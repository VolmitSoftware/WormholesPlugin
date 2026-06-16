package art.arcane.wormholes.network.view;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

public final class EntityRateScheduler {
    public record Bands(double nearRange, double midRange, double farRange,
                        double nearHz, double midHz, double farHz, double veryFarHz) {
    }

    public record Stats(long sendNear, long sendMid, long sendFar, long sendVeryFar) {
        public long total() {
            return sendNear + sendMid + sendFar + sendVeryFar;
        }
    }

    private final Bands bands;
    private final Map<Key, Long> nextEligibleTick = new ConcurrentHashMap<>();
    private final AtomicLong sendNear = new AtomicLong();
    private final AtomicLong sendMid = new AtomicLong();
    private final AtomicLong sendFar = new AtomicLong();
    private final AtomicLong sendVeryFar = new AtomicLong();

    public EntityRateScheduler(Bands bands) {
        this.bands = bands;
    }

    public Bands getBands() {
        return bands;
    }

    public boolean shouldSend(String subscriberId, UUID entityId,
                              double subscriberX, double subscriberY, double subscriberZ,
                              double entityX, double entityY, double entityZ,
                              long currentTick) {
        Key key = new Key(subscriberId, entityId);
        Long nextTick = nextEligibleTick.get(key);
        if (nextTick != null && currentTick < nextTick.longValue()) {
            return false;
        }
        double dx = entityX - subscriberX;
        double dy = entityY - subscriberY;
        double dz = entityZ - subscriberZ;
        double distSquared = (dx * dx) + (dy * dy) + (dz * dz);
        double hz = pickHz(distSquared);
        long interval = intervalTicks(hz);
        nextEligibleTick.put(key, currentTick + interval);
        recordBandHit(distSquared);
        return true;
    }

    public long intervalTicksFor(double subscriberX, double subscriberY, double subscriberZ,
                                 double entityX, double entityY, double entityZ) {
        double dx = entityX - subscriberX;
        double dy = entityY - subscriberY;
        double dz = entityZ - subscriberZ;
        double distSquared = (dx * dx) + (dy * dy) + (dz * dz);
        return intervalTicks(pickHz(distSquared));
    }

    public void clearEntity(String subscriberId, UUID entityId) {
        nextEligibleTick.remove(new Key(subscriberId, entityId));
    }

    public void clearSubscriber(String subscriberId) {
        nextEligibleTick.keySet().removeIf(key -> key.subscriberId().equals(subscriberId));
    }

    public void clearAll() {
        nextEligibleTick.clear();
    }

    public int trackedEntityCount() {
        return nextEligibleTick.size();
    }

    public Stats snapshot() {
        return new Stats(sendNear.get(), sendMid.get(), sendFar.get(), sendVeryFar.get());
    }

    public void resetStats() {
        sendNear.set(0L);
        sendMid.set(0L);
        sendFar.set(0L);
        sendVeryFar.set(0L);
    }

    private void recordBandHit(double distanceSquared) {
        double nearSquared = bands.nearRange() * bands.nearRange();
        double midSquared = bands.midRange() * bands.midRange();
        double farSquared = bands.farRange() * bands.farRange();
        if (distanceSquared <= nearSquared) {
            sendNear.incrementAndGet();
            return;
        }
        if (distanceSquared <= midSquared) {
            sendMid.incrementAndGet();
            return;
        }
        if (distanceSquared <= farSquared) {
            sendFar.incrementAndGet();
            return;
        }
        sendVeryFar.incrementAndGet();
    }

    private double pickHz(double distanceSquared) {
        double nearSquared = bands.nearRange() * bands.nearRange();
        double midSquared = bands.midRange() * bands.midRange();
        double farSquared = bands.farRange() * bands.farRange();
        if (distanceSquared <= nearSquared) {
            return bands.nearHz();
        }
        if (distanceSquared <= midSquared) {
            return bands.midHz();
        }
        if (distanceSquared <= farSquared) {
            return bands.farHz();
        }
        return bands.veryFarHz();
    }

    private static long intervalTicks(double hz) {
        if (hz <= 0.0D) {
            return Long.MAX_VALUE / 2L;
        }
        double ticks = 20.0D / hz;
        long rounded = Math.round(ticks);
        return Math.max(1L, rounded);
    }

    private record Key(String subscriberId, UUID entityId) {
    }
}
