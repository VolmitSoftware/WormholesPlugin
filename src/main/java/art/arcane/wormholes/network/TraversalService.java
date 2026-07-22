package art.arcane.wormholes.network;

import art.arcane.volmlib.util.scheduling.FoliaScheduler;
import art.arcane.volmlib.util.localization.MessageArgument;
import art.arcane.wormholes.Settings;
import art.arcane.wormholes.Wormholes;
import art.arcane.wormholes.config.toml.NetworkConfig;
import art.arcane.wormholes.localization.WormholesLocalization;
import art.arcane.wormholes.localization.WormholesMessages;
import art.arcane.wormholes.platform.WormholesPlatform;
import art.arcane.wormholes.portal.ILocalPortal;
import art.arcane.wormholes.portal.LocalPortal;
import art.arcane.wormholes.portal.Traversive;
import art.arcane.wormholes.portal.UniversalTunnel;
import art.arcane.wormholes.service.WormholesAudience;

import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.OfflinePlayer;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntitySnapshot;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.event.world.EntitiesLoadEvent;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.util.Vector;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;

public final class TraversalService implements Listener {
    public record Stats(long completed, long failed, int inFlight) {
    }

    record DestinationPlayerState(
        boolean directTransfer,
        boolean transferSupported,
        boolean banned,
        boolean whitelistEnabled,
        boolean whitelisted,
        boolean operator,
        int admittedPlayers,
        int maxPlayers
    ) {
    }

    private record PendingHandoff(Player player, UUID playerId, String peerName, UUID sourcePortalId,
                                  Traversive traversive, PlayerTransfer.Method transferMethod) {
    }

    private record EntityTransitState(boolean invulnerable, boolean silent, boolean gravity, Vector velocity) {
        private static EntityTransitState capture(Entity entity) {
            return new EntityTransitState(entity.isInvulnerable(), entity.isSilent(), entity.hasGravity(), entity.getVelocity().clone());
        }
    }

    private record PendingEntityTransfer(Entity entity, String peerName, UUID sourcePortalId, Traversive traversive,
                                         EntityTransitState transitState, long deadlineMillis) {
    }

    private record ArrivalPlacement(Player player, PlayerHandoffAdmission.Reservation reservation, String via, int attempt) {
        ArrivalPlacement retry(PlayerHandoffAdmission.Reservation nextReservation) {
            return new ArrivalPlacement(player, nextReservation, via, attempt + 1);
        }
    }

    private record ArrivalTeleport(ArrivalPlacement placement, ILocalPortal exit, Traversive traversive) {
    }

    private static final long ARRIVAL_TTL_MILLIS = 60_000L;
    private static final long ENTITY_DEDUPE_TTL_MILLIS = 60_000L;
    private static final long MIN_HANDOFF_RATE_LIMIT_MILLIS = 1_000L;
    private static final int MAX_ARRIVAL_PLACEMENT_ATTEMPTS = 5;
    static final NamespacedKey TRANSIT_STAMP_KEY = new NamespacedKey("wormholes", "entity_transit_state");

    private final NetworkManager network;
    private final Map<UUID, PendingHandoff> pendingHandoffs = new ConcurrentHashMap<>();
    private final PlayerHandoffAdmission inboundAdmissions = new PlayerHandoffAdmission();
    private final PlayerHandoffRateLimiter outboundRateLimiter = new PlayerHandoffRateLimiter();
    private final Map<UUID, PendingEntityTransfer> pendingEntityTransfers = new ConcurrentHashMap<>();
    private final Set<UUID> pendingSourceRemovals = ConcurrentHashMap.newKeySet();
    private final EntityTransferLedger appliedEntityTransfers = new EntityTransferLedger();
    private final Map<UUID, Long> transferLocks = new ConcurrentHashMap<>();
    private final AtomicLong completedTransfers = new AtomicLong();
    private final AtomicLong failedTransfers = new AtomicLong();

    public TraversalService(NetworkManager network) {
        this.network = network;
    }

    public Stats statsSnapshot() {
        int inFlight = pendingHandoffs.size() + pendingEntityTransfers.size();
        return new Stats(completedTransfers.get(), failedTransfers.get(), inFlight);
    }

    public void beginPlayerHandoff(Player player, UniversalTunnel tunnel, Traversive traversive) {
        beginPlayerHandoff(player, tunnel, traversive, null);
    }

