package art.arcane.wormholes.network.replication;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;

public record ChunkResyncRequest(long chunkKey, long expectedSequence) {
    public void writeTo(DataOutputStream out) throws IOException {
        out.writeLong(chunkKey);
        ReplicationVarint.writeULong(out, expectedSequence);
    }

    public static ChunkResyncRequest read(DataInputStream in) throws IOException {
        long chunkKey = in.readLong();
        long expectedSequence = ReplicationVarint.readULong(in);
        return new ChunkResyncRequest(chunkKey, expectedSequence);
    }
}
