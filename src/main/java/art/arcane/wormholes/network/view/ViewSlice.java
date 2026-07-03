package art.arcane.wormholes.network.view;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

public record ViewSlice(int minX, int minY, int minZ, int sizeX, int sizeY, int sizeZ, List<String> palette, short[] indices, byte[] light, List<String> biomePalette, short[] biomes) {
    public static final int MAX_PALETTE_ENTRIES = 4096;
    public static final int MAX_CELLS = 16 * 512 * 16;

    public int cellCount() {
        return sizeX * sizeY * sizeZ;
    }

    public int cellIndex(int x, int y, int z) {
        return (((y - minY) * sizeZ + (z - minZ)) * sizeX) + (x - minX);
    }

    public boolean contains(int x, int y, int z) {
        return x >= minX && x < minX + sizeX && y >= minY && y < minY + sizeY && z >= minZ && z < minZ + sizeZ;
    }

    public long columnKey() {
        return columnKey(minX >> 4, minZ >> 4);
    }

    public static long columnKey(int chunkX, int chunkZ) {
        return (((long) chunkX) << 32) | (((long) chunkZ) & 0xFFFFFFFFL);
    }

    public static int biomeGridSpan(int min, int size) {
        return ((min + size - 1) >> 2) - (min >> 2) + 1;
    }

    public int biomeGridSizeX() {
        return biomeGridSpan(minX, sizeX);
    }

    public int biomeGridSizeY() {
        return biomeGridSpan(minY, sizeY);
    }

    public int biomeGridSizeZ() {
        return biomeGridSpan(minZ, sizeZ);
    }

    public int biomeGridLength() {
        return biomeGridSizeX() * biomeGridSizeY() * biomeGridSizeZ();
    }

    public int biomeGridIndex(int x, int y, int z) {
        return ((((y >> 2) - (minY >> 2)) * biomeGridSizeZ() + ((z >> 2) - (minZ >> 2))) * biomeGridSizeX()) + ((x >> 2) - (minX >> 2));
    }

    /**
     * Palette-order- and light-independent hash of the slice's logical content (blocks + biomes).
     * Each cell contributes the hash of the actual block/biome it resolves to, so two slices with
     * identical per-cell content hash equal regardless of how each server ordered its palette or
     * what the current lighting is. This is what the cross-server hash probe compares, so a resync
     * only fires on a real block/biome divergence -- not on palette re-ordering after a diff, and
     * not on day/night light changes.
     */
    public long contentHash() {
        long h = 1469598103934665603L;
        int paletteSize = palette.size();
        int[] paletteHashes = new int[paletteSize];
        for (int i = 0; i < paletteSize; i++) {
            paletteHashes[i] = palette.get(i).hashCode();
        }
        for (int i = 0; i < indices.length; i++) {
            int idx = indices[i] & 0xFFFF;
            h ^= (idx < paletteSize ? paletteHashes[idx] : 0) & 0xFFFFFFFFL;
            h *= 1099511628211L;
        }
        int biomeSize = biomePalette.size();
        int[] biomeHashes = new int[biomeSize];
        for (int i = 0; i < biomeSize; i++) {
            biomeHashes[i] = biomePalette.get(i).hashCode();
        }
        for (int i = 0; i < biomes.length; i++) {
            int idx = biomes[i] & 0xFFFF;
            h ^= (idx < biomeSize ? biomeHashes[idx] : 0) & 0xFFFFFFFFL;
            h *= 1099511628211L;
        }
        return h;
    }

    public void write(DataOutputStream out) throws IOException {
        if (biomes.length != biomeGridLength()) {
            throw new IOException("View slice biome grid length mismatch: " + biomes.length + " != " + biomeGridLength());
        }
        out.writeInt(minX);
        out.writeInt(minY);
        out.writeInt(minZ);
        out.writeShort(sizeX);
        out.writeShort(sizeY);
        out.writeShort(sizeZ);
        out.writeShort(palette.size());
        for (String entry : palette) {
            out.writeUTF(entry);
        }
        writePackedIndices(out, indices, palette.size());
        out.write(light);
        out.writeShort(biomePalette.size());
        for (String entry : biomePalette) {
            out.writeUTF(entry);
        }
        writePackedIndices(out, biomes, biomePalette.size());
    }

    private static void writePackedIndices(DataOutputStream out, short[] values, int paletteSize) throws IOException {
        int width = paletteSize <= 256 ? 1 : 2;
        out.writeByte(width);
        if (width == 1) {
            byte[] raw = new byte[values.length];
            for (int i = 0; i < values.length; i++) {
                raw[i] = (byte) values[i];
            }
            out.write(raw);
            return;
        }
        byte[] raw = new byte[values.length * 2];
        for (int i = 0; i < values.length; i++) {
            raw[i * 2] = (byte) (values[i] >> 8);
            raw[i * 2 + 1] = (byte) values[i];
        }
        out.write(raw);
    }

    private static short[] readPackedIndices(DataInputStream in, int count) throws IOException {
        int width = in.readByte();
        if (width != 1 && width != 2) {
            throw new IOException("Invalid view slice index width: " + width);
        }
        byte[] raw = new byte[count * width];
        in.readFully(raw);
        short[] values = new short[count];
        if (width == 1) {
            for (int i = 0; i < count; i++) {
                values[i] = (short) (raw[i] & 0xFF);
            }
            return values;
        }
        for (int i = 0; i < count; i++) {
            values[i] = (short) (((raw[i * 2] & 0xFF) << 8) | (raw[i * 2 + 1] & 0xFF));
        }
        return values;
    }

    public static ViewSlice read(DataInputStream in) throws IOException {
        int minX = in.readInt();
        int minY = in.readInt();
        int minZ = in.readInt();
        int sizeX = in.readUnsignedShort();
        int sizeY = in.readUnsignedShort();
        int sizeZ = in.readUnsignedShort();
        long cells = (long) sizeX * sizeY * sizeZ;
        if (cells <= 0 || cells > MAX_CELLS) {
            throw new IOException("Invalid view slice cell count: " + cells);
        }
        int paletteSize = in.readUnsignedShort();
        if (paletteSize > MAX_PALETTE_ENTRIES) {
            throw new IOException("View slice palette too large: " + paletteSize);
        }
        List<String> palette = new ArrayList<>(paletteSize);
        for (int i = 0; i < paletteSize; i++) {
            palette.add(in.readUTF());
        }
        short[] indices = readPackedIndices(in, (int) cells);
        byte[] light = new byte[(int) cells];
        in.readFully(light);
        int biomePaletteSize = in.readUnsignedShort();
        if (biomePaletteSize > MAX_PALETTE_ENTRIES) {
            throw new IOException("View slice biome palette too large: " + biomePaletteSize);
        }
        List<String> biomePalette = new ArrayList<>(biomePaletteSize);
        for (int i = 0; i < biomePaletteSize; i++) {
            biomePalette.add(in.readUTF());
        }
        int gridLength = biomeGridSpan(minX, sizeX) * biomeGridSpan(minY, sizeY) * biomeGridSpan(minZ, sizeZ);
        short[] biomes = readPackedIndices(in, gridLength);
        return new ViewSlice(minX, minY, minZ, sizeX, sizeY, sizeZ, palette, indices, light, biomePalette, biomes);
    }
}
