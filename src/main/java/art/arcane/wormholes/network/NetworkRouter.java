package art.arcane.wormholes.network;

import art.arcane.wormholes.network.replication.ChunkReplicationManager;
import art.arcane.wormholes.network.replication.RemoteChunkStore;
import art.arcane.wormholes.network.view.RemoteViewCache;
import art.arcane.wormholes.network.view.ViewServer;
import art.arcane.wormholes.network.view.ViewSubscriptionManager;

import java.util.List;

public final class NetworkRouter {
    private final RemotePortalRegistry registry;
    private final PortalSyncService portalSync;
    private final TraversalService traversal;
    private final ViewServer viewServer;
    private final RemoteViewCache viewCache;
    private final ViewSubscriptionManager viewSubscriptions;
    private final ChunkReplicationManager replicationManager;
    private final NetworkManager network;

    public NetworkRouter(RemotePortalRegistry registry, PortalSyncService portalSync, TraversalService traversal, ViewServer viewServer, RemoteViewCache viewCache, ViewSubscriptionManager viewSubscriptions, ChunkReplicationManager replicationManager, NetworkManager network) {
        this.registry = registry;
        this.portalSync = portalSync;
        this.traversal = traversal;
        this.viewServer = viewServer;
        this.viewCache = viewCache;
        this.viewSubscriptions = viewSubscriptions;
        this.replicationManager = replicationManager;
        this.network = network;
    }

    public void onPeerState(String peerName, boolean ready) {
        portalSync.onPeerStateChanged(peerName, ready);
        viewSubscriptions.onPeerStateChanged(peerName, ready);
        if (!ready) {
            viewServer.onPeerDisconnected(peerName);
            if (replicationManager != null) {
                replicationManager.clearPeer(peerName);
            }
            viewCache.clearChunkStore(peerName);
        }
    }

    private void handleChunkDiff(String peerName, WireMessage.ChunkDiff diff) {
        List<RemoteChunkStore.ApplyOutcome> outcomes = viewCache.applyChunkDiff(peerName, diff.batches());
        for (RemoteChunkStore.ApplyOutcome outcome : outcomes) {
            if (outcome.resyncRequested()) {
                network.send(peerName, new WireMessage.ChunkResyncRequestMessage(new art.arcane.wormholes.network.replication.ChunkResyncRequest(outcome.chunkKey(), outcome.expectedSequenceOrLastApplied())));
            }
        }
    }

    private void handleHashProbe(String peerName, WireMessage.ChunkHashProbeMessage probe) {
        RemoteChunkStore store = viewCache.chunkStoreIfPresent(peerName);
        if (store == null) {
            return;
        }
        List<Long> mismatches = store.mismatches(probe.probe().entries());
        if (mismatches.isEmpty()) {
            return;
        }
        for (Long chunkKey : mismatches) {
            network.send(peerName, new WireMessage.ChunkResyncRequestMessage(new art.arcane.wormholes.network.replication.ChunkResyncRequest(chunkKey, 0L)));
        }
    }

    public void onMessage(String peerName, WireMessage message) {
        switch (message) {
            case WireMessage.PortalDirectory directory -> registry.applyDirectory(peerName, directory.portals());
            case WireMessage.PortalUpsert upsert -> registry.applyUpsert(peerName, upsert.portal());
            case WireMessage.PortalRemove remove -> registry.applyRemove(peerName, remove.portalId());
            case WireMessage.HandoffRequest request -> traversal.onHandoffRequest(peerName, request);
            case WireMessage.HandoffAck ack -> traversal.onHandoffAck(peerName, ack);
            case WireMessage.HandoffDeny deny -> traversal.onHandoffDeny(peerName, deny);
            case WireMessage.HandoffCancel cancel -> traversal.onHandoffCancel(peerName, cancel);
            case WireMessage.EntityTransfer transfer -> traversal.onEntityTransfer(peerName, transfer);
            case WireMessage.EntityTransferAck ack -> traversal.onEntityTransferAck(peerName, ack);
            case WireMessage.ViewSubscribe subscribe -> viewServer.onSubscribe(peerName, subscribe.portalId());
            case WireMessage.ViewUnsubscribe unsubscribe -> viewServer.onUnsubscribe(peerName, unsubscribe.portalId());
            case WireMessage.ChunkBulkBatch bulk -> viewCache.applyChunkBulk(peerName, bulk.chunks());
            case WireMessage.ChunkDiff diff -> handleChunkDiff(peerName, diff);
            case WireMessage.ChunkHashProbeMessage probe -> handleHashProbe(peerName, probe);
            case WireMessage.ChunkResyncRequestMessage resync -> viewServer.onChunkResyncRequest(peerName, resync.request());
            case WireMessage.ViewBulkComplete complete -> viewCache.markViewReady(peerName, complete.portalId());
            case WireMessage.PortalSettingsUpdate settingsUpdate -> portalSync.applySettingsUpdate(peerName, settingsUpdate);
            case WireMessage.ViewEntities entities -> viewCache.applyEntities(peerName, entities.portalId(), entities.entities(), entities.presentIds());
            case WireMessage.ViewTime time -> viewCache.applyTime(peerName, time.portalId(), time.skyDarken());
            case WireMessage.ViewEntityAnimation animation -> {
                art.arcane.wormholes.ProjectionManager projectionManager = art.arcane.wormholes.Wormholes.projectionManager;
                if (projectionManager != null) {
                    if (animation.hurt()) {
                        projectionManager.dispatchProjectedEntityHurt(animation.entityId(), animation.yaw());
                    } else {
                        com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityAnimation.EntityAnimationType[] types =
                            com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityAnimation.EntityAnimationType.values();
                        if (animation.animationOrdinal() >= 0 && animation.animationOrdinal() < types.length) {
                            projectionManager.dispatchProjectedEntityAnimation(animation.entityId(), types[animation.animationOrdinal()]);
                        }
                    }
                }
            }
            default -> {
            }
        }
    }
}
