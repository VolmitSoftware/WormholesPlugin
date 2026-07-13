package art.arcane.wormholes.network;

import art.arcane.volmlib.util.scheduling.FoliaScheduler;
import art.arcane.wormholes.Wormholes;
import art.arcane.wormholes.config.toml.NetworkConfig;
import art.arcane.wormholes.platform.WormholesPlatform;
import art.arcane.wormholes.portal.ILocalPortal;
import art.arcane.wormholes.portal.LocalPortal;
import art.arcane.wormholes.portal.Traversive;
import art.arcane.wormholes.portal.UniversalTunnel;
import art.arcane.wormholes.service.WormholesAudience;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntitySnapshot;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.util.Vector;

import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;

public final class TraversalService implements Listener {
    public record Stats(long completed, long failed, int inFlight) {
    }

    private record PendingHandoff(UUID playerId, String peerName, UUID sourcePortalId, Traversive traversive, long deadlineMillis) {
    }

    private record PendingArrival(String peerName, UUID exitPortalId, WireTraversive traversive, long expiresAtMillis) {
    }

    private record EntityTransitState(boolean invulnerable, boolean silent, boolean gravity, Vector velocity) {
        private static EntityTransitState capture(Entity entity) {
            return new EntityTransitState(entity.isInvulnerable(), entity.isSilent(), entity.hasGravity(), entity.getVelocity().clone());
        }
    }

    private record PendingEntityTransfer(Entity entity, String peerName, UUID sourcePortalId, Traversive traversive,
                                         EntityTransitState transitState, long deadlineMillis) {
    }

    private static final long ARRIVAL_TTL_MILLIS = 15_000L;
    private static final long ENTITY_DEDUPE_TTL_MILLIS = 60_000L;

    private final NetworkManager network;
    private final Map<UUID, PendingHandoff> pendingHandoffs = new ConcurrentHashMap<>();
    private final Map<UUID, PendingArrival> pendingArrivals = new ConcurrentHashMap<>();
    private final Map<UUID, PendingEntityTransfer> pendingEntityTransfers = new ConcurrentHashMap<>();
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
        if (mayUseDirectTransfer(config) && (peer.publicHost == null || peer.publicHost.isBlank())) {
            rejectSource(player, sourcePortal, traversive);
            notifyUnreachable(player, peerName + " has no public-host configured");
            return;
        }
        long now = System.currentTimeMillis();
        pruneTransferLocks(now);
        if (isTransferLocked(player.getUniqueId(), now)) {
            Wormholes.i("[handoff] BLOCKED " + player.getName() + " -> " + peerName + ": transfer-locked (recent transfer not yet cleared)");
            return;
        }

        UUID transferId = UUID.randomUUID();
        long deadline = now + config.handoffTimeoutMs;
        lockTransfer(player.getUniqueId(), deadline);
        pendingHandoffs.put(transferId, new PendingHandoff(player.getUniqueId(), peerName, sourcePortalId(sourcePortal), traversive, deadline));
        Wormholes.i("[handoff] begin " + player.getName() + " -> peer=" + peerName + " destPortal=" + tunnel.getDestinationPortalId() + " transferId=" + transferId + " transactional=true");
        boolean queued = network.send(peerName, new WireMessage.HandoffRequest(transferId, player.getUniqueId(), player.getName(), tunnel.getDestinationPortalId(), WireTraversive.fromTraversive(traversive)));
        if (!queued) {
            pendingHandoffs.remove(transferId);
            unlockTransfer(player.getUniqueId());
            failedTransfers.incrementAndGet();
            rejectSource(player, sourcePortal, traversive);
            notifyUnreachable(player, peerName + " could not queue the handoff request");
            return;
        }
        if (Wormholes.viewServer != null) {
            Wormholes.viewServer.onPortalTraversed(peerName, tunnel.getDestinationPortalId());
        }

