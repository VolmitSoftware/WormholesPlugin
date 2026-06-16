package art.arcane.wormholes.network.replication;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

public record ChunkDiffBatch(long chunkKey, long sequence, List<BlockChange> blocks, List<LightDiff> lights, List<BlockEntityDiff> entities) {
    public static final int MAX_BLOCKS = 65_536;
    public static final int MAX_LIGHTS = 256;
    public static final int MAX_ENTITIES = 4_096;
    public static final int MAX_BLOCK_ENTITY_NBT_BYTES = 64 * 1024;

    public byte[] encode() throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream(256 + blocks.size() * 6);
        DataOutputStream out = new DataOutputStream(buffer);
        writeTo(out);
        out.flush();
        return buffer.toByteArray();
    }

    public void writeTo(DataOutputStream out) throws IOException {
        out.writeLong(chunkKey);
        ReplicationVarint.writeULong(out, sequence);
        ReplicationVarint.writeUInt(out, blocks.size());
        for (BlockChange change : blocks) {
            ReplicationVarint.writeUInt(out, change.packedXyz());
            out.writeUTF(change.state());
            out.writeByte(change.flags());
        }
        ReplicationVarint.writeUInt(out, lights.size());
        for (LightDiff diff : lights) {
            out.writeInt(diff.sectionY());
            out.writeByte(diff.lightType());
            byte[] data = diff.data();
            ReplicationVarint.writeUInt(out, data.length);
            out.write(data);
        }
        ReplicationVarint.writeUInt(out, entities.size());
        for (BlockEntityDiff diff : entities) {
            ReplicationVarint.writeUInt(out, diff.packedXyz());
            byte[] nbt = diff.nbt();
            ReplicationVarint.writeUInt(out, nbt.length);
            out.write(nbt);
        }
    }

    public static ChunkDiffBatch decode(byte[] bytes) throws IOException {
        return read(new DataInputStream(new ByteArrayInputStream(bytes)));
    }

    public static ChunkDiffBatch read(DataInputStream in) throws IOException {
        long chunkKey = in.readLong();
        long sequence = ReplicationVarint.readULong(in);
        int blockCount = ReplicationVarint.readUInt(in);
        if (blockCount < 0 || blockCount > MAX_BLOCKS) {
            throw new IOException("Invalid block diff count: " + blockCount);
        }
        List<BlockChange> blocks = new ArrayList<>(blockCount);
        for (int i = 0; i < blockCount; i++) {
            int packedXyz = ReplicationVarint.readUInt(in);
            String state = in.readUTF();
            byte flags = in.readByte();
            blocks.add(new BlockChange(packedXyz, state, flags));
        }
        int lightCount = ReplicationVarint.readUInt(in);
        if (lightCount < 0 || lightCount > MAX_LIGHTS) {
            throw new IOException("Invalid light diff count: " + lightCount);
        }
        List<LightDiff> lights = new ArrayList<>(lightCount);
        for (int i = 0; i < lightCount; i++) {
            int sectionY = in.readInt();
            byte lightType = in.readByte();
            int dataLength = ReplicationVarint.readUInt(in);
            if (dataLength < 0 || dataLength > LightDiff.DATA_LENGTH * 2) {
                throw new IOException("Invalid light diff length: " + dataLength);
            }
            byte[] data = new byte[dataLength];
            in.readFully(data);
            lights.add(new LightDiff(sectionY, lightType, data));
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
