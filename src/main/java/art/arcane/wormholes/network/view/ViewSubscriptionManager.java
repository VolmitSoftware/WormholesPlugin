package art.arcane.wormholes.network.view;

import art.arcane.wormholes.network.NetworkManager;
import art.arcane.wormholes.network.WireMessage;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class ViewSubscriptionManager {
    private record Key(String peerName, UUID portalId) {
    }

    private static final long RESUBSCRIBE_INTERVAL_MILLIS = 5_000L;

    private final NetworkManager network;
    private final RemoteViewCache cache;
    private final Map<Key, Long> lastTouchMillis = new ConcurrentHashMap<>();
    private final Map<Key, Long> lastSubscribeMillis = new ConcurrentHashMap<>();
    private final Map<Key, Long> unsubscribeGraceMillis = new ConcurrentHashMap<>();
    private final Map<Key, Boolean> subscribed = new ConcurrentHashMap<>();

    public ViewSubscriptionManager(NetworkManager network, RemoteViewCache cache) {
        this.network = network;
        this.cache = cache;
    }

    public RemoteViewCache.RemoteView touch(String peerName, UUID portalId, int graceSeconds) {
        Key key = new Key(peerName, portalId);
        long now = System.currentTimeMillis();
        lastTouchMillis.put(key, now);
        unsubscribeGraceMillis.put(key, Long.valueOf(Math.max(5, graceSeconds) * 1000L));
        RemoteViewCache.RemoteView view = cache.getOrCreate(peerName, portalId);
        if (subscribed.putIfAbsent(key, Boolean.TRUE) == null) {
            lastSubscribeMillis.put(key, now);
            network.send(peerName, new WireMessage.ViewSubscribe(portalId));
            return view;
        }
        if (!view.hasData() && now - lastSubscribeMillis.getOrDefault(key, 0L) >= RESUBSCRIBE_INTERVAL_MILLIS) {
            lastSubscribeMillis.put(key, now);
            network.send(peerName, new WireMessage.ViewSubscribe(portalId));
        }
        return view;
    }

    public void sweep() {
        long now = System.currentTimeMillis();
        for (Map.Entry<Key, Long> entry : lastTouchMillis.entrySet()) {
            Key key = entry.getKey();
            long graceMillis = unsubscribeGraceMillis.getOrDefault(key, Long.valueOf(30_000L)).longValue();
            if (now - entry.getValue() < graceMillis) {
                continue;
            }
            lastTouchMillis.remove(key, entry.getValue());
            lastSubscribeMillis.remove(key);
            unsubscribeGraceMillis.remove(key);
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
