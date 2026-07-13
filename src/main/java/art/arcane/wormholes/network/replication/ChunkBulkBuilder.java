package art.arcane.wormholes.network.replication;

import art.arcane.wormholes.platform.WormholesPlatform;
import art.arcane.wormholes.network.view.ViewBox;
import art.arcane.wormholes.network.view.ViewSlice;

import org.bukkit.ChunkSnapshot;
import org.bukkit.block.Biome;
import org.bukkit.block.data.BlockData;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class ChunkBulkBuilder {
    private final Map<BlockData, String> blockDataStrings;
    private final Map<Biome, String> biomeStrings = new ConcurrentHashMap<>();

    public ChunkBulkBuilder(Map<BlockData, String> blockDataStrings) {
        this.blockDataStrings = blockDataStrings;
    }

    public ViewSlice buildSlice(ViewBox box, int chunkX, int chunkZ, ChunkSnapshot snapshot) {
        int minX = Math.max(box.minX(), chunkX << 4);
        int maxX = Math.min(box.maxX(), (chunkX << 4) + 15);
        int minZ = Math.max(box.minZ(), chunkZ << 4);
        int maxZ = Math.min(box.maxZ(), (chunkZ << 4) + 15);
        if (minX > maxX || minZ > maxZ) {
            return null;
        }
        int minY = box.minY();
        int maxY = box.maxY();
        int sizeX = maxX - minX + 1;
        int sizeY = maxY - minY + 1;
        int sizeZ = maxZ - minZ + 1;
        int cells = sizeX * sizeY * sizeZ;

        List<String> palette = new ArrayList<>(16);
        HashMap<String, Integer> paletteLookup = new HashMap<>(32);
        List<String> biomePalette = new ArrayList<>(8);
        HashMap<String, Integer> biomePaletteLookup = new HashMap<>(16);
        short[] indices = new short[cells];
        byte[] light = new byte[cells];

        int cell = 0;
        for (int y = minY; y <= maxY; y++) {
            for (int z = minZ; z <= maxZ; z++) {
                int lz = z & 0xF;
                for (int x = minX; x <= maxX; x++) {
                    int lx = x & 0xF;
                    BlockData data = snapshot.getBlockData(lx, y, lz);
                    String stateString = blockDataStrings.computeIfAbsent(data, BlockData::getAsString);
                    Integer paletteIndex = paletteLookup.get(stateString);
                    if (paletteIndex == null) {
                        paletteIndex = palette.size();
                        palette.add(stateString);
                        paletteLookup.put(stateString, paletteIndex);
                    }
                    indices[cell] = (short) paletteIndex.intValue();
                    int sky = snapshot.getBlockSkyLight(lx, y, lz);
                    int emitted = snapshot.getBlockEmittedLight(lx, y, lz);
                    light[cell] = (byte) (((sky & 0x0F) << 4) | (emitted & 0x0F));
                    cell++;
                }
            }
        }

        short[] biomes = buildBiomeGrid(minX, minY, minZ, sizeX, sizeY, sizeZ, (localX, worldY, localZ) -> {
            Biome biome = snapshot.getBiome(localX, worldY, localZ);
            String biomeString = biomeStrings.computeIfAbsent(biome, b -> WormholesPlatform.keyString(b.getKey()));
            Integer biomePaletteIndex = biomePaletteLookup.get(biomeString);
            if (biomePaletteIndex == null) {
                biomePaletteIndex = biomePalette.size();
                biomePalette.add(biomeString);
                biomePaletteLookup.put(biomeString, biomePaletteIndex);
            }
            return (short) biomePaletteIndex.intValue();
        });

        return new ViewSlice(minX, minY, minZ, sizeX, sizeY, sizeZ, palette, indices, light, biomePalette, biomes);
    }

    @FunctionalInterface
    interface QuartBiomeResolver {
        short paletteIndexFor(int localX, int worldY, int localZ);
    }

    static short[] buildBiomeGrid(int minX, int minY, int minZ, int sizeX, int sizeY, int sizeZ, QuartBiomeResolver resolver) {
        int gridSizeX = ViewSlice.biomeGridSpan(minX, sizeX);
        int gridSizeY = ViewSlice.biomeGridSpan(minY, sizeY);
        int gridSizeZ = ViewSlice.biomeGridSpan(minZ, sizeZ);
        short[] biomes = new short[gridSizeX * gridSizeY * gridSizeZ];
        int grid = 0;
        for (int gy = 0; gy < gridSizeY; gy++) {
            int sy = Math.max(minY, ((minY >> 2) + gy) << 2);
            for (int gz = 0; gz < gridSizeZ; gz++) {
                int sz = Math.max(minZ, ((minZ >> 2) + gz) << 2);
                for (int gx = 0; gx < gridSizeX; gx++) {
                    int sx = Math.max(minX, ((minX >> 2) + gx) << 2);
                    biomes[grid] = resolver.paletteIndexFor(sx & 0xF, sy, sz & 0xF);
                    grid++;
                }
            }
        }
        return biomes;
    }

    public static byte[] encodeSliceBytes(ViewSlice slice) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream(slice.cellCount() * 3 + 2048);
        DataOutputStream out = new DataOutputStream(buffer);
        slice.write(out);
        out.flush();
        return buffer.toByteArray();
    }
}