    public void beginPlayerHandoff(Player player, UniversalTunnel tunnel, Traversive traversive, LocalPortal sourcePortal) {
        String peerName = tunnel.getServerName();
        NetworkConfig config = Wormholes.settings.getNetwork();
        UUID playerId = player.getUniqueId();
        long now = System.currentTimeMillis();
        long rateLimitMillis = handoffRateLimitMillis();
        PlayerHandoffRateLimiter.Decision rateDecision = outboundRateLimiter.acquire(playerId, now, rateLimitMillis);
        if (!rateDecision.allowed()) {
            rejectSource(player, sourcePortal, traversive);
            notifyCooldown(player, rateDecision.retryAfterMillis());
            Wormholes.i("[handoff] BLOCKED " + player.getName() + " -> " + peerName + ": rate limited for " + rateDecision.retryAfterMillis() + "ms");
            return;
        }

        NetworkConfig.PeerEntry peer = network.getPeer(peerName);
        if (peer == null) {
            rejectSource(player, sourcePortal, traversive);
            notifyUnreachable(player, "peer '" + peerName + "' not configured");
            return;
        }
        if (!network.isPeerReady(peerName)) {
            rejectSource(player, sourcePortal, traversive);
            notifyUnreachable(player, peerName + " not connected");
            return;
        }
        PlayerTransfer.Method transferMethod = PlayerTransfer.resolveMethod(peer, config.transferMode);
        if (transferMethod == PlayerTransfer.Method.DIRECT && !PlayerTransfer.hasDirectHost(peer)) {
            rejectSource(player, sourcePortal, traversive);
            notifyUnreachable(player, peerName + " has no game-port host configured");
            return;
        }
        pruneTransferLocks(now);
        long lockRemainingMillis = remainingTransferLock(playerId, now);
        if (lockRemainingMillis > 0L) {
            outboundRateLimiter.penalize(playerId, now, Math.max(rateLimitMillis, lockRemainingMillis));
            rejectSource(player, sourcePortal, traversive);
            notifyCooldown(player, lockRemainingMillis);
            Wormholes.i("[handoff] BLOCKED " + player.getName() + " -> " + peerName + ": transfer-locked (recent transfer not yet cleared)");
            return;
        }

        UUID transferId = UUID.randomUUID();
        long deadline = now + config.handoffTimeoutMs;
        lockTransfer(playerId, deadline);
        pendingHandoffs.put(transferId, new PendingHandoff(
            player,
            playerId,
            peerName,
            sourcePortalId(sourcePortal),
            traversive,
            transferMethod
        ));
        boolean directTransfer = transferMethod == PlayerTransfer.Method.DIRECT;
        Wormholes.i("[handoff] begin " + player.getName() + " -> peer=" + peerName + " destPortal=" + tunnel.getDestinationPortalId() + " transferId=" + transferId + " method=" + transferMethod + " transactional=true");
        boolean queued = network.send(peerName, new WireMessage.HandoffRequest(
            transferId,
            playerId,
            player.getName(),
            tunnel.getDestinationPortalId(),
            directTransfer,
            WireTraversive.fromTraversive(traversive)
        ));
        if (!queued) {
            pendingHandoffs.remove(transferId);
            unlockTransfer(playerId);
            outboundRateLimiter.penalize(playerId, System.currentTimeMillis(), rateLimitMillis);
            failedTransfers.incrementAndGet();
            rejectSource(player, sourcePortal, traversive);
            notifyUnreachable(player, peerName + " could not queue the handoff request");
            return;
        }
        if (Wormholes.viewServer != null) {
            Wormholes.viewServer.onPortalTraversed(peerName, tunnel.getDestinationPortalId());
        }

        long timeoutTicks = Math.max(1L, config.handoffTimeoutMs / 50L);
        Runnable handoffTimeoutBody = () -> {
            PendingHandoff expired = pendingHandoffs.remove(transferId);
            if (expired != null) {
                network.send(peerName, new WireMessage.HandoffCancel(transferId, expired.playerId()));
                unlockTransfer(expired.playerId());
                outboundRateLimiter.penalize(expired.playerId(), System.currentTimeMillis(), rateLimitMillis);
                failedTransfers.incrementAndGet();
                if (player.isOnline()) {
                    rejectSource(player, expired);
                    notifyUnreachable(player, peerName + " did not ack within " + config.handoffTimeoutMs + "ms");
                }
            }
        };
        Runnable handoffTimeoutRetired = () -> {
            PendingHandoff expired = pendingHandoffs.remove(transferId);
            if (expired != null) {
                network.send(peerName, new WireMessage.HandoffCancel(transferId, expired.playerId()));
                unlockTransfer(expired.playerId());
            }
        };
        boolean timeoutScheduled = WormholesPlatform.scheduleEntity(Wormholes.instance, player, handoffTimeoutBody, handoffTimeoutRetired, timeoutTicks);
        if (!timeoutScheduled) {
            PendingHandoff rejected = pendingHandoffs.remove(transferId);
            if (rejected != null) {
                network.send(peerName, new WireMessage.HandoffCancel(transferId, rejected.playerId()));
                unlockTransfer(rejected.playerId());
                outboundRateLimiter.penalize(rejected.playerId(), System.currentTimeMillis(), rateLimitMillis);
                failedTransfers.incrementAndGet();
                rejectSource(player, rejected);
                notifyUnreachable(player, "source scheduler rejected the handoff timeout");
            }
        }
    }

    public void beginEntityTransfer(Entity entity, UniversalTunnel tunnel, Traversive traversive) {
        beginEntityTransfer(entity, tunnel, traversive, null);
    }

    public void beginEntityTransfer(Entity entity, UniversalTunnel tunnel, Traversive traversive, LocalPortal sourcePortal) {
        String peerName = tunnel.getServerName();
        if (network.getPeer(peerName) == null || !network.isPeerReady(peerName)) {
            rejectSource(entity, sourcePortal, traversive);
            return;
        }
        NetworkConfig config = Wormholes.settings.getNetwork();
        long now = System.currentTimeMillis();
        pruneTransferLocks(now);
        if (isTransferLocked(entity.getUniqueId(), now)) {
            return;
        }
        long deadline = now + config.handoffTimeoutMs;
        lockTransfer(entity.getUniqueId(), deadline);

        EntitySnapshot snapshot = entity.createSnapshot();
        if (snapshot == null) {
            unlockTransfer(entity.getUniqueId());
            rejectSource(entity, sourcePortal, traversive);
            return;
        }
        byte[] data = snapshot.getAsString().getBytes(StandardCharsets.UTF_8);
        if (data.length > WireMessage.EntityTransfer.MAX_SNAPSHOT_BYTES) {
            Wormholes.w("net: entity " + entity.getType() + " snapshot too large to transfer (" + data.length + " bytes)");
            unlockTransfer(entity.getUniqueId());
            rejectSource(entity, sourcePortal, traversive);
            return;
        }

        UUID transferId = UUID.randomUUID();
        PendingEntityTransfer pending = new PendingEntityTransfer(
            entity,
            peerName,
            sourcePortalId(sourcePortal),
            traversive,
            EntityTransitState.capture(entity),
            deadline
        );
        pendingEntityTransfers.put(transferId, pending);
        boolean sent = network.send(peerName, new WireMessage.EntityTransfer(transferId, tunnel.getDestinationPortalId(), data, WireTraversive.fromTraversive(traversive)));
        if (!sent) {
            if (pendingEntityTransfers.remove(transferId, pending)) {
                unlockTransfer(entity.getUniqueId());
                failedTransfers.incrementAndGet();
                restoreRejectedEntityTransfer(pending);
            }
            return;
        }
        markEntityInTransit(entity, transferId);
        long timeoutTicks = Math.max(1L, config.handoffTimeoutMs / 50L);
        Runnable transferTimeoutBody = () -> {
            PendingEntityTransfer expired = pendingEntityTransfers.remove(transferId);
            if (expired != null) {
                unlockTransfer(entity.getUniqueId());
                failedTransfers.incrementAndGet();
                restoreRejectedEntityTransfer(expired);
            }
        };
        Runnable transferTimeoutRetired = () -> {
            PendingEntityTransfer expired = pendingEntityTransfers.remove(transferId);
            if (expired != null) {
                unlockTransfer(entity.getUniqueId());
                failedTransfers.incrementAndGet();
            }
        };
        boolean timeoutScheduled = WormholesPlatform.scheduleEntity(Wormholes.instance, entity, transferTimeoutBody, transferTimeoutRetired, timeoutTicks);
        if (!timeoutScheduled && pendingEntityTransfers.remove(transferId, pending)) {
            unlockTransfer(entity.getUniqueId());
            failedTransfers.incrementAndGet();
            restoreRejectedEntityTransfer(pending);
        }
        prunePendingEntityTransfers();
    }

