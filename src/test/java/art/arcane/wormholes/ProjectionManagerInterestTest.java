package art.arcane.wormholes;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.bukkit.Location;
import org.junit.jupiter.api.Test;

import art.arcane.wormholes.config.WormholesSettings;
import art.arcane.wormholes.config.toml.AdvancedConfig;
import art.arcane.wormholes.config.toml.MainConfig;
import art.arcane.wormholes.config.toml.ProjectionConfig;
import art.arcane.wormholes.config.toml.RenderConfig;

public final class ProjectionManagerInterestTest {
    @Test
    public void lookingTowardPortalKeepsObserverInterested() {
        Location eye = new Location(null, 0.0D, 64.0D, 0.0D, 0.0F, 0.0F);
        Location center = new Location(null, 0.0D, 64.0D, 8.0D);

        assertTrue(ProjectionManager.isLookingTowardPortal(eye, center, -0.2D));
    }

    @Test
    public void lookingAwayFromPortalRejectsObserver() {
        Location eye = new Location(null, 0.0D, 64.0D, 0.0D, 180.0F, 0.0F);
        Location center = new Location(null, 0.0D, 64.0D, 8.0D);

        assertFalse(ProjectionManager.isLookingTowardPortal(eye, center, -0.2D));
    }

    @Test
    public void veryCloseObserverRemainsInterested() {
        Location eye = new Location(null, 0.0D, 64.0D, 0.0D, 180.0F, 0.0F);
        Location center = new Location(null, 0.0D, 64.0D, 0.0D);

        assertTrue(ProjectionManager.isLookingTowardPortal(eye, center, -0.2D));
    }

    @Test
    public void sideGraceRejectsEdgeOnViews() {
        assertFalse(ProjectionManager.hasStablePortalSide(8.0D, 64.0D, 0.0D,
                0.0D, 64.0D, 0.0D,
                0.0D, 0.0D, -1.0D,
                0.12D));
    }

    @Test
    public void sideGraceAllowsFrontAndBackViews() {
        assertTrue(ProjectionManager.hasStablePortalSide(0.0D, 64.0D, -8.0D,
                0.0D, 64.0D, 0.0D,
                0.0D, 0.0D, -1.0D,
                0.12D));

        assertTrue(ProjectionManager.hasStablePortalSide(0.0D, 64.0D, 8.0D,
                0.0D, 64.0D, 0.0D,
                0.0D, 0.0D, -1.0D,
                0.12D));
    }

    @Test
    public void foveatedUnrenderingDefaultsOffAndBypassesViewportInterest() {
        assertTrue(ProjectionManager.isObserverProjectionInterested(null, null, null, false));
        assertFalse(ProjectionManager.isObserverProjectionInterested(null, null, null, true));
    }

    @Test
    public void recursivePortalDepthClampsToBoundedRange() {
        ProjectionConfig projection = new ProjectionConfig();
        projection.recursivePortalDepth = 1;
        Settings.refresh(new WormholesSettings(new MainConfig(), projection, new RenderConfig(), new AdvancedConfig()));
        assertEquals(3, Settings.PROJECTION_RECURSIVE_PORTAL_DEPTH);

        projection.recursivePortalDepth = 128;
        Settings.refresh(new WormholesSettings(new MainConfig(), projection, new RenderConfig(), new AdvancedConfig()));
        assertEquals(64, Settings.PROJECTION_RECURSIVE_PORTAL_DEPTH);

        projection.recursivePortalDepth = 12;
        Settings.refresh(new WormholesSettings(new MainConfig(), projection, new RenderConfig(), new AdvancedConfig()));
        assertEquals(12, Settings.PROJECTION_RECURSIVE_PORTAL_DEPTH);

        Settings.refresh(new WormholesSettings(new MainConfig(), new ProjectionConfig(), new RenderConfig(), new AdvancedConfig()));
    }
}
