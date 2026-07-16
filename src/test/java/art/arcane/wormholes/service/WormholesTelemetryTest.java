package art.arcane.wormholes.service;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class WormholesTelemetryTest {
    @BeforeEach
    void setUp() {
        WormholesTelemetry.clear();
    }

    @AfterEach
    void tearDown() {
        WormholesTelemetry.clear();
    }

    @Test
    void blockChangesDoNotImplicitlyCountPackets() {
        primeRateWindow();

        WormholesTelemetry.countBlockChange();
        WormholesTelemetry.countBlockChange();
        WormholesTelemetry.countBlockChange();

        assertEquals(3.0D, WormholesTelemetry.blockChangesPerSecond(2_000L));
        assertEquals(0.0D, WormholesTelemetry.packetsPerSecond(2_000L));
    }

    @Test
    void packetsAndBlockChangesAreCountedIndependently() {
        primeRateWindow();

        WormholesTelemetry.countBlockChange();
        WormholesTelemetry.countBlockChange();
        WormholesTelemetry.countBlockChange();
        WormholesTelemetry.countPacket();
        WormholesTelemetry.countPacket();

        assertEquals(3.0D, WormholesTelemetry.blockChangesPerSecond(2_000L));
        assertEquals(2.0D, WormholesTelemetry.packetsPerSecond(2_000L));
    }

    private static void primeRateWindow() {
        WormholesTelemetry.blockChangesPerSecond(1_000L);
    }
}
