package art.arcane.wormholes.network.replication;

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

public final class ChunkBulkBuilder {
    private final Map<BlockData, String> blockDataStrings;

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
        short[] biomes = new short[cells];

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
                    Biome biome = snapshot.getBiome(lx, y, lz);
                    String biomeString = biome.getKey().asString();
                    Integer biomePaletteIndex = biomePaletteLookup.get(biomeString);
                    if (biomePaletteIndex == null) {
                        biomePaletteIndex = biomePalette.size();
                        biomePalette.add(biomeString);
                        biomePaletteLookup.put(biomeString, biomePaletteIndex);
                    }
                    biomes[cell] = (short) biomePaletteIndex.intValue();
                    int sky = snapshot.getBlockSkyLight(lx, y, lz);
                    int emitted = snapshot.getBlockEmittedLight(lx, y, lz);
                    light[cell] = (byte) (((sky & 0x0F) << 4) | (emitted & 0x0F));
                    cell++;
                }
            }
        }

        return new ViewSlice(minX, minY, minZ, sizeX, sizeY, sizeZ, palette, indices, light, biomePalette, biomes);
    }

    public static byte[] encodeSliceBytes(ViewSlice slice) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream(1024);
        DataOutputStream out = new DataOutputStream(buffer);
        slice.write(out);
        out.flush();
        return buffer.toByteArray();
    }
}
