package art.arcane.wormholes.network;

import art.arcane.volmlib.util.scheduling.FoliaScheduler;
import art.arcane.wormholes.Wormholes;
import art.arcane.wormholes.config.toml.NetworkConfig;
import art.arcane.wormholes.portal.ILocalPortal;
import art.arcane.wormholes.portal.Traversive;
import art.arcane.wormholes.portal.UniversalTunnel;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntitySnapshot;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerTeleportEvent;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class TraversalService implements Listener {
    private record PendingHandoff(UUID playerId, String peerName, long deadlineMillis) {
    }

    private record PendingArrival(UUID exitPortalId, WireTraversive traversive, long expiresAtMillis) {
    }

    private record PendingEntityTransfer(Entity entity, String peerName, long deadlineMillis) {
    }

    private static final long ARRIVAL_TTL_MILLIS = 15_000L;
    private static final long ENTITY_DEDUPE_TTL_MILLIS = 60_000L;
    private static final long TRANSFER_FAILED_CHECK_TICKS = 100L;

    private final NetworkManager network;
    private final Map<UUID, PendingHandoff> pendingHandoffs = new ConcurrentHashMap<>();
    private final Map<UUID, PendingArrival> pendingArrivals = new ConcurrentHashMap<>();
    private final Map<UUID, PendingEntityTransfer> pendingEntityTransfers = new ConcurrentHashMap<>();
    private final Map<UUID, Long> appliedEntityTransfers = new ConcurrentHashMap<>();

    public TraversalService(NetworkManager network) {
        this.network = network;
    }

    public void beginPlayerHandoff(Player player, UniversalTunnel tunnel, Traversive traversive) {
        String peerName = tunnel.getServerName();
        NetworkConfig config = Wormholes.settings.getNetwork();
        NetworkConfig.PeerEntry peer = network.getPeer(peerName);
        if (peer == null || !network.isPeerReady(peerName)) {
            notifyUnreachable(player);
            return;
        }

        UUID transferId = UUID.randomUUID();
        long deadline = System.currentTimeMillis() + config.handoffTimeoutMs;
        pendingHandoffs.put(transferId, new PendingHandoff(player.getUniqueId(), peerName, deadline));
        network.send(peerName, new WireMessage.HandoffRequest(transferId, player.getUniqueId(), player.getName(), tunnel.getDestinationPortalId(), WireTraversive.fromTraversive(traversive)));

        long timeoutTicks = Math.max(1L, config.handoffTimeoutMs / 50L);
        FoliaScheduler.runEntity(Wormholes.instance, player, () -> {
            if (pendingHandoffs.remove(transferId) != null && player.isOnline()) {
                notifyUnreachable(player);
            }
        }, timeoutTicks);
    }

    public void beginEntityTransfer(Entity entity, UniversalTunnel tunnel, Traversive traversive) {
        String peerName = tunnel.getServerName();
        if (network.getPeer(peerName) == null || !network.isPeerReady(peerName)) {
            return;
        }

        EntitySnapshot snapshot = entity.createSnapshot();
        if (snapshot == null) {
            return;
        }
        byte[] data = snapshot.getAsString().getBytes(StandardCharsets.UTF_8);
        if (data.length > WireMessage.EntityTransfer.MAX_SNAPSHOT_BYTES) {
            Wormholes.w("net: entity " + entity.getType() + " snapshot too large to transfer (" + data.length + " bytes)");
            return;
        }

        UUID transferId = UUID.randomUUID();
        NetworkConfig config = Wormholes.settings.getNetwork();
        pendingEntityTransfers.put(transferId, new PendingEntityTransfer(entity, peerName, System.currentTimeMillis() + config.handoffTimeoutMs));
        network.send(peerName, new WireMessage.EntityTransfer(transferId, tunnel.getDestinationPortalId(), data, WireTraversive.fromTraversive(traversive)));
        prunePendingEntityTransfers();
    }

    public void onHandoffRequest(String peerName, WireMessage.HandoffRequest request) {
        ILocalPortal exit = Wormholes.portalManager == null ? null : Wormholes.portalManager.getLocalPortal(request.destPortalId());
        if (exit == null) {
            network.send(peerName, new WireMessage.HandoffDeny(request.transferId(), "unknown portal"));
            return;
        }
        pruneArrivals();
        pendingArrivals.put(request.playerId(), new PendingArrival(exit.getId(), request.traversive(), System.currentTimeMillis() + ARRIVAL_TTL_MILLIS));
        network.send(peerName, new WireMessage.HandoffAck(request.transferId()));
    }

    public void onHandoffAck(String peerName, WireMessage.HandoffAck ack) {
        PendingHandoff handoff = pendingHandoffs.remove(ack.transferId());
        if (handoff == null || !handoff.peerName().equals(peerName)) {
            return;
        }
        Player player = Wormholes.instance.getServer().getPlayer(handoff.playerId());
        if (player == null) {
            network.send(peerName, new WireMessage.HandoffCancel(handoff.playerId()));
            return;
        }
        NetworkConfig config = Wormholes.settings.getNetwork();
        NetworkConfig.PeerEntry peer = network.getPeer(peerName);
        if (peer == null) {
            return;
        }
        FoliaScheduler.runEntity(Wormholes.instance, player, () -> {
            if (!player.isOnline()) {
                network.send(peerName, new WireMessage.HandoffCancel(handoff.playerId()));
                return;
            }
            if (!PlayerTransfer.send(player, peer, config.transferMode)) {
                network.send(peerName, new WireMessage.HandoffCancel(handoff.playerId()));
                notifyUnreachable(player);
                return;
            }
            FoliaScheduler.runEntity(Wormholes.instance, player, () -> {
                if (player.isOnline()) {
                    network.send(peerName, new WireMessage.HandoffCancel(handoff.playerId()));
                    Wormholes.sendActionBar(player, Component.text("Transfer failed - is the destination reachable?", NamedTextColor.RED));
                }
            }, TRANSFER_FAILED_CHECK_TICKS);
        });
    }

    public void onHandoffDeny(String peerName, WireMessage.HandoffDeny deny) {
        PendingHandoff handoff = pendingHandoffs.remove(deny.transferId());
        if (handoff == null) {
            return;
        }
        Player player = Wormholes.instance.getServer().getPlayer(handoff.playerId());
        if (player != null) {
            notifyUnreachable(player);
        }
    }

    public void onHandoffCancel(String peerName, WireMessage.HandoffCancel cancel) {
        pendingArrivals.remove(cancel.playerId());
    }

    public void onEntityTransfer(String peerName, WireMessage.EntityTransfer transfer) {
        if (appliedEntityTransfers.containsKey(transfer.transferId())) {
            network.send(peerName, new WireMessage.EntityTransferAck(transfer.transferId(), true));
            return;
        }

        ILocalPortal exit = Wormholes.portalManager == null ? null : Wormholes.portalManager.getLocalPortal(transfer.destPortalId());
        if (exit == null || exit.getStructure() == null || exit.getStructure().getWorld() == null) {
            network.send(peerName, new WireMessage.EntityTransferAck(transfer.transferId(), false));
            return;
        }

        Traversive traversive = transfer.traversive().toTraversive(null);
        Location target = exit.computeExitTarget(traversive);
        FoliaScheduler.runRegion(Wormholes.instance, target, () -> {
            boolean accepted = false;
            try {
                EntitySnapshot snapshot = Wormholes.instance.getServer().getEntityFactory().createEntitySnapshot(new String(transfer.entitySnapshot(), StandardCharsets.UTF_8));
                if (!isEntityTypeDenied(snapshot)) {
                    Entity entity = snapshot.createEntity(target);
                    exit.completeRemoteArrival(entity, traversive);
                    accepted = true;
                }
            } catch (Throwable e) {
                Wormholes.w("net: failed to apply entity transfer from " + peerName + ": " + e.getMessage());
            }
            if (accepted) {
                pruneAppliedEntityTransfers();
                appliedEntityTransfers.put(transfer.transferId(), System.currentTimeMillis());
            }
            network.send(peerName, new WireMessage.EntityTransferAck(transfer.transferId(), accepted));
        });
    }

    public void onEntityTransferAck(String peerName, WireMessage.EntityTransferAck ack) {
        PendingEntityTransfer pending = pendingEntityTransfers.remove(ack.transferId());
        if (pending == null || !ack.accepted()) {
            return;
        }
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
        PendingArrival arrival = pendingArrivals.remove(player.getUniqueId());
        if (arrival == null || arrival.expiresAtMillis() < System.currentTimeMillis()) {
            return;
        }
        ILocalPortal exit = Wormholes.portalManager == null ? null : Wormholes.portalManager.getLocalPortal(arrival.exitPortalId());
        if (exit == null || exit.getStructure() == null || exit.getStructure().getWorld() == null) {
            return;
        }
        Traversive traversive = arrival.traversive().toTraversive(player);
        Location target = exit.computeExitTarget(traversive);
        FoliaScheduler.runEntity(Wormholes.instance, player, () ->
            player.teleportAsync(target, PlayerTeleportEvent.TeleportCause.PLUGIN).thenAccept(success -> {
                if (success) {
                    exit.completeRemoteArrival(player, traversive);
                }
            })
        );
    }

    private static boolean isEntityTypeDenied(EntitySnapshot snapshot) {
        NetworkConfig config = Wormholes.settings.getNetwork();
        if (config.entityTransferDenyTypes == null || config.entityTransferDenyTypes.isBlank()) {
            return false;
        }
        Set<String> denied = new HashSet<>(Arrays.asList(config.entityTransferDenyTypes.toUpperCase(Locale.ROOT).split("\\s*,\\s*")));
        return denied.contains(snapshot.getEntityType().name());
    }

    private void notifyUnreachable(Player player) {
        Wormholes.sendActionBar(player, Component.text("Destination server unreachable", NamedTextColor.RED));
    }

    private void pruneArrivals() {
        long now = System.currentTimeMillis();
        pendingArrivals.values().removeIf(arrival -> arrival.expiresAtMillis() < now);
    }

    private void prunePendingEntityTransfers() {
        long now = System.currentTimeMillis();
        pendingEntityTransfers.values().removeIf(pending -> pending.deadlineMillis() < now);
    }

    private void pruneAppliedEntityTransfers() {
        if (appliedEntityTransfers.size() < 256) {
            return;
        }
        long now = System.currentTimeMillis();
        Iterator<Map.Entry<UUID, Long>> iterator = appliedEntityTransfers.entrySet().iterator();
        while (iterator.hasNext()) {
            if (iterator.next().getValue() + ENTITY_DEDUPE_TTL_MILLIS < now) {
                iterator.remove();
            }
        }
    }
}
