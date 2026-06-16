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

    public DictionarySampleCollector(int budgetBytes) {
        if (budgetBytes <= 0) {
            throw new IllegalArgumentException("budgetBytes must be positive, got " + budgetBytes);
        }
        this.budgetBytes = budgetBytes;
    }

    public synchronized boolean record(byte[] payload) {
        if (payload == null) {
            return false;
        }
        if (payload.length < MIN_SAMPLE_BYTES || payload.length > MAX_SAMPLE_BYTES) {
            return false;
        }
        byte[] copy = payload.clone();
        samples.addLast(copy);
        accumulatedBytes += copy.length;
        while (accumulatedBytes > budgetBytes && samples.size() > 1) {
            byte[] removed = samples.pollFirst();
            if (removed == null) {
                break;
            }
            accumulatedBytes -= removed.length;
        }
        return accumulatedBytes >= budgetBytes;
    }

    public synchronized boolean isFull() {
        return accumulatedBytes >= budgetBytes;
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
    }

    public int budgetBytes() {
        return budgetBytes;
    }
}