    private void markEntityInTransit(Entity entity, UUID transferId) {
        if (entity == null || !entity.isValid()) {
            return;
        }
        FoliaScheduler.runEntity(Wormholes.instance, entity, () -> {
            if (!entity.isValid() || !pendingEntityTransfers.containsKey(transferId)) {
                return;
            }
            byte stamp = encodeTransitStamp(entity.isInvulnerable(), entity.isSilent(), entity.hasGravity());
            entity.getPersistentDataContainer().set(TRANSIT_STAMP_KEY, PersistentDataType.BYTE, Byte.valueOf(stamp));
            entity.setInvulnerable(true);
            entity.setSilent(true);
            entity.setGravity(false);
            entity.setVelocity(entity.getVelocity().zero());
        });
    }

    private void restoreRejectedEntityTransfer(PendingEntityTransfer pending) {
        Entity entity = pending == null ? null : pending.entity();
        if (entity == null) {
            return;
        }
        FoliaScheduler.runEntity(Wormholes.instance, entity, () -> {
            if (!entity.isValid()) {
                return;
            }
            entity.getPersistentDataContainer().remove(TRANSIT_STAMP_KEY);
            EntityTransitState state = pending.transitState();
            entity.setInvulnerable(state.invulnerable());
            entity.setSilent(state.silent());
            entity.setGravity(state.gravity());
            entity.setVelocity(state.velocity().clone());
            ILocalPortal source = Wormholes.portalManager == null || pending.sourcePortalId() == null
                ? null
                : Wormholes.portalManager.getLocalPortal(pending.sourcePortalId());
            if (source != null) {
                source.rejectDeparture(entity, pending.traversive());
            }
        });
    }

    public void onHandoffRequest(String peerName, WireMessage.HandoffRequest request) {
        boolean scheduled = FoliaScheduler.runGlobal(Wormholes.instance, () -> evaluateHandoffRequest(peerName, request));
        if (!scheduled) {
            long retryAfterMillis = handoffRateLimitMillis();
            network.send(peerName, new WireMessage.HandoffDeny(request.transferId(), "destination scheduler unavailable", retryAfterMillis));
        }
    }

    private void evaluateHandoffRequest(String peerName, WireMessage.HandoffRequest wireRequest) {
        long now = System.currentTimeMillis();
        long rateLimitMillis = handoffRateLimitMillis();
        PlayerHandoffAdmission.Request request = new PlayerHandoffAdmission.Request(
            wireRequest.transferId(),
            wireRequest.playerId(),
            wireRequest.playerName(),
            peerName,
            wireRequest.destPortalId(),
            wireRequest.directTransfer(),
            wireRequest.traversive()
        );
        ILocalPortal exit = Wormholes.portalManager == null ? null : Wormholes.portalManager.getLocalPortal(wireRequest.destPortalId());
        String denialReason = destinationDenialReason(wireRequest, exit, now);
        PlayerHandoffAdmission.Decision decision = inboundAdmissions.decide(new PlayerHandoffAdmission.Attempt(
            request,
            denialReason,
            now,
            ARRIVAL_TTL_MILLIS,
            rateLimitMillis
        ));
        if (!decision.accepted()) {
            network.send(peerName, new WireMessage.HandoffDeny(
                wireRequest.transferId(),
                decision.reason(),
                decision.retryAfterMillis()
            ));
            Wormholes.i("[handoff] request DENIED peer=" + peerName + " player=" + wireRequest.playerName() + " transferId=" + wireRequest.transferId() + " reason=" + decision.reason() + " retryAfterMs=" + decision.retryAfterMillis());
            return;
        }

        if (decision.fresh()) {
            try {
                Traversive traversive = wireRequest.traversive().toTraversive(null);
                warmArrivalChunk(exit, traversive);
            } catch (Throwable error) {
                inboundAdmissions.release(request, System.currentTimeMillis());
                network.send(peerName, new WireMessage.HandoffDeny(wireRequest.transferId(), "destination preparation failed", rateLimitMillis));
                Wormholes.instance.getLogger().log(Level.WARNING, "Failed to prepare player handoff from " + peerName, error);
                return;
            }
        }

        boolean ackQueued = inboundAdmissions.queueAcknowledgement(
            request,
            System.currentTimeMillis(),
            () -> network.send(peerName, new WireMessage.HandoffAck(wireRequest.transferId()))
        );
        if (!ackQueued) {
            if (decision.fresh()) {
                inboundAdmissions.release(request, System.currentTimeMillis());
            }
            Wormholes.w("[handoff] admission ended or ACK could not queue for peer=" + peerName + " transferId=" + wireRequest.transferId());
            return;
        }

        if (!decision.fresh()) {
            Wormholes.i("[handoff] request REPLAY peer=" + peerName + " player=" + wireRequest.playerName() + " transferId=" + wireRequest.transferId() + " — replayed admission ACK");
            return;
        }

        Player already = Wormholes.instance.getServer().getPlayer(wireRequest.playerId());
        PlayerHandoffAdmission.Reservation arrival = already == null || !already.isOnline()
            ? null
            : inboundAdmissions.claimArrival(wireRequest.playerId(), System.currentTimeMillis());
        if (arrival != null) {
            Wormholes.i("[handoff] request RX from peer=" + peerName + " player=" + wireRequest.playerName() + " — player already arrived; placing now at exitPortal=" + exit.getId());
            placeArrivingPlayer(already, arrival, "late-request");
            return;
        }
        Wormholes.i("[handoff] request RX from peer=" + peerName + " player=" + wireRequest.playerName() + " exitPortal=" + exit.getId() + " — destination admitted, acking");
    }

