package art.arcane.wormholes.network.view;

import art.arcane.wormholes.Wormholes;
import art.arcane.wormholes.network.NetworkManager;
import art.arcane.wormholes.network.WireMessage;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class ViewSubscriptionManager {
    private record Key(String peerName, UUID portalId) {
    }

    private final NetworkManager network;
    private final RemoteViewCache cache;
    private final Map<Key, Long> lastTouchMillis = new ConcurrentHashMap<>();
    private final Map<Key, Boolean> subscribed = new ConcurrentHashMap<>();

    public ViewSubscriptionManager(NetworkManager network, RemoteViewCache cache) {
        this.network = network;
        this.cache = cache;
    }

    public RemoteViewCache.RemoteView touch(String peerName, UUID portalId) {
        Key key = new Key(peerName, portalId);
        lastTouchMillis.put(key, System.currentTimeMillis());
        if (subscribed.putIfAbsent(key, Boolean.TRUE) == null) {
            network.send(peerName, new WireMessage.ViewSubscribe(portalId));
        }
        return cache.getOrCreate(peerName, portalId);
    }

    public void sweep() {
        long graceMillis = Math.max(5, Wormholes.settings.getNetwork().viewUnsubscribeGraceSeconds) * 1000L;
        long now = System.currentTimeMillis();
        for (Map.Entry<Key, Long> entry : lastTouchMillis.entrySet()) {
            if (now - entry.getValue() < graceMillis) {
                continue;
            }
            Key key = entry.getKey();
            lastTouchMillis.remove(key, entry.getValue());
            if (subscribed.remove(key) != null) {
                network.send(key.peerName(), new WireMessage.ViewUnsubscribe(key.portalId()));
                cache.remove(key.peerName(), key.portalId());
            }
        }
    }

    public void onPeerStateChanged(String peerName, boolean ready) {
        if (ready) {
            return;
        }
        for (Key key : subscribed.keySet()) {
            if (key.peerName().equals(peerName)) {
                subscribed.remove(key);
            }
        }
    }
}
