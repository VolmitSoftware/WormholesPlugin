package art.arcane.wormholes.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DebugTelemetryServiceTest {
    @Test
    void ratesUseTheMeasuredElapsedTime() {
        DebugTelemetryService.CounterSnapshot previous = counters(1_000_000_000L, 0L);
        DebugTelemetryService.CounterSnapshot current = new DebugTelemetryService.CounterSnapshot(
            3_000_000_000L,
            2L,
            4L,
            6L,
            8L,
            10L,
            12L,
            14L,
            16L,
            18L,
            20L,
            22L,
            24L,
            26L,
            28L,
            30L,
            32L
        );

        DebugTelemetryService.RateSnapshot rates = DebugTelemetryService.RateSnapshot.between(previous, current);

        assertEquals(2.0D, rates.elapsedSeconds());
        assertEquals(1.0D, rates.rawBytesInPerSecond());
        assertEquals(2.0D, rates.wireBytesInPerSecond());
        assertEquals(3.0D, rates.rawBytesOutPerSecond());
        assertEquals(4.0D, rates.wireBytesOutPerSecond());
        assertEquals(5.0D, rates.viewBulkPerSecond());
        assertEquals(6.0D, rates.viewDiffPerSecond());
        assertEquals(7.0D, rates.viewEntityPerSecond());
        assertEquals(8.0D, rates.viewTimePerSecond());
        assertEquals(9.0D, rates.replicatedBlocksPerSecond());
        assertEquals(10.0D, rates.resyncPerSecond());
        assertEquals(11.0D, rates.transfersCompletedPerSecond());
        assertEquals(12.0D, rates.transfersFailedPerSecond());
        assertEquals(13.0D, rates.sidebandDroppedBytesPerSecond());
        assertEquals(14.0D, rates.sidebandDroppedCountPerSecond());
        assertEquals(15.0D, rates.captureDroppedPerSecond());
        assertEquals(16.0D, rates.captureOverflowPerSecond());
    }

    @Test
    void ratesClampCountersThatReset() {
        DebugTelemetryService.CounterSnapshot previous = counters(1_000_000_000L, 100L);
        DebugTelemetryService.CounterSnapshot current = counters(2_000_000_000L, 5L);

        DebugTelemetryService.RateSnapshot rates = DebugTelemetryService.RateSnapshot.between(previous, current);

        assertEquals(0.0D, rates.rawBytesOutPerSecond());
        assertEquals(0.0D, rates.viewDiffPerSecond());
        assertEquals(0.0D, rates.captureOverflowPerSecond());
    }

    @Test
    void ratesAreZeroWhenTimeDoesNotAdvance() {
        DebugTelemetryService.CounterSnapshot previous = counters(1_000_000_000L, 10L);
        DebugTelemetryService.CounterSnapshot current = counters(1_000_000_000L, 20L);

        DebugTelemetryService.RateSnapshot rates = DebugTelemetryService.RateSnapshot.between(previous, current);

        assertEquals(DebugTelemetryService.RateSnapshot.zero(), rates);
    }

    private static DebugTelemetryService.CounterSnapshot counters(long capturedAtNanos, long value) {
        return new DebugTelemetryService.CounterSnapshot(
            capturedAtNanos,
            value,
            value,
            value,
            value,
            value,
            value,
            value,
            value,
            value,
            value,
            value,
            value,
            value,
            value,
            value,
            value
        );
    }
}