    private String destinationDenialReason(WireMessage.HandoffRequest request, ILocalPortal exit, long nowMillis) {
        if (exit == null) {
            return "unknown portal";
        }
        if (!exit.isOpen()) {
            return "portal closed";
        }
        if (exit.getStructure() == null || exit.getStructure().getWorld() == null) {
            return "portal world unavailable";
        }
        if (!acceptsInbound(exit)) {
            return "portal receive disabled";
        }

        Server server = Wormholes.instance.getServer();
        NetworkConfig networkConfig = Wormholes.settings.getNetwork();
        Player online = server.getPlayer(request.playerId());
        if (online != null && online.isOnline()) {
            return "player already connected";
        }
        OfflinePlayer profile = server.getOfflinePlayer(request.playerId());
        boolean operator = profile.isOp();
        int maxPlayers = server.getMaxPlayers();
        int admittedPlayers = server.getOnlinePlayers().size() + inboundAdmissions.activeReservations(nowMillis);
        boolean transferSupported = networkConfig.autoAcceptTransfers || WormholesPlatform.isAcceptingTransfers(server);
        return destinationPlayerDenialReason(new DestinationPlayerState(
            request.directTransfer(),
            transferSupported,
            profile.isBanned(),
            server.hasWhitelist(),
            profile.isWhitelisted(),
            operator,
            admittedPlayers,
            maxPlayers
        ));
    }

    static String destinationPlayerDenialReason(DestinationPlayerState state) {
        if (state.directTransfer() && !state.transferSupported()) {
            return "destination does not accept direct transfers";
        }
        if (state.banned()) {
            return "player is banned";
        }
        if (state.whitelistEnabled() && !state.operator() && !state.whitelisted()) {
            return "player is not whitelisted";
        }
        if (state.maxPlayers() > 0 && state.admittedPlayers() >= state.maxPlayers()) {
            return "destination server is full";
        }
        return null;
    }

    public void onHandoffAck(String peerName, WireMessage.HandoffAck ack) {
        PendingHandoff handoff = pendingHandoffs.get(ack.transferId());
        if (handoff == null || !handoff.peerName().equals(peerName)
            || !pendingHandoffs.remove(ack.transferId(), handoff)) {
            return;
        }
        Player player = handoff.player();
        NetworkConfig.PeerEntry peer = network.getPeer(peerName);
        if (peer == null) {
            network.send(peerName, new WireMessage.HandoffCancel(ack.transferId(), handoff.playerId()));
            unlockTransfer(handoff.playerId());
            outboundRateLimiter.penalize(handoff.playerId(), System.currentTimeMillis(), handoffRateLimitMillis());
            failedTransfers.incrementAndGet();
            rejectSource(player, handoff);
            notifyUnreachable(player, "peer '" + peerName + "' disappeared between handoff and ack");
            return;
        }
        boolean scheduled = FoliaScheduler.runEntity(Wormholes.instance, player, () -> {
            if (!player.isOnline()) {
                network.send(peerName, new WireMessage.HandoffCancel(ack.transferId(), handoff.playerId()));
                unlockTransfer(handoff.playerId());
                return;
            }
            ILocalPortal source = sourcePortal(handoff.sourcePortalId());
            if (handoff.sourcePortalId() != null && (source == null || !source.canCompleteDeparture(player, handoff.traversive()))) {
                network.send(peerName, new WireMessage.HandoffCancel(ack.transferId(), handoff.playerId()));
                unlockTransfer(handoff.playerId());
                long retryAfterMillis = handoffRateLimitMillis();
                outboundRateLimiter.penalize(handoff.playerId(), System.currentTimeMillis(), retryAfterMillis);
                failedTransfers.incrementAndGet();
                notifyDenied(player, source == null ? "source portal unavailable" : "you left the source portal", retryAfterMillis);
                return;
            }
            if (source != null) {
                source.confirmDeparture(player, handoff.traversive());
            }
            String privateEndpoint = network.privatePlayerEndpoint(peerName);
            if (!PlayerTransfer.send(player, peer, handoff.transferMethod(), privateEndpoint)) {
                network.send(peerName, new WireMessage.HandoffCancel(ack.transferId(), handoff.playerId()));
                unlockTransfer(handoff.playerId());
                outboundRateLimiter.penalize(handoff.playerId(), System.currentTimeMillis(), handoffRateLimitMillis());
                failedTransfers.incrementAndGet();
                rejectSource(player, handoff);
                notifyUnreachable(player, "transfer method '" + handoff.transferMethod() + "' was rejected by Bukkit");
                return;
            }
            completedTransfers.incrementAndGet();
            Wormholes.i("[handoff] ack RX from peer=" + peerName + " — transfer of " + player.getName() + " dispatched via " + handoff.transferMethod());
            lockTransfer(handoff.playerId(), System.currentTimeMillis() + ARRIVAL_TTL_MILLIS);
        });
        if (!scheduled) {
            network.send(peerName, new WireMessage.HandoffCancel(ack.transferId(), handoff.playerId()));
            unlockTransfer(handoff.playerId());
            outboundRateLimiter.penalize(handoff.playerId(), System.currentTimeMillis(), handoffRateLimitMillis());
            failedTransfers.incrementAndGet();
            rejectSource(player, handoff);
            notifyUnreachable(player, "source scheduler rejected the transfer");
        }
    }

