package art.arcane.wormholes;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import art.arcane.wormholes.util.Direction;

public final class ConstructionManagerTest {
    @Test
    public void coplanarAreaAcceptsPlanesLinesAndPoints() {
        assertTrue(ConstructionManager.isCoplanarPortalArea(0, 3, 4));
        assertTrue(ConstructionManager.isCoplanarPortalArea(5, 0, 2));
        assertTrue(ConstructionManager.isCoplanarPortalArea(5, 2, 0));
        assertTrue(ConstructionManager.isCoplanarPortalArea(0, 0, 4));
        assertTrue(ConstructionManager.isCoplanarPortalArea(5, 0, 0));
        assertTrue(ConstructionManager.isCoplanarPortalArea(0, 0, 0));
    }

    @Test
    public void coplanarAreaRejectsVolumes() {
        assertFalse(ConstructionManager.isCoplanarPortalArea(2, 3, 4));
        assertFalse(ConstructionManager.isCoplanarPortalArea(1, 1, 1));
    }

    @Test
    public void planarNormalFollowsFlatAxis() {
        assertEquals(Direction.E, ConstructionManager.derivePortalNormal(0, 3, 4, 1.0D, 0.0D, 0.0D));
        assertEquals(Direction.W, ConstructionManager.derivePortalNormal(0, 3, 4, -0.2D, 0.9D, 0.1D));
        assertEquals(Direction.U, ConstructionManager.derivePortalNormal(5, 0, 2, 0.0D, 1.0D, 0.0D));
        assertEquals(Direction.D, ConstructionManager.derivePortalNormal(5, 0, 2, 0.9D, -0.1D, 0.3D));
        assertEquals(Direction.S, ConstructionManager.derivePortalNormal(5, 2, 0, 0.0D, 0.0D, 1.0D));
        assertEquals(Direction.N, ConstructionManager.derivePortalNormal(5, 2, 0, 0.1D, 0.2D, -0.9D));
    }

    @Test
    public void lineNormalPicksLookDominantFlatAxis() {
        assertEquals(Direction.N, ConstructionManager.derivePortalNormal(2, 0, 0, 0.0D, 0.0D, -1.0D));
        assertEquals(Direction.D, ConstructionManager.derivePortalNormal(2, 0, 0, 0.0D, -1.0D, 0.0D));
        assertEquals(Direction.E, ConstructionManager.derivePortalNormal(0, 2, 0, 1.0D, 0.5D, 0.0D));
        assertEquals(Direction.S, ConstructionManager.derivePortalNormal(0, 2, 0, 0.1D, 0.5D, 1.0D));
    }

    @Test
    public void pointNormalPicksLookDominantAxis() {
        assertEquals(Direction.U, ConstructionManager.derivePortalNormal(0, 0, 0, 0.1D, 0.9D, 0.2D));
        assertEquals(Direction.S, ConstructionManager.derivePortalNormal(0, 0, 0, 0.0D, 0.0D, 1.0D));
        assertEquals(Direction.W, ConstructionManager.derivePortalNormal(0, 0, 0, -0.8D, 0.1D, 0.2D));
    }
}
