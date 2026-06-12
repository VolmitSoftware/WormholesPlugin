package art.arcane.wormholes.network;

import art.arcane.wormholes.portal.RemotePortal;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class RemotePortalRegistry {
    private final Map<String, Map<UUID, RemotePortal>> byPeer = new ConcurrentHashMap<>();

    public void applyDirectory(String peerName, List<PortalInfo> portals) {
        Map<UUID, RemotePortal> fresh = new ConcurrentHashMap<>();
        for (PortalInfo info : portals) {
            fresh.put(info.id(), RemotePortal.fromInfo(peerName, info));
        }
        byPeer.put(peerName, fresh);
    }

    public void applyUpsert(String peerName, PortalInfo info) {
        byPeer.computeIfAbsent(peerName, key -> new ConcurrentHashMap<>()).put(info.id(), RemotePortal.fromInfo(peerName, info));
    }

    public void applyRemove(String peerName, UUID portalId) {
        Map<UUID, RemotePortal> portals = byPeer.get(peerName);
        if (portals != null) {
            portals.remove(portalId);
        }
    }

    public RemotePortal get(String peerName, UUID portalId) {
        Map<UUID, RemotePortal> portals = byPeer.get(peerName);
        return portals == null ? null : portals.get(portalId);
    }

    public boolean hasPeer(String peerName) {
        return byPeer.containsKey(peerName);
    }

    public List<RemotePortal> all() {
        List<RemotePortal> result = new ArrayList<>();
        for (Map<UUID, RemotePortal> portals : byPeer.values()) {
            result.addAll(portals.values());
        }
        return result;
    }

    public void clear() {
        byPeer.clear();
    }
}