    public void onHandoffDeny(String peerName, WireMessage.HandoffDeny deny) {
        PendingHandoff handoff = pendingHandoffs.get(deny.transferId());
        if (handoff == null || !handoff.peerName().equals(peerName)
            || !pendingHandoffs.remove(deny.transferId(), handoff)) {
            return;
        }
        unlockTransfer(handoff.playerId());
        long retryAfterMillis = Math.max(handoffRateLimitMillis(), deny.retryAfterMillis());
        outboundRateLimiter.penalize(handoff.playerId(), System.currentTimeMillis(), retryAfterMillis);
        failedTransfers.incrementAndGet();
        Player player = handoff.player();
        rejectSource(player, handoff);
        String reason = deny.reason() == null || deny.reason().isBlank() ? "destination denied" : deny.reason();
        notifyDenied(player, reason, retryAfterMillis);
    }

    public void onHandoffCancel(String peerName, WireMessage.HandoffCancel cancel) {
        inboundAdmissions.cancel(new PlayerHandoffAdmission.Cancellation(
            peerName,
            cancel.transferId(),
            cancel.playerId(),
            System.currentTimeMillis(),
            handoffRateLimitMillis(),
            ARRIVAL_TTL_MILLIS
        ));
    }

    public void onEntityTransfer(String peerName, WireMessage.EntityTransfer transfer) {
        long now = System.currentTimeMillis();
        EntityTransferLedger.Claim claim = appliedEntityTransfers.claim(transfer.transferId(), now);
        if (claim.status() == EntityTransferLedger.ClaimStatus.APPLIED) {
            network.send(peerName, new WireMessage.EntityTransferAck(transfer.transferId(), true));
            return;
        }
        if (claim.status() == EntityTransferLedger.ClaimStatus.IN_FLIGHT) {
            return;
        }

        ILocalPortal exit = Wormholes.portalManager == null ? null : Wormholes.portalManager.getLocalPortal(transfer.destPortalId());
        if (exit == null || !exit.isOpen() || exit.getStructure() == null || exit.getStructure().getWorld() == null) {
            appliedEntityTransfers.release(transfer.transferId(), claim);
            network.send(peerName, new WireMessage.EntityTransferAck(transfer.transferId(), false));
            return;
        }
        if (!acceptsInbound(exit)) {
            appliedEntityTransfers.release(transfer.transferId(), claim);
            network.send(peerName, new WireMessage.EntityTransferAck(transfer.transferId(), false));
            return;
        }

        Traversive traversive = transfer.traversive().toTraversive(null);
        Location target = exit.computeExitTarget(traversive);
        boolean scheduled = FoliaScheduler.runRegion(Wormholes.instance, target,
            () -> applyInboundEntityTransfer(peerName, transfer, exit, traversive, target, claim));
        if (!scheduled) {
            appliedEntityTransfers.release(transfer.transferId(), claim);
            network.send(peerName, new WireMessage.EntityTransferAck(transfer.transferId(), false));
        }
    }

    private void applyInboundEntityTransfer(String peerName, WireMessage.EntityTransfer transfer, ILocalPortal exit,
                                            Traversive traversive, Location target, EntityTransferLedger.Claim claim) {
        Entity created = null;
        boolean accepted = false;
        try {
            EntitySnapshot snapshot = Wormholes.instance.getServer().getEntityFactory().createEntitySnapshot(
                new String(transfer.entitySnapshot(), StandardCharsets.UTF_8));
            if (!isEntityTypeDenied(snapshot)) {
                created = snapshot.createEntity(target);
                if (created != null) {
                    exit.completeRemoteArrival(created, traversive);
                    accepted = appliedEntityTransfers.markApplied(transfer.transferId(), claim, System.currentTimeMillis());
                }
            }
        } catch (Throwable error) {
            Wormholes.instance.getLogger().log(Level.WARNING, "Failed to apply entity transfer from " + peerName, error);
        }
        if (!accepted) {
            if (created != null && created.isValid()) {
                created.remove();
            }
            appliedEntityTransfers.release(transfer.transferId(), claim);
        } else {
            pruneAppliedEntityTransfers();
        }
        network.send(peerName, new WireMessage.EntityTransferAck(transfer.transferId(), accepted));
    }

    static boolean acceptsInbound(ILocalPortal portal) {
        return portal != null
            && !portal.isMirrorMode()
            && portal.isIncomingTraversalsEnabled();
    }

    public void onEntityTransferAck(String peerName, WireMessage.EntityTransferAck ack) {
        PendingEntityTransfer pending = pendingEntityTransfers.get(ack.transferId());
        if (pending == null || !pending.peerName().equals(peerName) || !pendingEntityTransfers.remove(ack.transferId(), pending)) {
            return;
        }
        unlockTransfer(pending.entity().getUniqueId());
        if (!ack.accepted()) {
            failedTransfers.incrementAndGet();
            restoreRejectedEntityTransfer(pending);
            return;
        }
        completedTransfers.incrementAndGet();
        Entity entity = pending.entity();
        Runnable removalBody = () -> {
            if (entity.isValid()) {
                entity.remove();
            }
        };
        Runnable removalRetired = () -> queueSourceRemoval(entity.getUniqueId());
        if (!WormholesPlatform.scheduleEntity(Wormholes.instance, entity, removalBody, removalRetired, 0L)) {
            queueSourceRemoval(entity.getUniqueId());
        }
    }

