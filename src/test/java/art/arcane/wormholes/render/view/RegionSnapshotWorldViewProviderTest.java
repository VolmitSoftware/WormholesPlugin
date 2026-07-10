package art.arcane.wormholes.render.view;

import art.arcane.wormholes.network.view.EntityVisual;
import art.arcane.wormholes.network.view.RemoteViewCache;
import art.arcane.wormholes.render.ProjectionWorldChangeTracker;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RegionSnapshotWorldViewProviderTest {
    @Test
    void keepsEntityStateStableWhenOnlyMotionChanges() {
        UUID id = UUID.randomUUID();
        EntityVisual first = visual(id, 1.0D, new byte[] {1, 2}, new byte[] {3});
        EntityVisual moved = visual(id, 9.0D, new byte[] {1, 2}, new byte[] {3});
        RemoteViewCache.RemoteProfile profile = new RemoteViewCache.RemoteProfile("Player", "texture", "signature");

        assertTrue(RegionSnapshotWorldViewProvider.sameEntityState(first, profile, moved, profile));
    }

    @Test
    void changesEntityStateForMetadataEquipmentOrProfileChanges() {
        UUID id = UUID.randomUUID();
        EntityVisual base = visual(id, 1.0D, new byte[] {1}, new byte[] {2});
        RemoteViewCache.RemoteProfile profile = new RemoteViewCache.RemoteProfile("Player", "texture", "signature");

        assertFalse(RegionSnapshotWorldViewProvider.sameEntityState(base, profile,
            visual(id, 1.0D, new byte[] {9}, new byte[] {2}), profile));
        assertFalse(RegionSnapshotWorldViewProvider.sameEntityState(base, profile,
            visual(id, 1.0D, new byte[] {1}, new byte[] {9}), profile));
        assertFalse(RegionSnapshotWorldViewProvider.sameEntityState(base, profile, base,
            new RemoteViewCache.RemoteProfile("Other", "texture", "signature")));
    }

    @Test
    void invalidatesOnlyChangedChunkAfterCapturedVersion() {
        ProjectionWorldChangeTracker tracker = new ProjectionWorldChangeTracker();
        UUID worldId = UUID.randomUUID();
        long capturedVersion = tracker.currentVersion();
        tracker.markChanged(worldId, 2 << 4, 3 << 4);

        assertTrue(RegionSnapshotWorldViewProvider.isChunkDirty(tracker, worldId, 2, 3, capturedVersion));
        assertFalse(RegionSnapshotWorldViewProvider.isChunkDirty(tracker, worldId, 1, 3, capturedVersion));
        assertFalse(RegionSnapshotWorldViewProvider.isChunkDirty(tracker, worldId, 2, 3, tracker.currentVersion()));
    }

    private static EntityVisual visual(UUID id, double x, byte[] metadata, byte[] equipment) {
        return EntityVisual.full(id, "minecraft:zombie", x, 64.0D, 0.0D, 1.8D,
            0.0D, 0.0D, 1.0D, 0.0F, 0.0F, 0.0D, 0.0D, 0.0D, true,
            "", "", "", null, null, metadata, equipment, 0);
    }
}
