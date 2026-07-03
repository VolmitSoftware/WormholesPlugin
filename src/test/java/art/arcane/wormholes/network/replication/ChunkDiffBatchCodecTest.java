package art.arcane.wormholes.network.replication;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
        LightDiff light = LightDiff.full(4, LightDiff.TYPE_SKYLIGHT, lightData);

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

    @Test
    void stateTableDeduplicatesRepeatedStatesAndShrinksEncoding() throws IOException {
        String stone = "minecraft:stone";
        String fence = "minecraft:oak_fence[east=false,north=true,south=false,waterlogged=false,west=false]";
        List<BlockChange> blocks = new ArrayList<>(500);
        for (int i = 0; i < 500; i++) {
            String state = (i & 1) == 0 ? stone : fence;
            blocks.add(new BlockChange(BlockChange.pack(i & 0xF, 60 + (i >> 4), (i >> 2) & 0xF), state, BlockChange.FLAG_NONE));
        }
        ChunkDiffBatch batch = new ChunkDiffBatch(1L, 9L, blocks, List.<LightDiff>of(), List.<BlockEntityDiff>of());
        byte[] encoded = batch.encode();
        ChunkDiffBatch decoded = ChunkDiffBatch.decode(encoded);
        assertEquals(500, decoded.blocks().size());
        for (int i = 0; i < 500; i++) {
            assertEquals(blocks.get(i).packedXyz(), decoded.blocks().get(i).packedXyz());
            assertEquals(blocks.get(i).state(), decoded.blocks().get(i).state());
            assertEquals(blocks.get(i).flags(), decoded.blocks().get(i).flags());
        }
        int uniqueStringBytes = stone.length() + fence.length() + 4;
        assertTrue(encoded.length < 500 * 8 + uniqueStringBytes + 64,
            "table-encoded batch is " + encoded.length + " bytes");
    }

    @Test
    void decodeRejectsStateIndexBeyondTable() throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(buffer);
        out.writeLong(1L);
        ReplicationVarint.writeULong(out, 2L);
        ReplicationVarint.writeUInt(out, 1);
        out.writeUTF("minecraft:stone");
        ReplicationVarint.writeUInt(out, 1);
        ReplicationVarint.writeUInt(out, BlockChange.pack(0, 60, 0));
        ReplicationVarint.writeUInt(out, 1);
        out.writeByte(0);
        out.flush();
        assertThrows(IOException.class, () -> ChunkDiffBatch.decode(buffer.toByteArray()));
    }

    @Test
    void decodeRejectsOversizedStateTable() throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(buffer);
        out.writeLong(1L);
        ReplicationVarint.writeULong(out, 2L);
        ReplicationVarint.writeUInt(out, ChunkDiffBatch.MAX_BLOCKS + 1);
        out.flush();
        assertThrows(IOException.class, () -> ChunkDiffBatch.decode(buffer.toByteArray()));
    }

    @Test
    void emptyBlocksBatchRoundTripsWithZeroTable() throws IOException {
        byte[] lightData = new byte[LightDiff.DATA_LENGTH];
        lightData[7] = (byte) 0x5A;
        ChunkDiffBatch batch = new ChunkDiffBatch(3L, 4L,
            List.<BlockChange>of(),
            List.of(LightDiff.full(2, LightDiff.TYPE_BLOCKLIGHT, lightData)),
            List.<BlockEntityDiff>of());
        ChunkDiffBatch decoded = ChunkDiffBatch.decode(batch.encode());
        assertEquals(0, decoded.blocks().size());
        assertEquals(1, decoded.lights().size());
        assertArrayEquals(lightData, decoded.lights().get(0).data());
    }

    @Test
    void sparseLightDiffRoundTripsCellsAndLevels() throws IOException {
        byte[] data = new byte[LightDiff.DATA_LENGTH];
        int[] cells = {0, 1, 17, 500, 501, 4095};
        for (int cell : cells) {
            int level = (cell % 15) + 1;
            if ((cell & 1) == 0) {
                data[cell >> 1] = (byte) ((data[cell >> 1] & 0xF0) | level);
            } else {
                data[cell >> 1] = (byte) ((data[cell >> 1] & 0x0F) | (level << 4));
            }
        }
        LightDiff pendingSparse = LightDiff.pending(3, LightDiff.TYPE_SKYLIGHT, data, cells);
        byte[] fullData = new byte[LightDiff.DATA_LENGTH];
        fullData[100] = (byte) 0xAB;
        LightDiff full = LightDiff.full(-2, LightDiff.TYPE_BLOCKLIGHT, fullData);
        ChunkDiffBatch batch = new ChunkDiffBatch(5L, 6L,
            List.<BlockChange>of(),
            List.of(pendingSparse, full),
            List.<BlockEntityDiff>of());
        ChunkDiffBatch decoded = ChunkDiffBatch.decode(batch.encode());
        assertEquals(2, decoded.lights().size());
        LightDiff sparseDecoded = decoded.lights().get(0);
        assertEquals(3, sparseDecoded.sectionY());
        assertEquals(LightDiff.TYPE_SKYLIGHT, sparseDecoded.lightType());
        assertNull(sparseDecoded.data());
        assertArrayEquals(cells, sparseDecoded.sparseCells());
        for (int i = 0; i < cells.length; i++) {
            int expected = (cells[i] % 15) + 1;
            assertEquals(expected, ChunkDiffBatch.nibbleAt(sparseDecoded.sparseLevels(), i), "cell " + cells[i]);
        }
        LightDiff fullDecoded = decoded.lights().get(1);
        assertEquals(-2, fullDecoded.sectionY());
        assertNull(fullDecoded.sparseCells());
        assertArrayEquals(fullData, fullDecoded.data());

        ChunkDiffBatch reDecoded = ChunkDiffBatch.decode(decoded.encode());
        assertArrayEquals(cells, reDecoded.lights().get(0).sparseCells());
        assertArrayEquals(sparseDecoded.sparseLevels(), reDecoded.lights().get(0).sparseLevels());
    }

    @Test
    void decodeRejectsMalformedSparseLights() throws IOException {
        assertThrows(IOException.class, () -> ChunkDiffBatch.decode(sparseLightPayload(0, new int[0])));
        assertThrows(IOException.class, () -> ChunkDiffBatch.decode(sparseLightPayload(5000, new int[0])));
        assertThrows(IOException.class, () -> ChunkDiffBatch.decode(sparseLightPayloadRaw(new int[]{5, 0})));
        assertThrows(IOException.class, () -> ChunkDiffBatch.decode(sparseLightPayloadRaw(new int[]{4000, 200})));
    }

    private static byte[] sparseLightPayload(int declaredCount, int[] deltas) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(buffer);
        writeLightHeader(out);
        ReplicationVarint.writeUInt(out, declaredCount);
        for (int delta : deltas) {
            ReplicationVarint.writeUInt(out, delta);
        }
        out.flush();
        return buffer.toByteArray();
    }

    private static byte[] sparseLightPayloadRaw(int[] rawValues) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(buffer);
        writeLightHeader(out);
        ReplicationVarint.writeUInt(out, rawValues.length);
        for (int value : rawValues) {
            ReplicationVarint.writeUInt(out, value);
        }
        out.write(new byte[(rawValues.length + 1) >> 1]);
        out.flush();
        return buffer.toByteArray();
    }

    private static void writeLightHeader(DataOutputStream out) throws IOException {
        out.writeLong(1L);
        ReplicationVarint.writeULong(out, 2L);
        ReplicationVarint.writeUInt(out, 0);
        ReplicationVarint.writeUInt(out, 0);
        ReplicationVarint.writeUInt(out, 1);
        out.writeInt(4);
        out.writeByte(LightDiff.TYPE_BLOCKLIGHT | 0x80);
    }
}
