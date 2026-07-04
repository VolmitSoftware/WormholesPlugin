package art.arcane.wormholes.render;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;

import org.junit.jupiter.api.Test;

public final class ProjectionWorldChangeTrackerTest {
    private static final UUID WORLD = UUID.fromString("00000000-0000-0000-0000-00000000000a");
    private static final UUID OTHER_WORLD = UUID.fromString("00000000-0000-0000-0000-00000000000b");

    @Test
    public void markChangedInsideWindowReportsDirty() {
        ProjectionWorldChangeTracker tracker = new ProjectionWorldChangeTracker();
        tracker.markChanged(WORLD, 35, -18);

        assertTrue(tracker.dirtySince(WORLD, 0, -4, 4, 0, 0L));
        assertFalse(tracker.dirtySince(WORLD, 10, 10, 14, 14, 0L));
        assertFalse(tracker.dirtySince(OTHER_WORLD, 0, -4, 4, 0, 0L));
    }

    @Test
    public void sinceCurrentVersionReportsClean() {
        ProjectionWorldChangeTracker tracker = new ProjectionWorldChangeTracker();
        tracker.markChanged(WORLD, 35, -18);
        tracker.markChanged(WORLD, 100, 100);
        long current = tracker.currentVersion();

        assertFalse(tracker.dirtySince(WORLD, -100, -100, 100, 100, current));
        assertTrue(tracker.dirtySince(WORLD, -100, -100, 100, 100, current - 1L));
    }

    @Test
    public void repeatedSameChunkMarksStayDetectableAfterResample() {
        ProjectionWorldChangeTracker tracker = new ProjectionWorldChangeTracker();
        tracker.markChanged(WORLD, 35, -18);
        long afterFirst = tracker.currentVersion();
        tracker.markChanged(WORLD, 36, -17);

        assertEquals(2L, tracker.currentVersion());
        assertTrue(tracker.dirtySince(WORLD, 0, -4, 4, 0, afterFirst));
    }

    @Test
    public void overflowClearFloorsAllQueries() {
        ProjectionWorldChangeTracker tracker = new ProjectionWorldChangeTracker();
        for (int i = 0; i <= 8200; i++) {
            tracker.markChanged(WORLD, i << 4, 0);
        }

        assertTrue(tracker.dirtySince(WORLD, 500_000, 500_000, 500_001, 500_001, 1L));
    }

    @Test
    public void clearWorldResetsTracking() {
        ProjectionWorldChangeTracker tracker = new ProjectionWorldChangeTracker();
        tracker.markChanged(WORLD, 35, -18);
        tracker.clearWorld(WORLD);

        assertFalse(tracker.dirtySince(WORLD, 0, -4, 4, 0, 0L));
    }
}