        long timeoutTicks = Math.max(1L, config.handoffTimeoutMs / 50L);
        boolean timeoutScheduled = FoliaScheduler.runEntity(Wormholes.instance, player, () -> {
            PendingHandoff expired = pendingHandoffs.remove(transferId);
            if (expired != null) {
                unlockTransfer(expired.playerId());
                if (player.isOnline()) {
                    rejectSource(player, expired);
                    notifyUnreachable(player, peerName + " did not ack within " + config.handoffTimeoutMs + "ms");
                }
            }
        }, timeoutTicks);
        if (!timeoutScheduled) {
            PendingHandoff rejected = pendingHandoffs.remove(transferId);
            if (rejected != null) {
                network.send(peerName, new WireMessage.HandoffCancel(rejected.playerId()));
                unlockTransfer(rejected.playerId());
                failedTransfers.incrementAndGet();
                rejectSource(player, rejected);
                notifyUnreachable(player, "source scheduler rejected the handoff timeout");
            }
        }
    }

    private static boolean mayUseDirectTransfer(NetworkConfig config) {
        String mode = config.transferMode == null ? "auto" : config.transferMode.toLowerCase(Locale.ROOT);
        return !mode.equals("proxy");
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
        boolean timeoutScheduled = FoliaScheduler.runEntity(Wormholes.instance, entity, () -> {
            PendingEntityTransfer expired = pendingEntityTransfers.remove(transferId);
            if (expired != null) {
                unlockTransfer(entity.getUniqueId());
                failedTransfers.incrementAndGet();
                restoreRejectedEntityTransfer(expired);
            }
        }, timeoutTicks);
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
        ILocalPortal exit = Wormholes.portalManager == null ? null : Wormholes.portalManager.getLocalPortal(request.destPortalId());
        if (exit == null) {
            network.send(peerName, new WireMessage.HandoffDeny(request.transferId(), "unknown portal"));
            return;
        }
        if (!acceptsInbound(exit)) {
            network.send(peerName, new WireMessage.HandoffDeny(request.transferId(), "portal receive disabled"));
            return;
        }
        pruneArrivals();
        Traversive traversive = request.traversive().toTraversive(null);
        PendingArrival arrival = new PendingArrival(peerName, exit.getId(), request.traversive(), System.currentTimeMillis() + ARRIVAL_TTL_MILLIS);
        pendingArrivals.put(request.playerId(), arrival);
        warmArrivalChunk(exit, traversive);
        network.send(peerName, new WireMessage.HandoffAck(request.transferId()));
        Player already = Wormholes.instance.getServer().getPlayer(request.playerId());
        if (already != null && already.isOnline() && pendingArrivals.remove(request.playerId()) != null) {
            Wormholes.i("[handoff] request RX from peer=" + peerName + " player=" + request.playerName() + " — player already arrived; placing now at exitPortal=" + exit.getId());
            placeArrivingPlayer(already, arrival, "late-request");
        } else {
            Wormholes.i("[handoff] request RX from peer=" + peerName + " player=" + request.playerName() + " exitPortal=" + exit.getId() + " — registered pendingArrival, acking");
        }
    }

    public void onHandoffAck(String peerName, WireMessage.HandoffAck ack) {
        PendingHandoff handoff = pendingHandoffs.get(ack.transferId());
        if (handoff == null || !handoff.peerName().equals(peerName)
            || !pendingHandoffs.remove(ack.transferId(), handoff)) {
            return;
        }
        Player player = Wormholes.instance.getServer().getPlayer(handoff.playerId());
        if (player == null) {
            network.send(peerName, new WireMessage.HandoffCancel(handoff.playerId()));
            unlockTransfer(handoff.playerId());
            return;
        }
        NetworkConfig config = Wormholes.settings.getNetwork();
        NetworkConfig.PeerEntry peer = network.getPeer(peerName);
        if (peer == null) {
            unlockTransfer(handoff.playerId());
            rejectSource(player, handoff);
            notifyUnreachable(player, "peer '" + peerName + "' disappeared between handoff and ack");
            return;
        }
        boolean scheduled = FoliaScheduler.runEntity(Wormholes.instance, player, () -> {
            if (!player.isOnline()) {
                network.send(peerName, new WireMessage.HandoffCancel(handoff.playerId()));
                unlockTransfer(handoff.playerId());
                return;
            }
            if (!PlayerTransfer.send(player, peer, config.transferMode)) {
                network.send(peerName, new WireMessage.HandoffCancel(handoff.playerId()));
                unlockTransfer(handoff.playerId());
                failedTransfers.incrementAndGet();
                rejectSource(player, handoff);
                notifyUnreachable(player, "transfer-mode '" + config.transferMode + "' rejected by Bukkit (publicHost/proxy not reachable)");
                return;
            }
            completedTransfers.incrementAndGet();
            Wormholes.i("[handoff] ack RX from peer=" + peerName + " — transfer of " + player.getName() + " dispatched");
            lockTransfer(handoff.playerId(), System.currentTimeMillis() + ARRIVAL_TTL_MILLIS);
        });
        if (!scheduled) {
            network.send(peerName, new WireMessage.HandoffCancel(handoff.playerId()));
            unlockTransfer(handoff.playerId());
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
        Player player = Wormholes.instance.getServer().getPlayer(handoff.playerId());
        unlockTransfer(handoff.playerId());
        failedTransfers.incrementAndGet();
        if (player != null) {
            rejectSource(player, handoff);
            String reason = deny.reason() == null || deny.reason().isBlank() ? "destination denied" : "destination denied: " + deny.reason();
            notifyUnreachable(player, reason);
        }
    }

    public void onHandoffCancel(String peerName, WireMessage.HandoffCancel cancel) {
        PendingArrival arrival = pendingArrivals.get(cancel.playerId());
        if (arrival != null && arrival.peerName().equals(peerName)) {
            pendingArrivals.remove(cancel.playerId(), arrival);
        }
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
        if (exit == null || exit.getStructure() == null || exit.getStructure().getWorld() == null) {
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
            && portal.getProjectionMode().allowsTraversal()
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
        FoliaScheduler.runEntity(Wormholes.instance, entity, () -> {
            if (entity.isValid()) {
                entity.remove();
            }
        });
    }

    @EventHandler
    public void on(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        LocalPortal.latchReentryIfInsidePortal(player);
        unlockTransfer(player.getUniqueId());
        PendingArrival arrival = pendingArrivals.remove(player.getUniqueId());
        if (arrival == null || arrival.expiresAtMillis() < System.currentTimeMillis()) {
            Wormholes.i("[arrival] join " + player.getName() + " at " + locStr(player.getLocation()) + " — NO pending cross-server arrival (arrival=" + (arrival == null ? "null" : "expired") + "); not managed, relying on join-latch");
            return;
        }
        placeArrivingPlayer(player, arrival, "join");
    }

    private void placeArrivingPlayer(Player player, PendingArrival arrival, String via) {
        ILocalPortal exit = Wormholes.portalManager == null ? null : Wormholes.portalManager.getLocalPortal(arrival.exitPortalId());
        if (exit == null || exit.getStructure() == null || exit.getStructure().getWorld() == null) {
            Wormholes.i("[arrival] " + via + " " + player.getName() + " — pending arrival exitPortal=" + arrival.exitPortalId() + " UNRESOLVED (portal/world missing); cannot place at gateway");
            return;
        }
        LocalPortal.latchReentry(player.getUniqueId(), exit.getId());
        Traversive traversive = arrival.traversive().toTraversive(player);
        if (!exit.canArrive(player)) {
            Wormholes.i("[arrival] " + via + " " + player.getName() + " DENIED at exitPortal=" + exit.getId() + " (incoming disabled/permission)");
            exit.rejectRemoteArrival(player, traversive);
            return;
        }
        Location target = exit.computeExitTarget(traversive);
        Wormholes.i("[arrival] " + via + " " + player.getName() + " spawnLoc=" + locStr(player.getLocation()) + " exitPortal=" + exit.getId() + " -> teleport target=" + locStr(target) + " (latched to exit)");
        FoliaScheduler.runEntity(Wormholes.instance, player, () ->
            WormholesPlatform.teleport(Wormholes.instance, player, target, PlayerTeleportEvent.TeleportCause.PLUGIN).thenAccept(success -> {
                if (success) {
                    exit.completeRemoteArrival(player, traversive);
                }
            })
        );
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
        ILocalPortal source = Wormholes.portalManager == null ? null : Wormholes.portalManager.getLocalPortal(sourcePortalId);
        if (source == null) {
            return;
        }
        FoliaScheduler.runEntity(Wormholes.instance, entity, () -> {
            if (entity.isValid()) {
                source.rejectDeparture(entity, traversive);
            }
        });
    }

    private boolean isTransferLocked(UUID entityId, long now) {
        Long until = transferLocks.get(entityId);
        if (until == null) {
            return false;
        }
        if (until.longValue() <= now) {
            transferLocks.remove(entityId, until);
            return false;
        }
        return true;
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
        String text = reason == null || reason.isBlank()
            ? "Destination server unreachable"
            : "Destination server unreachable: " + reason;
        WormholesAudience.sendActionBar(player, Component.text(text, NamedTextColor.RED));
    }

    private void pruneArrivals() {
        long now = System.currentTimeMillis();
        pendingArrivals.values().removeIf(arrival -> arrival.expiresAtMillis() < now);
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
