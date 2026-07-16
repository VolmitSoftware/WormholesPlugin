package art.arcane.wormholes.service;

import java.util.concurrent.atomic.AtomicLong;

public final class WormholesTelemetry {
    private static final long RATE_WINDOW_MS = 1000L;
    private static final AtomicLong BLOCK_CHANGES = new AtomicLong();
    private static final AtomicLong PACKETS = new AtomicLong();
    private static final AtomicLong TRAVERSALS = new AtomicLong();
    private static final AtomicLong RENDER_NANOS = new AtomicLong();
    private static final Object RATE_LOCK = new Object();
    private static volatile int activeProjections;
    private static volatile int projectionObservers;
    private static volatile int spoofedEntities;
    private static long windowStartMs;
    private static long windowBlockChanges;
    private static long windowPackets;
    private static long windowTraversals;
    private static long windowRenderNanos;
    private static volatile double blockChangesPerSecond;
    private static volatile double packetsPerSecond;
    private static volatile double traversalsPerMinute;
    private static volatile double renderMsPerSecond;

    private WormholesTelemetry() {
    }

    public static void countBlockChange() {
        BLOCK_CHANGES.incrementAndGet();
    }

    public static void countPacket() {
        PACKETS.incrementAndGet();
    }

    public static void countTraversal() {
        TRAVERSALS.incrementAndGet();
    }

    public static void addRenderNanos(long nanos) {
        if (nanos > 0L) {
            RENDER_NANOS.addAndGet(nanos);
        }
    }

    public static void setProjectionGauges(int active, int observers, int spoofed) {
        activeProjections = active;
        projectionObservers = observers;
        spoofedEntities = spoofed;
    }

    public static int activeProjections() {
        return activeProjections;
    }

    public static int projectionObservers() {
        return projectionObservers;
    }

    public static int spoofedEntities() {
        return spoofedEntities;
    }

    public static double blockChangesPerSecond(long now) {
        refreshRates(now);
        return blockChangesPerSecond;
    }

    public static double packetsPerSecond(long now) {
        refreshRates(now);
        return packetsPerSecond;
    }

    public static double traversalsPerMinute(long now) {
        refreshRates(now);
        return traversalsPerMinute;
    }

    public static double renderMsPerSecond(long now) {
        refreshRates(now);
        return renderMsPerSecond;
    }

    public static void clear() {
        synchronized (RATE_LOCK) {
            BLOCK_CHANGES.set(0L);
            PACKETS.set(0L);
            TRAVERSALS.set(0L);
            RENDER_NANOS.set(0L);
            windowStartMs = 0L;
            windowBlockChanges = 0L;
            windowPackets = 0L;
            windowTraversals = 0L;
            windowRenderNanos = 0L;
            blockChangesPerSecond = 0D;
            packetsPerSecond = 0D;
            traversalsPerMinute = 0D;
            renderMsPerSecond = 0D;
        }
        setProjectionGauges(0, 0, 0);
    }

    private static void refreshRates(long now) {
        synchronized (RATE_LOCK) {
            if (windowStartMs == 0L) {
                windowStartMs = now;
                windowBlockChanges = BLOCK_CHANGES.get();
                windowPackets = PACKETS.get();
                windowTraversals = TRAVERSALS.get();
                windowRenderNanos = RENDER_NANOS.get();
                return;
            }

            long elapsed = now - windowStartMs;
            if (elapsed < RATE_WINDOW_MS) {
                return;
            }

            long blockChanges = BLOCK_CHANGES.get();
            long packets = PACKETS.get();
            long traversals = TRAVERSALS.get();
            long renderNanos = RENDER_NANOS.get();
            double seconds = elapsed / 1000D;

            blockChangesPerSecond = (blockChanges - windowBlockChanges) / seconds;
            packetsPerSecond = (packets - windowPackets) / seconds;
            traversalsPerMinute = ((traversals - windowTraversals) / seconds) * 60D;
            renderMsPerSecond = ((renderNanos - windowRenderNanos) / 1.0E6D) / seconds;

            windowStartMs = now;
            windowBlockChanges = blockChanges;
            windowPackets = packets;
            windowTraversals = traversals;
            windowRenderNanos = renderNanos;
        }
    }
}