    void queueSourceRemoval(UUID entityId) {
        pendingSourceRemovals.add(entityId);
    }

    @EventHandler
    public void on(EntitiesLoadEvent event) {
        for (Entity entity : event.getEntities()) {
            reconcileLoadedEntity(entity);
        }
    }

    public void sweepStrandedTransitEntities() {
        if (FoliaScheduler.isFoliaThreading(Wormholes.instance.getServer())) {
            return;
        }
        FoliaScheduler.runGlobal(Wormholes.instance, () -> {
            for (World world : Bukkit.getWorlds()) {
                for (Entity entity : world.getEntities()) {
                    reconcileLoadedEntity(entity);
                }
            }
        });
    }

    void reconcileLoadedEntity(Entity entity) {
        if (entity instanceof Player) {
            return;
        }
        if (pendingSourceRemovals.remove(entity.getUniqueId())) {
            entity.remove();
            return;
        }
        restoreStrandedTransitEntity(entity);
    }

    private void restoreStrandedTransitEntity(Entity entity) {
        PersistentDataContainer container = entity.getPersistentDataContainer();
        Byte stamp = container.get(TRANSIT_STAMP_KEY, PersistentDataType.BYTE);
        if (stamp == null || hasLiveTransfer(entity.getUniqueId())) {
            return;
        }
        container.remove(TRANSIT_STAMP_KEY);
        entity.setInvulnerable(stampInvulnerable(stamp.byteValue()));
        entity.setSilent(stampSilent(stamp.byteValue()));
        entity.setGravity(stampGravity(stamp.byteValue()));
    }

    private boolean hasLiveTransfer(UUID entityId) {
        for (PendingEntityTransfer pending : pendingEntityTransfers.values()) {
            if (entityId.equals(pending.entity().getUniqueId())) {
                return true;
            }
        }
        return false;
    }

    static byte encodeTransitStamp(boolean invulnerable, boolean silent, boolean gravity) {
        byte flags = 0;
        if (invulnerable) {
            flags |= 1;
        }
        if (silent) {
            flags |= 2;
        }
        if (gravity) {
            flags |= 4;
        }
        return flags;
    }

    static boolean stampInvulnerable(byte flags) {
        return (flags & 1) != 0;
    }

    static boolean stampSilent(byte flags) {
        return (flags & 2) != 0;
    }

    static boolean stampGravity(byte flags) {
        return (flags & 4) != 0;
    }

    @EventHandler
    public void on(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        LocalPortal.latchReentryIfInsidePortal(player);
        unlockTransfer(player.getUniqueId());
        PlayerHandoffAdmission.Reservation arrival = inboundAdmissions.claimArrival(player.getUniqueId(), System.currentTimeMillis());
        if (arrival == null) {
            Wormholes.i("[arrival] join " + player.getName() + " at " + locStr(player.getLocation()) + " — NO pending cross-server arrival; not managed, relying on join-latch");
            return;
        }
        placeArrivingPlayer(player, arrival, "join");
    }

    private void placeArrivingPlayer(Player player, PlayerHandoffAdmission.Reservation arrival, String via) {
        ArrivalPlacement placement = new ArrivalPlacement(player, arrival, via, 0);
        Runnable retired = () -> inboundAdmissions.releaseArrival(arrival, System.currentTimeMillis());
        if (!FoliaScheduler.runEntity(Wormholes.instance, player, () -> beginArrivalPlacement(placement), 0L, retired)) {
            retired.run();
            Wormholes.w("[arrival] " + via + " " + player.getName() + " — player scheduler rejected portal placement");
        }
    }

    private void beginArrivalPlacement(ArrivalPlacement placement) {
        Player player = placement.player();
        PlayerHandoffAdmission.Request request = placement.reservation().request();
        ILocalPortal exit = Wormholes.portalManager == null ? null : Wormholes.portalManager.getLocalPortal(request.exitPortalId());
        if (exit == null || exit.getStructure() == null || exit.getStructure().getWorld() == null) {
            retryArrivalPlacement(placement, "portal or world is unavailable", null);
            return;
        }

        Traversive traversive;
        try {
            traversive = request.traversive().toTraversive(player);
        } catch (RuntimeException error) {
            retryArrivalPlacement(placement, "arrival geometry is invalid", error);
            return;
        }
        LocalPortal.latchReentry(player.getUniqueId(), exit.getId());
        if (!exit.isOpen() || !exit.canArrive(player)) {
            inboundAdmissions.completeArrival(placement.reservation(), System.currentTimeMillis());
            Wormholes.i("[arrival] " + placement.via() + " " + player.getName() + " DENIED at exitPortal=" + exit.getId() + " (closed/incoming disabled/permission)");
            exit.rejectRemoteArrival(player, traversive);
            return;
        }

        Location target;
        try {
            target = exit.computeExitTarget(traversive);
        } catch (RuntimeException error) {
            retryArrivalPlacement(placement, "exit target could not be computed", error);
            return;
        }
        Wormholes.i("[arrival] " + placement.via() + " " + player.getName() + " spawnLoc=" + locStr(player.getLocation()) + " exitPortal=" + exit.getId() + " -> teleport target=" + locStr(target) + " (latched to exit)");
        ArrivalTeleport teleport = new ArrivalTeleport(placement, exit, traversive);
        WormholesPlatform.teleport(Wormholes.instance, player, target, PlayerTeleportEvent.TeleportCause.PLUGIN).whenComplete((success, error) -> {
            boolean scheduled = FoliaScheduler.runEntity(
                Wormholes.instance,
                player,
                () -> finishArrivalTeleport(teleport, Boolean.TRUE.equals(success), error),
                0L,
                () -> inboundAdmissions.releaseArrival(placement.reservation(), System.currentTimeMillis())
            );
            if (!scheduled) {
                inboundAdmissions.releaseArrival(placement.reservation(), System.currentTimeMillis());
                Wormholes.w("[arrival] " + placement.via() + " " + player.getName() + " — player retired before teleport completion could be handled");
            }
        });
    }

