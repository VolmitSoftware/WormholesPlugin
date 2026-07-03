package art.arcane.wormholes.portal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;

import org.junit.jupiter.api.Test;

public final class PortalUpdateGateTest {
    @Test
    public void openPortalIsAlwaysDue() {
        for (long tick = 0L; tick <= 40L; tick++) {
            assertTrue(PortalUpdateGate.isDue(true, false, tick, 3));
        }
    }

    @Test
    public void attendedClosedPortalIsAlwaysDue() {
        for (long tick = 0L; tick <= 40L; tick++) {
            assertTrue(PortalUpdateGate.isDue(false, true, tick, 7));
        }
    }

    @Test
    public void idleClosedPortalIsDueExactlyOncePerTenTicks() {
        int due = 0;
        for (long tick = 1L; tick <= 100L; tick++) {
            if (PortalUpdateGate.isDue(false, false, tick, 4)) {
                due++;
            }
        }
        assertEquals(10, due);
    }

    @Test
    public void staggerOffsetsSpreadDistinctPortalIds() {
        UUID first = new UUID(0L, 1L);
        UUID second = new UUID(0L, 2L);
        int firstOffset = PortalUpdateGate.staggerOffset(first);
        int secondOffset = PortalUpdateGate.staggerOffset(second);

        assertNotEquals(firstOffset, secondOffset);

        for (long tick = 0L; tick < PortalUpdateGate.IDLE_UPDATE_INTERVAL_TICKS; tick++) {
            boolean firstDue = PortalUpdateGate.isDue(false, false, tick, firstOffset);
            boolean secondDue = PortalUpdateGate.isDue(false, false, tick, secondOffset);
            assertFalse(firstDue && secondDue);
        }
    }
}
