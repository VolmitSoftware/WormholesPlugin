package art.arcane.wormholes.network.view;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RemoteViewCacheEntitySequenceTest {
    @Test
    void deltaRequiresAnExistingContiguousBaseline() {
        EntityVisual baseline = visual(10);

        assertFalse(RemoteViewCache.canApplyEntityUpdate(delta(11), null));
        assertTrue(RemoteViewCache.canApplyEntityUpdate(delta(11), baseline));
        assertFalse(RemoteViewCache.canApplyEntityUpdate(delta(12), baseline));
        assertFalse(RemoteViewCache.canApplyEntityUpdate(delta(10), baseline));
    }

    @Test
    void fullSnapshotRecoversAnyGapAndSequenceWrapRemainsContiguous() {
        assertTrue(RemoteViewCache.canApplyEntityUpdate(visual(3), visual(200)));
        assertFalse(RemoteViewCache.canApplyEntityUpdate(delta(2), visual(3)));
        assertTrue(RemoteViewCache.canApplyEntityUpdate(delta(0), visual(65535)));
    }

    private static EntityVisual delta(int sequence) {
        EntityVisual full = visual(sequence);
        return EntityDeltaCodec.buildDelta(full, full, sequence, EntityVisual.FIELD_POSITION);
    }

    private static EntityVisual visual(int sequence) {
        return EntityVisual.full(
            new UUID(0L, 1L),
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
            new byte[0],
            new byte[0],
            sequence
        );
    }
}
