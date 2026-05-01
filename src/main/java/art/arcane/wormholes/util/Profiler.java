package art.arcane.wormholes.util;

public final class Profiler {
    private long startNanos;
    private long endNanos;

    public Profiler() {
        startNanos = 0L;
        endNanos = 0L;
    }

    public void begin() {
        startNanos = System.nanoTime();
        endNanos = startNanos;
    }

    public void end() {
        endNanos = System.nanoTime();
    }

    public double getMilliseconds() {
        return (endNanos - startNanos) / 1_000_000.0D;
    }

    public double getSeconds() {
        return (endNanos - startNanos) / 1_000_000_000.0D;
    }
}