    private void finishArrivalTeleport(ArrivalTeleport teleport, boolean success, Throwable error) {
        ArrivalPlacement placement = teleport.placement();
        if (!success || error != null) {
            retryArrivalPlacement(placement, "portal teleport did not complete", error);
            return;
        }
        inboundAdmissions.completeArrival(placement.reservation(), System.currentTimeMillis());
        teleport.exit().completeRemoteArrival(placement.player(), teleport.traversive());
    }

    private void retryArrivalPlacement(ArrivalPlacement placement, String reason, Throwable error) {
        Player player = placement.player();
        LocalPortal.clearReentryLatch(player.getUniqueId());
        if (error == null) {
            Wormholes.w("[arrival] " + placement.via() + " " + player.getName() + " — " + reason + " (attempt " + (placement.attempt() + 1) + ")");
        } else {
            Wormholes.instance.getLogger().log(Level.WARNING, "[arrival] " + placement.via() + " " + player.getName() + " — " + reason + " (attempt " + (placement.attempt() + 1) + ")", error);
        }
        if (placement.attempt() + 1 >= MAX_ARRIVAL_PLACEMENT_ATTEMPTS) {
            inboundAdmissions.completeArrival(placement.reservation(), System.currentTimeMillis());
            WormholesAudience.sendActionBar(player, Wormholes.text().component(WormholesMessages.PORTAL_ARRIVAL_FAILED));
            return;
        }
        inboundAdmissions.releaseArrival(placement.reservation(), System.currentTimeMillis());
        long delayTicks = Math.min(20L, 2L << placement.attempt());
        Runnable retryBody = () -> {
            PlayerHandoffAdmission.Reservation next = inboundAdmissions.claimArrival(player.getUniqueId(), System.currentTimeMillis());
            if (next == null) {
                return;
            }
            if (!next.request().transferId().equals(placement.reservation().request().transferId())) {
                inboundAdmissions.releaseArrival(next, System.currentTimeMillis());
                return;
            }
            beginArrivalPlacement(placement.retry(next));
        };
        Runnable retryRetired = () -> inboundAdmissions.releaseArrival(placement.reservation(), System.currentTimeMillis());
        if (!WormholesPlatform.scheduleEntity(Wormholes.instance, player, retryBody, retryRetired, delayTicks)) {
            Wormholes.w("[arrival] " + placement.via() + " " + player.getName() + " — player scheduler rejected placement retry");
        }
    }

    private static UUID sourcePortalId(ILocalPortal portal) {
        return portal == null ? null : portal.getId();
    }

    private static String locStr(Location loc) {
        if (loc == null) {
            return "null";
        }
        String worldName = loc.getWorld() == null ? "?" : loc.getWorld().getName();
        return worldName + " " + (int) Math.floor(loc.getX()) + "," + (int) Math.floor(loc.getY()) + "," + (int) Math.floor(loc.getZ());
    }

    private void rejectSource(Player player, PendingHandoff handoff) {
        rejectSource(player, handoff.sourcePortalId(), handoff.traversive());
    }

    private void rejectSource(Entity entity, PendingEntityTransfer pending) {
        rejectSource(entity, pending.sourcePortalId(), pending.traversive());
    }

    private void rejectSource(Entity entity, ILocalPortal sourcePortal, Traversive traversive) {
        rejectSource(entity, sourcePortalId(sourcePortal), traversive);
    }

    private void rejectSource(Entity entity, UUID sourcePortalId, Traversive traversive) {
        if (entity == null || traversive == null || sourcePortalId == null) {
            return;
        }
        FoliaScheduler.runEntity(Wormholes.instance, entity, () -> {
            ILocalPortal source = Wormholes.portalManager == null ? null : Wormholes.portalManager.getLocalPortal(sourcePortalId);
            if (entity.isValid() && source != null) {
                source.rejectDeparture(entity, traversive);
            }
        });
    }

    private ILocalPortal sourcePortal(UUID sourcePortalId) {
        return sourcePortalId == null || Wormholes.portalManager == null
            ? null
            : Wormholes.portalManager.getLocalPortal(sourcePortalId);
    }

    private boolean isTransferLocked(UUID entityId, long now) {
        return remainingTransferLock(entityId, now) > 0L;
    }

    private long remainingTransferLock(UUID entityId, long now) {
        Long until = transferLocks.get(entityId);
        if (until == null) {
            return 0L;
        }
        if (until.longValue() <= now) {
            transferLocks.remove(entityId, until);
            return 0L;
        }
        return until.longValue() - now;
    }

    private void lockTransfer(UUID entityId, long untilMillis) {
        transferLocks.put(entityId, Long.valueOf(untilMillis));
    }

    private void unlockTransfer(UUID entityId) {
        transferLocks.remove(entityId);
    }

    private void pruneTransferLocks(long now) {
        if (transferLocks.size() < 512) {
            return;
        }
        transferLocks.values().removeIf(until -> until.longValue() <= now);
    }

