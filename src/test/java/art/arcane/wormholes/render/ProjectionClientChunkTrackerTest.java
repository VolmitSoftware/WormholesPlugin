package art.arcane.wormholes.render;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;

import org.junit.jupiter.api.Test;

public final class ProjectionClientChunkTrackerTest {
    @Test
    public void chunkKeyMatchesPaperPackingForPositiveAndNegativeCoordinates() {
        assertEquals(1L | (2L << 32), ProjectionClientChunkTracker.chunkKey(1, 2));
        assertEquals(0xFFFFFFFFL | (0xFFFFFFFEL << 32), ProjectionClientChunkTracker.chunkKey(-1, -2));
    }

    @Test
    public void sentUnloadAndResetTransitionsAreIdempotent() {
        ProjectionClientChunkTracker tracker = new ProjectionClientChunkTracker();
        UUID playerId = UUID.fromString("00000000-0000-0000-0000-000000000071");
        long chunkKey = ProjectionClientChunkTracker.chunkKey(-3, 5);

        assertFalse(tracker.isTracked(playerId, -3, 5));
        assertEquals(Long.MIN_VALUE, tracker.revision(playerId));
        tracker.markSent(playerId, chunkKey);
        long firstRevision = tracker.revision(playerId);
        assertEquals(firstRevision, tracker.chunkRevision(playerId, -3, 5));
        tracker.markSent(playerId, chunkKey);
        long replacementRevision = tracker.revision(playerId);
        assertTrue(replacementRevision > firstRevision);
        assertEquals(replacementRevision, tracker.chunkRevision(playerId, -3, 5));
        assertTrue(tracker.isTracked(playerId, -3, 5));

        tracker.markUnsent(playerId, chunkKey);
        assertTrue(tracker.revision(playerId) > replacementRevision);
        assertEquals(Long.MIN_VALUE, tracker.chunkRevision(playerId, -3, 5));
        tracker.markUnsent(playerId, chunkKey);
        assertFalse(tracker.isTracked(playerId, -3, 5));

        tracker.markSent(playerId, chunkKey);
        tracker.forget(playerId);
        assertFalse(tracker.isTracked(playerId, -3, 5));
        assertEquals(Long.MIN_VALUE, tracker.revision(playerId));
    }
}
