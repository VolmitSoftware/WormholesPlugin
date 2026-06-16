package art.arcane.wormholes.network.replication;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class BlockChangeCodecTest {
    @Test
    void packUnpackRoundTripPositiveY() {
        int packed = BlockChange.pack(7, 240, 13);
        assertEquals(7, BlockChange.unpackX(packed));
        assertEquals(240, BlockChange.unpackY(packed));
        assertEquals(13, BlockChange.unpackZ(packed));
    }

    @Test
    void packUnpackRoundTripNegativeY() {
        int packed = BlockChange.pack(0, -64, 0);
        assertEquals(0, BlockChange.unpackX(packed));
        assertEquals(-64, BlockChange.unpackY(packed));
        assertEquals(0, BlockChange.unpackZ(packed));
    }

    @Test
    void packUnpackRoundTripExtremeBounds() {
        int packedMax = BlockChange.pack(15, 319, 15);
        assertEquals(15, BlockChange.unpackX(packedMax));
        assertEquals(319, BlockChange.unpackY(packedMax));
        assertEquals(15, BlockChange.unpackZ(packedMax));

        int packedMin = BlockChange.pack(0, -64, 0);
        assertEquals(0, BlockChange.unpackX(packedMin));
        assertEquals(-64, BlockChange.unpackY(packedMin));
        assertEquals(0, BlockChange.unpackZ(packedMin));
    }

    @Test
    void packUnpackHandlesFullSignedRange() {
        for (int y = -64; y <= 319; y++) {
            int packed = BlockChange.pack(5, y, 9);
            assertEquals(y, BlockChange.unpackY(packed), "y=" + y);
        }
    }

    @Test
    void chunkDiffBatchEncodesAndDecodesMixedContent() throws IOException {
        List<BlockChange> blocks = new ArrayList<>();
        blocks.add(new BlockChange(BlockChange.pack(0, -64, 0), "minecraft:stone", BlockChange.FLAG_NONE));
        blocks.add(new BlockChange(BlockChange.pack(15, 319, 15), "minecraft:chest[facing=north,type=single,waterlogged=false]", BlockChange.FLAG_BLOCK_ENTITY_FOLLOWS));
        blocks.add(new BlockChange(BlockChange.pack(7, 80, 3), "minecraft:oak_stairs[facing=east,half=bottom,shape=straight,waterlogged=false]", BlockChange.FLAG_NONE));

        byte[] lightData = new byte[LightDiff.DATA_LENGTH];
        for (int i = 0; i < lightData.length; i++) {
            lightData[i] = (byte) (i & 0xFF);
        }
        List<LightDiff> lights = List.of(
            new LightDiff(-4, LightDiff.TYPE_BLOCKLIGHT, lightData),
            new LightDiff(19, LightDiff.TYPE_SKYLIGHT, lightData.clone())
        );

        byte[] nbtA = "sample-nbt-a".getBytes();
        byte[] nbtB = "sample-nbt-b".getBytes();
        List<BlockEntityDiff> entities = List.of(
            new BlockEntityDiff(BlockChange.pack(1, 100, 2), nbtA),
            new BlockEntityDiff(BlockChange.pack(14, -10, 9), nbtB)
        );

        ChunkDiffBatch original = new ChunkDiffBatch(0x1234567890ABCDEFL, 42L, blocks, lights, entities);
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        original.writeTo(new DataOutputStream(buffer));
        ChunkDiffBatch decoded = ChunkDiffBatch.read(new DataInputStream(new ByteArrayInputStream(buffer.toByteArray())));

        assertEquals(original.chunkKey(), decoded.chunkKey());
        assertEquals(original.sequence(), decoded.sequence());
        assertEquals(original.blocks().size(), decoded.blocks().size());
        for (int i = 0; i < original.blocks().size(); i++) {
            BlockChange a = original.blocks().get(i);
            BlockChange b = decoded.blocks().get(i);
            assertEquals(a.packedXyz(), b.packedXyz());
            assertEquals(a.state(), b.state());
            assertEquals(a.flags(), b.flags());
        }
        assertEquals(original.lights().size(), decoded.lights().size());
        for (int i = 0; i < original.lights().size(); i++) {
            LightDiff a = original.lights().get(i);
            LightDiff b = decoded.lights().get(i);
            assertEquals(a.sectionY(), b.sectionY());
            assertEquals(a.lightType(), b.lightType());
            assertArrayEquals(a.data(), b.data());
        }
        assertEquals(original.entities().size(), decoded.entities().size());
        for (int i = 0; i < original.entities().size(); i++) {
            BlockEntityDiff a = original.entities().get(i);
            BlockEntityDiff b = decoded.entities().get(i);
            assertEquals(a.packedXyz(), b.packedXyz());
            assertArrayEquals(a.nbt(), b.nbt());
        }
    }

    @Test
    void emptyBatchEncodesAndDecodes() throws IOException {
        ChunkDiffBatch original = new ChunkDiffBatch(1L, 1L, List.<BlockChange>of(), List.<LightDiff>of(), List.<BlockEntityDiff>of());
        byte[] encoded = original.encode();
        ChunkDiffBatch decoded = ChunkDiffBatch.decode(encoded);
        assertNotNull(decoded);
        assertEquals(0, decoded.blocks().size());
        assertEquals(0, decoded.lights().size());
        assertEquals(0, decoded.entities().size());
    }
}
