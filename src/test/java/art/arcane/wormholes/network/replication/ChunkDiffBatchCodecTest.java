package art.arcane.wormholes.network.replication;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

class ChunkDiffBatchCodecTest {
    @Test
    void blockChangePackUnpackRoundTrip() {
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                for (int y = 0; y < 384; y += 17) {
                    int packed = BlockChange.pack(x, y, z);
                    assertEquals(x, BlockChange.unpackX(packed));
                    assertEquals(z, BlockChange.unpackZ(packed));
                    assertEquals(y, BlockChange.unpackY(packed));
                }
            }
        }
    }

    @Test
    void chunkDiffBatchRoundTripsBlocksAndLights() throws IOException {
        long chunkKey = (((long) 5) << 32) | (((long) -7) & 0xFFFFFFFFL);
        long sequence = 42L;
        BlockChange b1 = new BlockChange(BlockChange.pack(1, 64, 2), "minecraft:stone", BlockChange.FLAG_NONE);
        BlockChange b2 = new BlockChange(BlockChange.pack(15, 200, 15), "minecraft:oak_fence[east=false,north=true,south=false,waterlogged=false,west=false]", BlockChange.FLAG_BLOCK_ENTITY_FOLLOWS);

        byte[] lightData = new byte[LightDiff.DATA_LENGTH];
        for (int i = 0; i < lightData.length; i++) {
            lightData[i] = (byte) (i & 0xFF);
        }
        LightDiff light = new LightDiff(4, LightDiff.TYPE_SKYLIGHT, lightData);

        byte[] nbt = new byte[]{1, 2, 3, 4, 5};
        BlockEntityDiff entity = new BlockEntityDiff(BlockChange.pack(3, 70, 4), nbt);

        ChunkDiffBatch batch = new ChunkDiffBatch(chunkKey, sequence, List.of(b1, b2), List.of(light), List.of(entity));
        byte[] encoded = batch.encode();
        ChunkDiffBatch decoded = ChunkDiffBatch.decode(encoded);

        assertEquals(chunkKey, decoded.chunkKey());
        assertEquals(sequence, decoded.sequence());
        assertEquals(2, decoded.blocks().size());
        assertEquals(b1.packedXyz(), decoded.blocks().get(0).packedXyz());
        assertEquals(b1.state(), decoded.blocks().get(0).state());
        assertEquals(b1.flags(), decoded.blocks().get(0).flags());
        assertEquals(b2.packedXyz(), decoded.blocks().get(1).packedXyz());
        assertEquals(b2.state(), decoded.blocks().get(1).state());
        assertEquals(b2.flags(), decoded.blocks().get(1).flags());

        assertEquals(1, decoded.lights().size());
        assertEquals(light.sectionY(), decoded.lights().get(0).sectionY());
        assertEquals(light.lightType(), decoded.lights().get(0).lightType());
        assertArrayEquals(light.data(), decoded.lights().get(0).data());

        assertEquals(1, decoded.entities().size());
        assertEquals(entity.packedXyz(), decoded.entities().get(0).packedXyz());
        assertArrayEquals(entity.nbt(), decoded.entities().get(0).nbt());
    }
}
