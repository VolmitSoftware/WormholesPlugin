package art.arcane.wormholes.network;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DictionarySampleCollectorTest {
    private static byte[] filledArray(int size, byte value) {
        byte[] data = new byte[size];
        for (int i = 0; i < size; i++) {
            data[i] = value;
        }
        return data;
    }

    @Test
    void recordReturnsFalseUntilBudgetReached() {
        DictionarySampleCollector collector = new DictionarySampleCollector(1024);
        assertFalse(collector.record(filledArray(256, (byte) 1)));
        assertFalse(collector.record(filledArray(256, (byte) 2)));
        assertFalse(collector.record(filledArray(256, (byte) 3)));
        assertFalse(collector.isFull());
        assertTrue(collector.record(filledArray(256, (byte) 4)));
        assertTrue(collector.isFull());
    }

    @Test
    void evictionIsFifoWhenOverflowing() {
        DictionarySampleCollector collector = new DictionarySampleCollector(512);
        collector.record(filledArray(256, (byte) 1));
        collector.record(filledArray(256, (byte) 2));
        collector.record(filledArray(256, (byte) 3));
        List<byte[]> snapshot = collector.snapshot();
        assertEquals(2, snapshot.size());
        assertEquals((byte) 2, snapshot.get(0)[0]);
        assertEquals((byte) 3, snapshot.get(1)[0]);
    }

    @Test
    void snapshotReturnsAtMostBudgetWorthOfBytes() {
        DictionarySampleCollector collector = new DictionarySampleCollector(2048);
        for (int i = 0; i < 32; i++) {
            collector.record(filledArray(256, (byte) i));
        }
        long total = 0L;
        for (byte[] sample : collector.snapshot()) {
            total += sample.length;
        }
        assertTrue(total <= collector.budgetBytes() + 256);
    }

    @Test
    void undersizedSamplesAreRejected() {
        DictionarySampleCollector collector = new DictionarySampleCollector(1024);
        assertFalse(collector.record(filledArray(16, (byte) 1)));
        assertEquals(0, collector.sampleCount());
    }

    @Test
    void oversizedSamplesAreRejected() {
        DictionarySampleCollector collector = new DictionarySampleCollector(1024 * 1024);
        assertFalse(collector.record(filledArray(64 * 1024, (byte) 1)));
        assertEquals(0, collector.sampleCount());
    }

    @Test
    void nullPayloadIsRejected() {
        DictionarySampleCollector collector = new DictionarySampleCollector(1024);
        assertFalse(collector.record(null));
        assertEquals(0, collector.sampleCount());
    }

    @Test
    void resetClearsAccumulatedState() {
        DictionarySampleCollector collector = new DictionarySampleCollector(1024);
        collector.record(filledArray(512, (byte) 1));
        collector.record(filledArray(512, (byte) 2));
        assertTrue(collector.isFull());
        collector.reset();
        assertFalse(collector.isFull());
        assertEquals(0, collector.sampleCount());
        assertEquals(0L, collector.accumulatedBytes());
    }

    @Test
    void constructorRejectsNonPositiveBudget() {
        assertThrows(IllegalArgumentException.class, () -> new DictionarySampleCollector(0));
        assertThrows(IllegalArgumentException.class, () -> new DictionarySampleCollector(-1));
    }

    @Test
    void isFullFlipsCorrectlyOnExactBudget() {
        DictionarySampleCollector collector = new DictionarySampleCollector(512);
        assertFalse(collector.isFull());
        collector.record(filledArray(256, (byte) 1));
        assertFalse(collector.isFull());
        collector.record(filledArray(256, (byte) 2));
        assertTrue(collector.isFull());
    }

    @Test
    void isFullReflectsStateWithoutReset() throws InterruptedException {
        DictionarySampleCollector collector = new DictionarySampleCollector(512);
        collector.record(filledArray(256, (byte) 1));
        collector.record(filledArray(256, (byte) 2));
        boolean[] observed = new boolean[1];
        Thread observer = new Thread(() -> observed[0] = collector.isFull());
        observer.start();
        observer.join();
        assertTrue(observed[0]);
    }
}
