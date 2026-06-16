package art.arcane.wormholes.network.replication;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;

public final class ReplicationVarint {
    private ReplicationVarint() {
    }

    public static void writeUInt(DataOutputStream out, int value) throws IOException {
        int remaining = value;
        while ((remaining & ~0x7F) != 0) {
            out.writeByte((remaining & 0x7F) | 0x80);
            remaining >>>= 7;
        }
        out.writeByte(remaining & 0x7F);
    }

    public static int readUInt(DataInputStream in) throws IOException {
        int result = 0;
        int shift = 0;
        while (true) {
            int read = in.readUnsignedByte();
            result |= (read & 0x7F) << shift;
            if ((read & 0x80) == 0) {
                return result;
            }
            shift += 7;
            if (shift >= 35) {
                throw new IOException("Varint too long");
            }
        }
    }

    public static void writeULong(DataOutputStream out, long value) throws IOException {
        long remaining = value;
        while ((remaining & ~0x7FL) != 0L) {
            out.writeByte((int) ((remaining & 0x7FL) | 0x80L));
            remaining >>>= 7;
        }
        out.writeByte((int) (remaining & 0x7FL));
    }

    public static long readULong(DataInputStream in) throws IOException {
        long result = 0L;
        int shift = 0;
        while (true) {
            int read = in.readUnsignedByte();
            result |= ((long) (read & 0x7F)) << shift;
            if ((read & 0x80) == 0) {
                return result;
            }
            shift += 7;
            if (shift >= 70) {
                throw new IOException("Varlong too long");
            }
        }
    }
}
