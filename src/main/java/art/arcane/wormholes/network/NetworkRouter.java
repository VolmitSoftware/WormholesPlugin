package art.arcane.wormholes.network;

import art.arcane.wormholes.network.view.RemoteViewCache;
import art.arcane.wormholes.network.view.ViewServer;
import art.arcane.wormholes.network.view.ViewSubscriptionManager;

public final class NetworkRouter {
    private final RemotePortalRegistry registry;
    private final PortalSyncService portalSync;
    private final TraversalService traversal;
    private final ViewServer viewServer;
    private final RemoteViewCache viewCache;
    private final ViewSubscriptionManager viewSubscriptions;

    public NetworkRouter(RemotePortalRegistry registry, PortalSyncService portalSync, TraversalService traversal, ViewServer viewServer, RemoteViewCache viewCache, ViewSubscriptionManager viewSubscriptions) {
        this.registry = registry;
        this.portalSync = portalSync;
        this.traversal = traversal;
        this.viewServer = viewServer;
        this.viewCache = viewCache;
        this.viewSubscriptions = viewSubscriptions;
    }

    public void onPeerState(String peerName, boolean ready) {
        portalSync.onPeerStateChanged(peerName, ready);
        viewSubscriptions.onPeerStateChanged(peerName, ready);
        if (!ready) {
            viewServer.onPeerDisconnected(peerName);
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
            case WireMessage.ViewSnapshot snapshot -> viewCache.applySnapshot(peerName, snapshot.portalId(), snapshot.box(), snapshot.slices());
            case WireMessage.ViewDelta delta -> viewCache.applyDelta(peerName, delta.portalId(), delta.slices());
            case WireMessage.ViewEntities entities -> viewCache.applyEntities(peerName, entities.portalId(), entities.entities());
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
