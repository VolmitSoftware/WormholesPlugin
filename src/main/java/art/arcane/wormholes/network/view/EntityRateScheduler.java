package art.arcane.wormholes.network.view;

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
    private final double nearSquared;
    private final double midSquared;
    private final double farSquared;
    private final AtomicLong sendNear = new AtomicLong();
    private final AtomicLong sendMid = new AtomicLong();
    private final AtomicLong sendFar = new AtomicLong();
    private final AtomicLong sendVeryFar = new AtomicLong();

    public EntityRateScheduler(Bands bands) {
        this.bands = bands;
        this.nearSquared = bands.nearRange() * bands.nearRange();
        this.midSquared = bands.midRange() * bands.midRange();
        this.farSquared = bands.farRange() * bands.farRange();
    }

    public Bands getBands() {
        return bands;
    }

    public long claimSendInterval(double distanceSquared) {
        double hz = pickHz(distanceSquared);
        recordBandHit(distanceSquared);
        return intervalTicks(hz);
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
}
