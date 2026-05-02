package art.arcane.wormholes;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

public final class ConstructionManagerTest {
    @Test
    public void flatPortalAreaAcceptsOnePlanarAxis() {
        assertTrue(ConstructionManager.isFlatPortalArea(0, 3, 4));
        assertTrue(ConstructionManager.isFlatPortalArea(5, 0, 2));
        assertTrue(ConstructionManager.isFlatPortalArea(5, 2, 0));
    }

    @Test
    public void flatPortalAreaRejectsLinesPointsAndVolumes() {
        assertFalse(ConstructionManager.isFlatPortalArea(0, 0, 4));
        assertFalse(ConstructionManager.isFlatPortalArea(0, 0, 0));
        assertFalse(ConstructionManager.isFlatPortalArea(2, 3, 4));
    }
}
