package art.arcane.wormholes.network.view;

import org.bukkit.Material;
import org.bukkit.entity.Pose;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ViewServerBlobRecaptureGateTest {
    private static final long INTERVAL = 40L;

    private static EntityVisual visual() {
        return EntityVisual.full(
            new UUID(1L, 2L),
            "minecraft:zombie",
            0.0D, 64.0D, 0.0D,
            1.95D,
            0.0D, 0.0D, 1.0D,
            0.0F, 0.0F,
            0.0D, 0.0D, 0.0D,
            true,
            "",
            "",
            "",
            null,
            null,
            new byte[]{1},
            new byte[]{2},
            0
        );
    }

    private static ViewServer.BlobCaptureState state(long tick) {
        return new ViewServer.BlobCaptureState(tick, Pose.STANDING, false, Material.AIR, Material.AIR);
    }

    @Test
    void nullPreviousVisualRecaptures() {
        assertTrue(ViewServer.shouldRecaptureBlobs(null, state(0L), 1L, INTERVAL, Pose.STANDING, false, Material.AIR, Material.AIR));
    }

    @Test
    void nullPreviousBlobStateRecaptures() {
        assertTrue(ViewServer.shouldRecaptureBlobs(visual(), null, 1L, INTERVAL, Pose.STANDING, false, Material.AIR, Material.AIR));
    }

    @Test
    void intervalElapsedRecaptures() {
        assertFalse(ViewServer.shouldRecaptureBlobs(visual(), state(0L), 39L, INTERVAL, Pose.STANDING, false, Material.AIR, Material.AIR));
        assertTrue(ViewServer.shouldRecaptureBlobs(visual(), state(0L), 40L, INTERVAL, Pose.STANDING, false, Material.AIR, Material.AIR));
    }

    @Test
    void poseChangeRecapturesImmediately() {
        assertTrue(ViewServer.shouldRecaptureBlobs(visual(), state(0L), 1L, INTERVAL, Pose.SNEAKING, false, Material.AIR, Material.AIR));
    }

    @Test
    void fireToggleRecapturesImmediately() {
        assertTrue(ViewServer.shouldRecaptureBlobs(visual(), state(0L), 1L, INTERVAL, Pose.STANDING, true, Material.AIR, Material.AIR));
    }

    @Test
    void heldItemChangeRecapturesImmediately() {
        assertTrue(ViewServer.shouldRecaptureBlobs(visual(), state(0L), 1L, INTERVAL, Pose.STANDING, false, Material.STONE, Material.AIR));
        assertTrue(ViewServer.shouldRecaptureBlobs(visual(), state(0L), 1L, INTERVAL, Pose.STANDING, false, Material.AIR, Material.SHIELD));
    }

    @Test
    void unchangedStateWithinIntervalReuses() {
        assertFalse(ViewServer.shouldRecaptureBlobs(visual(), state(10L), 20L, INTERVAL, Pose.STANDING, false, Material.AIR, Material.AIR));
    }
}
