package art.arcane.wormholes.network;

import art.arcane.wormholes.portal.ILocalPortal;
import art.arcane.wormholes.portal.PortalFrame;
import art.arcane.wormholes.util.AxisAlignedBB;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Supplier;

public final class PortalSyncService {
    private final NetworkManager network;
    private final Supplier<List<ILocalPortal>> portalSource;
    private final Consumer<Runnable> globalDispatcher;

    public PortalSyncService(NetworkManager network, Supplier<List<ILocalPortal>> portalSource, Consumer<Runnable> globalDispatcher) {
        this.network = network;
        this.portalSource = portalSource;
        this.globalDispatcher = globalDispatcher;
    }

    public void onPeerStateChanged(String peerName, boolean ready) {
        if (!ready) {
            return;
        }
        globalDispatcher.accept(() -> sendDirectory(peerName));
    }

    public void sendDirectory(String peerName) {
        List<PortalInfo> shared = new ArrayList<>();
        for (ILocalPortal portal : portalSource.get()) {
            if (isShareable(portal)) {
                shared.add(toInfo(portal));
            }
        }
        network.send(peerName, new WireMessage.PortalDirectory(shared));
    }

    public void broadcastPortal(ILocalPortal portal) {
        if (!network.isRunning()) {
            return;
        }
        if (isShareable(portal)) {
            WireMessage.PortalUpsert upsert = new WireMessage.PortalUpsert(toInfo(portal));
            for (NetworkManager.PeerStatus peer : network.status()) {
                network.send(peer.name(), upsert);
            }
        } else {
            broadcastRemove(portal.getId());
        }
    }

    public void broadcastRemove(UUID portalId) {
        if (!network.isRunning()) {
            return;
        }
        WireMessage.PortalRemove remove = new WireMessage.PortalRemove(portalId);
        for (NetworkManager.PeerStatus peer : network.status()) {
            network.send(peer.name(), remove);
        }
    }

    private static boolean isShareable(ILocalPortal portal) {
        return portal.isGateway()
            && portal.getStructure() != null
            && portal.getStructure().getWorld() != null
            && portal.getStructure().getArea() != null;
    }

    public static PortalInfo toInfo(ILocalPortal portal) {
        PortalFrame frame = portal.getFrame();
        AxisAlignedBB area = portal.getStructure().getArea();
        return new PortalInfo(
            portal.getId(),
            portal.getName(),
            portal.getStructure().getWorld().getName(),
            portal.getType().name(),
            portal.isOpen(),
            frame.getNormal().name(),
            frame.getRight().name(),
            frame.getUp().name(),
            portal.getOrigin().getX(),
            portal.getOrigin().getY(),
            portal.getOrigin().getZ(),
            Math.min(area.getXa(), area.getXb()),
            Math.min(area.getYa(), area.getYb()),
            Math.min(area.getZa(), area.getZb()),
            Math.max(area.getXa(), area.getXb()),
            Math.max(area.getYa(), area.getYb()),
            Math.max(area.getZa(), area.getZb())
        );
    }
}
