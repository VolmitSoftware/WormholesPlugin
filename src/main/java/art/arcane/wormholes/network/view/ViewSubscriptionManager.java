package art.arcane.wormholes.network.view;

import art.arcane.wormholes.network.NetworkManager;
import art.arcane.wormholes.network.WireMessage;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class ViewSubscriptionManager {
    public record SubscriberPose(double x, double y, double z, double forwardX, double forwardY, double forwardZ) {
    }

    public record ConeBias(boolean enabled, double degrees, double behindFactor) {
        public static ConeBias disabled() {
            return new ConeBias(false, 60.0D, 1.0D);
        }
    }

    public record YBias(boolean enabled, int caveYMax, int skyYMin, double factor) {
        public static YBias disabled() {
            return new YBias(false, 50, 200, 1.0D);
        }
    }

    public record ScoredSlice(ViewSlice slice, double priority) {
    }

    private record Key(String peerName, UUID portalId) {
    }

    private static final long RESUBSCRIBE_INTERVAL_MILLIS = 5_000L;

    private final NetworkManager network;
    private final RemoteViewCache cache;
    private final Map<Key, Long> lastTouchMillis = new ConcurrentHashMap<>();
    private final Map<Key, Long> lastSubscribeMillis = new ConcurrentHashMap<>();
    private final Map<Key, Long> unsubscribeGraceMillis = new ConcurrentHashMap<>();
    private final Map<Key, Boolean> subscribed = new ConcurrentHashMap<>();
    private final Map<String, SubscriberPose> subscriberPoses = new ConcurrentHashMap<>();

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

    public void recordSubscriberPose(String peerName, SubscriberPose pose) {
        if (pose == null) {
            subscriberPoses.remove(peerName);
            return;
        }
        subscriberPoses.put(peerName, pose);
    }

    public SubscriberPose getSubscriberPose(String peerName) {
        return subscriberPoses.get(peerName);
    }

    public List<ScoredSlice> orderSlicesForSubscriber(String peerName, List<ViewSlice> slices, ConeBias coneBias, YBias yBias) {
        List<ScoredSlice> scored = new ArrayList<>(slices.size());
        SubscriberPose pose = subscriberPoses.get(peerName);
        for (ViewSlice slice : slices) {
            double basePriority = 1.0D;
            double priority = computePriority(slice, basePriority, pose, coneBias, yBias);
            scored.add(new ScoredSlice(slice, priority));
        }
        scored.sort(Comparator.comparingDouble(entry -> -entry.priority()));
        return scored;
    }

    public double scoreSlice(String peerName, ViewSlice slice, double basePriority, ConeBias coneBias, YBias yBias) {
        SubscriberPose pose = subscriberPoses.get(peerName);
        return computePriority(slice, basePriority, pose, coneBias, yBias);
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
        subscriberPoses.remove(peerName);
    }

    private static double computePriority(ViewSlice slice, double basePriority, SubscriberPose pose, ConeBias coneBias, YBias yBias) {
        double priority = basePriority;
        double sliceCenterX = slice.minX() + slice.sizeX() * 0.5D;
        double sliceCenterY = slice.minY() + slice.sizeY() * 0.5D;
        double sliceCenterZ = slice.minZ() + slice.sizeZ() * 0.5D;
        if (pose != null && coneBias != null && coneBias.enabled()) {
            double coneFactor = ViewBox.conePriorityFactor(
                sliceCenterX, sliceCenterY, sliceCenterZ,
                pose.x(), pose.y(), pose.z(),
                pose.forwardX(), pose.forwardY(), pose.forwardZ(),
                coneBias.degrees(), coneBias.behindFactor()
            );
            priority *= coneFactor;
        }
        if (pose != null && yBias != null && yBias.enabled()) {
            double yFactor = ViewBox.yBiasFactor(pose.y(), sliceCenterY, yBias.caveYMax(), yBias.skyYMin(), yBias.factor());
            priority *= yFactor;
        }
        return priority;
    }
}
