package art.arcane.wormholes.network.replication;

import art.arcane.wormholes.network.view.ViewSlice;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChunkBulkBuilderBiomeGridTest {
    private static final int BIOME_SPLIT_Y = 72;

    @Test
    void buildBiomeGridWithUnalignedMinYSamplesWorldAlignedQuarts() {
        int minX = 0;
        int minY = 62;
        int minZ = 0;
        int sizeX = 16;
        int sizeY = 24;
        int sizeZ = 16;
        List<int[]> sampledCoordinates = new ArrayList<>(128);
        short[] grid = ChunkBulkBuilder.buildBiomeGrid(minX, minY, minZ, sizeX, sizeY, sizeZ, (localX, worldY, localZ) -> {
            sampledCoordinates.add(new int[]{localX, worldY, localZ});
            return (short) (worldY < BIOME_SPLIT_Y ? 0 : 1);
        });
        int gridLength = ViewSlice.biomeGridSpan(minX, sizeX) * ViewSlice.biomeGridSpan(minY, sizeY) * ViewSlice.biomeGridSpan(minZ, sizeZ);
        assertEquals(gridLength, grid.length);
        for (int[] coordinate : sampledCoordinates) {
            assertTrue(coordinate[0] >= 0 && coordinate[0] <= 15);
            assertTrue(coordinate[2] >= 0 && coordinate[2] <= 15);
            assertTrue(coordinate[1] >= minY && coordinate[1] < minY + sizeY, "sample y " + coordinate[1] + " outside slice");
            assertTrue(coordinate[1] == minY || (coordinate[1] & 3) == 0, "sample y " + coordinate[1] + " not quart-aligned nor clamped to minY");
        }

        ViewSlice slice = new ViewSlice(minX, minY, minZ, sizeX, sizeY, sizeZ,
            List.of("minecraft:air"), new short[sizeX * sizeY * sizeZ], new byte[sizeX * sizeY * sizeZ],
            List.of("minecraft:plains", "minecraft:desert"), grid);
        for (int y = minY; y < minY + sizeY; y++) {
            short expected = (short) (Math.max(minY, (y >> 2) << 2) < BIOME_SPLIT_Y ? 0 : 1);
            for (int z = minZ; z < minZ + sizeZ; z++) {
                for (int x = minX; x < minX + sizeX; x++) {
                    assertEquals(expected, grid[slice.biomeGridIndex(x, y, z)], "cell " + x + "," + y + "," + z);
                }
            }
        }
    }

    @Test
    void buildBiomeGridQueriesResolverOncePerQuart() {
        AtomicInteger resolverCalls = new AtomicInteger();
        int minX = -16;
        int minY = 62;
        int minZ = 32;
        int sizeX = 16;
        int sizeY = 24;
        int sizeZ = 16;
        short[] grid = ChunkBulkBuilder.buildBiomeGrid(minX, minY, minZ, sizeX, sizeY, sizeZ, (localX, worldY, localZ) -> {
            resolverCalls.incrementAndGet();
            return (short) 0;
        });
        int gridLength = ViewSlice.biomeGridSpan(minX, sizeX) * ViewSlice.biomeGridSpan(minY, sizeY) * ViewSlice.biomeGridSpan(minZ, sizeZ);
        assertEquals(gridLength, grid.length);
        assertEquals(gridLength, resolverCalls.get());
        assertTrue(resolverCalls.get() < sizeX * sizeY * sizeZ / 32,
            "quart sampling must be far below per-cell sampling, was " + resolverCalls.get());
    }

    @Test
    void buildBiomeGridWithNegativeUnalignedBoundsStaysDense() {
        int minX = -7;
        int minY = -62;
        int minZ = 13;
        int sizeX = 16;
        int sizeY = 24;
        int sizeZ = 16;
        short[] grid = ChunkBulkBuilder.buildBiomeGrid(minX, minY, minZ, sizeX, sizeY, sizeZ, (localX, worldY, localZ) -> (short) 1);
        int gridLength = ViewSlice.biomeGridSpan(minX, sizeX) * ViewSlice.biomeGridSpan(minY, sizeY) * ViewSlice.biomeGridSpan(minZ, sizeZ);
        assertEquals(gridLength, grid.length);
        for (short value : grid) {
            assertEquals(1, value);
        }
    }
}
