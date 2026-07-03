package art.arcane.wormholes.network.replication;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public record ChunkDiffBatch(long chunkKey, long sequence, List<BlockChange> blocks, List<LightDiff> lights, List<BlockEntityDiff> entities) {
    public static final int MAX_BLOCKS = 65_536;
    public static final int MAX_LIGHTS = 256;
    public static final int MAX_ENTITIES = 4_096;
    public static final int MAX_BLOCK_ENTITY_NBT_BYTES = 64 * 1024;
    private static final int MAX_SPARSE_CELLS = 4_096;

    public byte[] encode() throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream(256 + blocks.size() * 8 + lights.size() * (LightDiff.DATA_LENGTH + 8));
        DataOutputStream out = new DataOutputStream(buffer);
        writeTo(out);
        out.flush();
        return buffer.toByteArray();
    }

    public void writeTo(DataOutputStream out) throws IOException {
        out.writeLong(chunkKey);
        ReplicationVarint.writeULong(out, sequence);
        Map<String, Integer> table = new LinkedHashMap<>(Math.max(4, blocks.size()));
        for (BlockChange change : blocks) {
            table.computeIfAbsent(change.state(), ignored -> table.size());
        }
        ReplicationVarint.writeUInt(out, table.size());
        for (String state : table.keySet()) {
            out.writeUTF(state);
        }
        ReplicationVarint.writeUInt(out, blocks.size());
        for (BlockChange change : blocks) {
            ReplicationVarint.writeUInt(out, change.packedXyz());
            ReplicationVarint.writeUInt(out, table.get(change.state()).intValue());
            out.writeByte(change.flags());
        }
        ReplicationVarint.writeUInt(out, lights.size());
        for (LightDiff diff : lights) {
            writeLight(out, diff);
        }
        ReplicationVarint.writeUInt(out, entities.size());
        for (BlockEntityDiff diff : entities) {
            ReplicationVarint.writeUInt(out, diff.packedXyz());
            byte[] nbt = diff.nbt();
            ReplicationVarint.writeUInt(out, nbt.length);
            out.write(nbt);
        }
    }

    private static void writeLight(DataOutputStream out, LightDiff diff) throws IOException {
        out.writeInt(diff.sectionY());
        int[] cells = diff.sparseCells();
        if (cells == null) {
            out.writeByte(diff.lightType());
            byte[] data = diff.data();
            ReplicationVarint.writeUInt(out, data.length);
            out.write(data);
            return;
        }
        out.writeByte(diff.lightType() | 0x80);
        ReplicationVarint.writeUInt(out, cells.length);
        ReplicationVarint.writeUInt(out, cells[0]);
        for (int i = 1; i < cells.length; i++) {
            ReplicationVarint.writeUInt(out, cells[i] - cells[i - 1]);
        }
        byte[] packed = new byte[(cells.length + 1) >> 1];
        byte[] data = diff.data();
        byte[] levels = diff.sparseLevels();
        for (int i = 0; i < cells.length; i++) {
            int level = data != null ? nibbleAt(data, cells[i]) : nibbleAt(levels, i);
            if ((i & 1) == 0) {
                packed[i >> 1] = (byte) ((packed[i >> 1] & 0xF0) | level);
            } else {
                packed[i >> 1] = (byte) ((packed[i >> 1] & 0x0F) | (level << 4));
            }
        }
        out.write(packed);
    }

    public static int nibbleAt(byte[] packed, int index) {
        return (index & 1) == 0 ? (packed[index >> 1] & 0x0F) : ((packed[index >> 1] >> 4) & 0x0F);
    }

    private static LightDiff readLight(DataInputStream in) throws IOException {
        int sectionY = in.readInt();
        int rawType = in.readByte() & 0xFF;
        boolean sparse = (rawType & 0x80) != 0;
        byte lightType = (byte) (rawType & 0x7F);
        if (!sparse) {
            int dataLength = ReplicationVarint.readUInt(in);
            if (dataLength < 0 || dataLength > LightDiff.DATA_LENGTH * 2) {
                throw new IOException("Invalid light diff length: " + dataLength);
            }
            byte[] data = new byte[dataLength];
            in.readFully(data);
            return LightDiff.full(sectionY, lightType, data);
        }
        int count = ReplicationVarint.readUInt(in);
        if (count < 1 || count > MAX_SPARSE_CELLS) {
            throw new IOException("Invalid sparse light cell count: " + count);
        }
        int[] cells = new int[count];
        int cell = ReplicationVarint.readUInt(in);
        if (cell < 0 || cell >= MAX_SPARSE_CELLS) {
            throw new IOException("Invalid sparse light cell index: " + cell);
        }
        cells[0] = cell;
        for (int i = 1; i < count; i++) {
            int delta = ReplicationVarint.readUInt(in);
            if (delta < 1) {
                throw new IOException("Non-ascending sparse light cell delta: " + delta);
            }
            cell += delta;
            if (cell >= MAX_SPARSE_CELLS) {
                throw new IOException("Sparse light cell index out of range: " + cell);
            }
            cells[i] = cell;
        }
        byte[] levels = new byte[(count + 1) >> 1];
        in.readFully(levels);
        return LightDiff.sparse(sectionY, lightType, cells, levels);
    }

    public static ChunkDiffBatch decode(byte[] bytes) throws IOException {
        return read(new DataInputStream(new ByteArrayInputStream(bytes)));
    }

    public static ChunkDiffBatch read(DataInputStream in) throws IOException {
        long chunkKey = in.readLong();
        long sequence = ReplicationVarint.readULong(in);
        int tableSize = ReplicationVarint.readUInt(in);
        if (tableSize < 0 || tableSize > MAX_BLOCKS) {
            throw new IOException("Invalid block state table size: " + tableSize);
        }
        String[] table = new String[tableSize];
        for (int i = 0; i < tableSize; i++) {
            table[i] = in.readUTF();
        }
        int blockCount = ReplicationVarint.readUInt(in);
        if (blockCount < 0 || blockCount > MAX_BLOCKS) {
            throw new IOException("Invalid block diff count: " + blockCount);
        }
        List<BlockChange> blocks = new ArrayList<>(blockCount);
        for (int i = 0; i < blockCount; i++) {
            int packedXyz = ReplicationVarint.readUInt(in);
            int stateIndex = ReplicationVarint.readUInt(in);
            if (stateIndex < 0 || stateIndex >= tableSize) {
                throw new IOException("Invalid block state table index: " + stateIndex);
            }
            byte flags = in.readByte();
            blocks.add(new BlockChange(packedXyz, table[stateIndex], flags));
        }
        int lightCount = ReplicationVarint.readUInt(in);
        if (lightCount < 0 || lightCount > MAX_LIGHTS) {
            throw new IOException("Invalid light diff count: " + lightCount);
        }
        List<LightDiff> lights = new ArrayList<>(lightCount);
        for (int i = 0; i < lightCount; i++) {
            lights.add(readLight(in));
        }
        int entityCount = ReplicationVarint.readUInt(in);
        if (entityCount < 0 || entityCount > MAX_ENTITIES) {
            throw new IOException("Invalid block entity diff count: " + entityCount);
        }
        List<BlockEntityDiff> entities = new ArrayList<>(entityCount);
        for (int i = 0; i < entityCount; i++) {
            int packedXyz = ReplicationVarint.readUInt(in);
            int nbtLength = ReplicationVarint.readUInt(in);
            if (nbtLength < 0 || nbtLength > MAX_BLOCK_ENTITY_NBT_BYTES) {
                throw new IOException("Invalid block entity NBT length: " + nbtLength);
            }
            byte[] nbt = new byte[nbtLength];
            in.readFully(nbt);
            entities.add(new BlockEntityDiff(packedXyz, nbt));
        }
        return new ChunkDiffBatch(chunkKey, sequence, blocks, lights, entities);
    }

    public boolean isEmpty() {
        return blocks.isEmpty() && lights.isEmpty() && entities.isEmpty();
    }
}
