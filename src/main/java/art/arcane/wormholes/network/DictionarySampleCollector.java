package art.arcane.wormholes.network;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

public final class DictionarySampleCollector {
    private static final int MIN_SAMPLE_BYTES = 32;
    private static final int MAX_SAMPLE_BYTES = 32 * 1024;

    private final int budgetBytes;
    private final Deque<byte[]> samples = new ArrayDeque<>();
    private long accumulatedBytes;
    private volatile boolean full;

    public DictionarySampleCollector(int budgetBytes) {
        if (budgetBytes <= 0) {
            throw new IllegalArgumentException("budgetBytes must be positive, got " + budgetBytes);
        }
        this.budgetBytes = budgetBytes;
    }

    public boolean record(byte[] payload) {
        if (payload == null) {
            return false;
        }
        if (payload.length < MIN_SAMPLE_BYTES || payload.length > MAX_SAMPLE_BYTES) {
            return false;
        }
        byte[] copy = payload.clone();
        synchronized (this) {
            samples.addLast(copy);
            accumulatedBytes += copy.length;
            while (accumulatedBytes > budgetBytes && samples.size() > 1) {
                byte[] removed = samples.pollFirst();
                if (removed == null) {
                    break;
                }
                accumulatedBytes -= removed.length;
            }
            full = accumulatedBytes >= budgetBytes;
            return full;
        }
    }

    public boolean isFull() {
        return full;
    }

    public synchronized long accumulatedBytes() {
        return accumulatedBytes;
    }

    public synchronized int sampleCount() {
        return samples.size();
    }

    public synchronized List<byte[]> snapshot() {
        return new ArrayList<>(samples);
    }

    public synchronized void reset() {
        samples.clear();
        accumulatedBytes = 0L;
        full = false;
    }

    public int budgetBytes() {
        return budgetBytes;
    }
}
