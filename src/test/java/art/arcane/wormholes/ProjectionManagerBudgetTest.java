package art.arcane.wormholes;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProjectionManagerBudgetTest {
    @Test
    void rotatesScarceBudgetAcrossObservers() {
        assertArrayEquals(new int[] {1, 1, 0}, ProjectionManager.fairBudgetAllocations(3, 2, 1, 0L));
        assertArrayEquals(new int[] {1, 0, 1}, ProjectionManager.fairBudgetAllocations(3, 2, 1, 1L));
        assertArrayEquals(new int[] {0, 1, 1}, ProjectionManager.fairBudgetAllocations(3, 2, 1, 2L));
    }

    @Test
    void respectsPerObserverAndTotalCaps() {
        assertArrayEquals(new int[] {2, 2, 2}, ProjectionManager.fairBudgetAllocations(3, 7, 2, 0L));
        assertArrayEquals(new int[] {0, 0, 0}, ProjectionManager.fairBudgetAllocations(3, 0, 2, 0L));
        assertArrayEquals(new int[0], ProjectionManager.fairBudgetAllocations(0, 8, 2, 0L));
    }

    @Test
    void thousandObserversAllReceiveBudgetWithinOneRotation() {
        boolean[] seen = new boolean[1_000];
        for (long tick = 0; tick < 42; tick++) {
            int[] allocations = ProjectionManager.fairBudgetAllocations(1_000, 24, 1, tick);
            int allocated = 0;
            for (int observer = 0; observer < allocations.length; observer++) {
                if (allocations[observer] > 0) {
                    seen[observer] = true;
                    allocated += allocations[observer];
                }
            }
            assertEquals(24, allocated);
        }
        for (boolean observerSeen : seen) {
            assertTrue(observerSeen);
        }
    }

    @Test
    void rotatesObserverDiscoveryInBoundedBatches() {
        assertArrayEquals(new int[] {0, 64, 128, 192}, new int[] {
            ProjectionManager.observerDiscoveryStart(1_000, 64, 1L),
            ProjectionManager.observerDiscoveryStart(1_000, 64, 2L),
            ProjectionManager.observerDiscoveryStart(1_000, 64, 3L),
            ProjectionManager.observerDiscoveryStart(1_000, 64, 4L)
        });
        assertArrayEquals(new int[] {960, 24}, new int[] {
            ProjectionManager.observerDiscoveryStart(1_000, 64, 16L),
            ProjectionManager.observerDiscoveryStart(1_000, 64, 17L)
        });
    }

    @Test
    void observerDiscoveryHandlesEmptyAndOversizedBatches() {
        assertArrayEquals(new int[] {0, 0, 0}, new int[] {
            ProjectionManager.observerDiscoveryStart(0, 64, 1L),
            ProjectionManager.observerDiscoveryStart(1_000, 0, 1L),
            ProjectionManager.observerDiscoveryStart(32, 64, 2L)
        });
    }

    @Test
    void rotatesScarcePortalBudgetInsteadOfStarvingSecondProjection() {
        List<String> portals = List.of("nearest", "overlapping", "farther");

        assertEquals(List.of("nearest"), ProjectionManager.selectRoundRobin(portals, 1, 0));
        assertEquals(List.of("overlapping"), ProjectionManager.selectRoundRobin(portals, 1, 1));
        assertEquals(List.of("farther"), ProjectionManager.selectRoundRobin(portals, 1, 2));
        assertEquals(List.of("nearest"), ProjectionManager.selectRoundRobin(portals, 1, 3));
        assertEquals(List.of("overlapping", "farther"), ProjectionManager.selectRoundRobin(portals, 2, 1));
    }
}
