package art.arcane.wormholes.network.view;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

public record ViewSlice(int minX, int minY, int minZ, int sizeX, int sizeY, int sizeZ, List<String> palette, short[] indices, byte[] light) {
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

    public long contentHash() {
        long hash = 0xcbf29ce484222325L;
        for (String entry : palette) {
            for (int i = 0; i < entry.length(); i++) {
                hash = (hash ^ entry.charAt(i)) * 0x100000001b3L;
            }
            hash = (hash ^ 0xFF) * 0x100000001b3L;
        }
        for (short index : indices) {
            hash = (hash ^ (index & 0xFFFF)) * 0x100000001b3L;
        }
        for (byte level : light) {
            hash = (hash ^ (level & 0xFF)) * 0x100000001b3L;
        }
        return hash;
    }

    public void write(DataOutputStream out) throws IOException {
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
        for (short index : indices) {
            out.writeShort(index);
        }
        out.write(light);
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
        short[] indices = new short[(int) cells];
        for (int i = 0; i < indices.length; i++) {
            indices[i] = in.readShort();
        }
        byte[] light = new byte[(int) cells];
        in.readFully(light);
        return new ViewSlice(minX, minY, minZ, sizeX, sizeY, sizeZ, palette, indices, light);
    }
}
