package art.arcane.wormholes.perf;

import art.arcane.wormholes.network.replication.BlockChange;
import art.arcane.wormholes.network.replication.BlockEntityDiff;
import art.arcane.wormholes.network.replication.ChunkBulkBuilder;
import art.arcane.wormholes.network.replication.ChunkDiffBatch;
import art.arcane.wormholes.network.replication.LightDiff;
import art.arcane.wormholes.network.view.ViewSlice;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WireFormatV2SizeTest {
    private static final String[] REALISTIC_STATES = {
        "minecraft:air",
        "minecraft:stone",
        "minecraft:deepslate",
        "minecraft:dirt",
        "minecraft:grass_block[snowy=false]",
        "minecraft:oak_log[axis=y]",
        "minecraft:oak_leaves[distance=1,persistent=false,waterlogged=false]",
        "minecraft:water[level=0]",
        "minecraft:gravel",
        "minecraft:copper_ore"
    };
    private static final String[] REALISTIC_BIOMES = {
        "minecraft:plains", "minecraft:forest", "minecraft:dripstone_caves"
    };

    @Test
    void fullHeightBulkEncodesWellUnderV1Size() throws IOException {
        int minX = 0;
        int minY = -64;
        int minZ = 0;
        int sizeX = 16;
        int sizeY = 384;
        int sizeZ = 16;
        int cells = sizeX * sizeY * sizeZ;
        Random random = new Random(4242L);
        List<String> palette = new ArrayList<>(List.of(REALISTIC_STATES));
        List<String> biomePalette = new ArrayList<>(List.of(REALISTIC_BIOMES));
        short[] indices = new short[cells];
        byte[] light = new byte[cells];
        for (int i = 0; i < cells; i++) {
            indices[i] = (short) random.nextInt(palette.size());
            light[i] = (byte) random.nextInt(256);
        }
        int gridLength = ViewSlice.biomeGridSpan(minX, sizeX) * ViewSlice.biomeGridSpan(minY, sizeY) * ViewSlice.biomeGridSpan(minZ, sizeZ);
        short[] biomes = new short[gridLength];
        for (int i = 0; i < gridLength; i++) {
            biomes[i] = (short) random.nextInt(biomePalette.size());
        }
        ViewSlice slice = new ViewSlice(minX, minY, minZ, sizeX, sizeY, sizeZ, palette, indices, light, biomePalette, biomes);
        byte[] encoded = ChunkBulkBuilder.encodeSliceBytes(slice);

        long v1Bytes = 18L + paletteUtfBytes(palette) + (long) cells * 2 + cells + 2L + paletteUtfBytes(biomePalette) + (long) cells * 2;
        assertTrue(encoded.length < v1Bytes * 55 / 100,
            "v2 bulk is " + encoded.length + " bytes, v1 was " + v1Bytes + " bytes");
        assertTrue(encoded.length < 250_000,
            "v2 full-height bulk should stay under 250KB, was " + encoded.length);
        assertEquals(slice.contentHash(), roundTripHash(encoded));
    }

    @Test
    void twentyBlockDiffBatchEncodesWellUnderV1Size() throws IOException {
        String stone = "minecraft:stone";
        String fence = "minecraft:oak_fence[east=false,north=true,south=false,waterlogged=false,west=false]";
        String stairs = "minecraft:oak_stairs[facing=east,half=bottom,shape=straight,waterlogged=false]";
        String[] states = {stone, fence, stairs};
        List<BlockChange> blocks = new ArrayList<>(20);
        for (int i = 0; i < 20; i++) {
            blocks.add(new BlockChange(BlockChange.pack(i & 0xF, 64 + i, (i * 3) & 0xF), states[i % 3], BlockChange.FLAG_NONE));
        }
        ChunkDiffBatch batch = new ChunkDiffBatch(7L, 12L, blocks, List.<LightDiff>of(), List.<BlockEntityDiff>of());
        byte[] encoded = batch.encode();

        long v1Bytes = 8L + 1L + 1L + 1L + 1L;
        for (BlockChange change : blocks) {
            v1Bytes += 3L + 2L + change.state().getBytes(StandardCharsets.UTF_8).length + 1L;
        }
        assertTrue(encoded.length < v1Bytes * 60 / 100,
            "v2 diff batch is " + encoded.length + " bytes, v1 was " + v1Bytes + " bytes");
        assertEquals(20, ChunkDiffBatch.decode(encoded).blocks().size());
    }

    private static long paletteUtfBytes(List<String> palette) {
        long total = 0L;
        for (String entry : palette) {
            total += 2L + entry.getBytes(StandardCharsets.UTF_8).length;
        }
        return total;
    }

    private static long roundTripHash(byte[] payload) throws IOException {
        return ViewSlice.read(new java.io.DataInputStream(new java.io.ByteArrayInputStream(payload))).contentHash();
    }
}