    private void warmArrivalChunk(ILocalPortal exit, Traversive traversive) {
        if (exit == null || traversive == null || exit.getStructure() == null || exit.getStructure().getWorld() == null) {
            return;
        }
        Location target = exit.computeExitTarget(traversive);
        World world = target.getWorld();
        if (world == null) {
            return;
        }
        int centerX = target.getBlockX() >> 4;
        int centerZ = target.getBlockZ() >> 4;
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                WormholesPlatform.loadChunk(Wormholes.instance, world, centerX + dx, centerZ + dz);
            }
        }
    }

    private static boolean isEntityTypeDenied(EntitySnapshot snapshot) {
        NetworkConfig config = Wormholes.settings.getNetwork();
        String denyList = config.entityTransferDenyTypes;
        if (denyList == null || denyList.isBlank()) {
            return false;
        }
        String entityType = snapshot.getEntityType().name();
        for (String token : denyList.split(",")) {
            if (token.trim().equalsIgnoreCase(entityType)) {
                return true;
            }
        }
        return false;
    }

    private void notifyUnreachable(Player player, String reason) {
        if (reason == null || reason.isBlank()) {
            sendActionBar(player, Wormholes.text().component(WormholesMessages.PORTAL_DESTINATION_UNREACHABLE));
            return;
        }
        sendActionBar(player, Wormholes.text().component(
                WormholesMessages.PORTAL_DESTINATION_UNREACHABLE_DETAIL,
                WormholesLocalization.args(MessageArgument.untrusted("reason", reason))));
    }

    private void notifyCooldown(Player player, long retryAfterMillis) {
        sendActionBar(player, Wormholes.text().component(
                WormholesMessages.PORTAL_TRANSFER_COOLDOWN,
                WormholesLocalization.args(MessageArgument.untrusted("seconds", formatSeconds(retryAfterMillis)))));
    }

    private void notifyDenied(Player player, String reason, long retryAfterMillis) {
        if (retryAfterMillis <= 0L) {
            sendActionBar(player, Wormholes.text().component(
                    WormholesMessages.PORTAL_TRANSFER_BLOCKED,
                    WormholesLocalization.args(MessageArgument.untrusted("reason", reason))));
            return;
        }
        sendActionBar(player, Wormholes.text().component(
                WormholesMessages.PORTAL_TRANSFER_BLOCKED_RETRY,
                WormholesLocalization.args(
                        MessageArgument.untrusted("reason", reason),
                        MessageArgument.untrusted("seconds", formatSeconds(retryAfterMillis)))));
    }

    private void sendActionBar(Player player, Component message) {
        if (player == null) {
            return;
        }
        FoliaScheduler.runEntity(Wormholes.instance, player, () -> {
            if (player.isOnline()) {
                WormholesAudience.sendActionBar(player, message);
            }
        });
    }

    private static String formatSeconds(long millis) {
        long tenths = Math.max(1L, (millis + 99L) / 100L);
        return Long.toString(tenths / 10L) + "." + Long.toString(tenths % 10L);
    }

    private static long handoffRateLimitMillis() {
        return Math.max(MIN_HANDOFF_RATE_LIMIT_MILLIS, Settings.TELEPORT_COOLDOWN_MILLIS);
    }

    private void prunePendingEntityTransfers() {
        long now = System.currentTimeMillis();
        for (Map.Entry<UUID, PendingEntityTransfer> entry : pendingEntityTransfers.entrySet()) {
            PendingEntityTransfer pending = entry.getValue();
            if (pending.deadlineMillis() >= now) {
                continue;
            }
            if (pendingEntityTransfers.remove(entry.getKey(), pending)) {
                unlockTransfer(pending.entity().getUniqueId());
                failedTransfers.incrementAndGet();
                restoreRejectedEntityTransfer(pending);
            }
        }
    }

    private void pruneAppliedEntityTransfers() {
        appliedEntityTransfers.pruneApplied(System.currentTimeMillis(), ENTITY_DEDUPE_TTL_MILLIS, 256);
    }

    static final class EntityTransferLedger {
        enum ClaimStatus {
            STARTED,
            IN_FLIGHT,
            APPLIED
        }

        record Claim(ClaimStatus status, long token) {
        }

        private enum TransferStatus {
            IN_FLIGHT,
            APPLIED
        }

        private record Entry(long token, TransferStatus status, long updatedAtMillis) {
        }

        private final Map<UUID, Entry> entries = new ConcurrentHashMap<>();
        private final AtomicLong nextToken = new AtomicLong();

        Claim claim(UUID transferId, long nowMillis) {
            long token = nextToken.incrementAndGet();
            Entry fresh = new Entry(token, TransferStatus.IN_FLIGHT, nowMillis);
            Entry existing = entries.putIfAbsent(transferId, fresh);
            if (existing == null) {
                return new Claim(ClaimStatus.STARTED, token);
            }
            ClaimStatus status = existing.status() == TransferStatus.APPLIED ? ClaimStatus.APPLIED : ClaimStatus.IN_FLIGHT;
            return new Claim(status, existing.token());
        }

        boolean markApplied(UUID transferId, Claim claim, long nowMillis) {
            if (claim == null || claim.status() != ClaimStatus.STARTED) {
                return false;
            }
            Entry[] changed = new Entry[1];
            entries.computeIfPresent(transferId, (ignored, current) -> {
                if (current.token() != claim.token() || current.status() != TransferStatus.IN_FLIGHT) {
                    return current;
                }
                Entry applied = new Entry(current.token(), TransferStatus.APPLIED, nowMillis);
                changed[0] = applied;
                return applied;
            });
            return changed[0] != null;
        }

        boolean release(UUID transferId, Claim claim) {
            if (claim == null || claim.status() != ClaimStatus.STARTED) {
                return false;
            }
            boolean[] removed = new boolean[1];
            entries.computeIfPresent(transferId, (ignored, current) -> {
                if (current.token() != claim.token()) {
                    return current;
                }
                removed[0] = true;
                return null;
            });
            return removed[0];
        }

        void pruneApplied(long nowMillis, long ttlMillis, int minimumSize) {
            if (entries.size() < minimumSize) {
                return;
            }
            entries.entrySet().removeIf(entry -> entry.getValue().status() == TransferStatus.APPLIED
                && entry.getValue().updatedAtMillis() + ttlMillis < nowMillis);
        }
    }
}
