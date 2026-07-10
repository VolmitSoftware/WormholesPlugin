package art.arcane.wormholes;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;

class ProjectionManagerBudgetTest {
    @Test
    void rotatesScarceBudgetAcrossObservers() {
        assertArrayEquals(new int[] {1, 1, 0}, ProjectionManager.fairBudgetAllocations(3, 2, 1, 0L));
        assertArrayEquals(new int[] {0, 1, 1}, ProjectionManager.fairBudgetAllocations(3, 2, 1, 1L));
        assertArrayEquals(new int[] {1, 0, 1}, ProjectionManager.fairBudgetAllocations(3, 2, 1, 2L));
    }

    @Test
    void respectsPerObserverAndTotalCaps() {
        assertArrayEquals(new int[] {2, 2, 2}, ProjectionManager.fairBudgetAllocations(3, 7, 2, 0L));
        assertArrayEquals(new int[] {0, 0, 0}, ProjectionManager.fairBudgetAllocations(3, 0, 2, 0L));
        assertArrayEquals(new int[0], ProjectionManager.fairBudgetAllocations(0, 8, 2, 0L));
    }
}
