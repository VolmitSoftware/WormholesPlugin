package art.arcane.wormholes.network.replication;

public final class XxHash64 {
    private static final long PRIME64_1 = 0x9E3779B185EBCA87L;
    private static final long PRIME64_2 = 0xC2B2AE3D27D4EB4FL;
    private static final long PRIME64_3 = 0x165667B19E3779F9L;
    private static final long PRIME64_4 = 0x85EBCA77C2B2AE63L;
    private static final long PRIME64_5 = 0x27D4EB2F165667C5L;

    private XxHash64() {
    }

    public static long hash(byte[] data) {
        return hash(data, 0, data.length, 0L);
    }

    public static long hash(byte[] data, int offset, int length, long seed) {
        if (offset < 0 || length < 0 || offset + length > data.length) {
            throw new IndexOutOfBoundsException("Invalid offset/length for hash");
        }
        long hash;
        int end = offset + length;
        int cursor = offset;
        int limit = end - 32;

        if (length >= 32) {
            long v1 = seed + PRIME64_1 + PRIME64_2;
            long v2 = seed + PRIME64_2;
            long v3 = seed;
            long v4 = seed - PRIME64_1;
            while (cursor <= limit) {
                v1 = round(v1, readLong(data, cursor));
                cursor += 8;
                v2 = round(v2, readLong(data, cursor));
                cursor += 8;
                v3 = round(v3, readLong(data, cursor));
                cursor += 8;
                v4 = round(v4, readLong(data, cursor));
                cursor += 8;
            }
            hash = Long.rotateLeft(v1, 1) + Long.rotateLeft(v2, 7) + Long.rotateLeft(v3, 12) + Long.rotateLeft(v4, 18);
            hash = mergeRound(hash, v1);
            hash = mergeRound(hash, v2);
            hash = mergeRound(hash, v3);
            hash = mergeRound(hash, v4);
        } else {
            hash = seed + PRIME64_5;
        }
        hash += length;

        while (cursor + 8 <= end) {
            long k1 = round(0L, readLong(data, cursor));
            hash ^= k1;
            hash = Long.rotateLeft(hash, 27) * PRIME64_1 + PRIME64_4;
            cursor += 8;
        }
        if (cursor + 4 <= end) {
            hash ^= (readInt(data, cursor) & 0xFFFFFFFFL) * PRIME64_1;
            hash = Long.rotateLeft(hash, 23) * PRIME64_2 + PRIME64_3;
            cursor += 4;
        }
        while (cursor < end) {
            hash ^= (data[cursor] & 0xFFL) * PRIME64_5;
            hash = Long.rotateLeft(hash, 11) * PRIME64_1;
            cursor++;
        }
        hash ^= hash >>> 33;
        hash *= PRIME64_2;
        hash ^= hash >>> 29;
        hash *= PRIME64_3;
        hash ^= hash >>> 32;
        return hash;
    }

    private static long round(long acc, long input) {
        long next = acc + input * PRIME64_2;
        next = Long.rotateLeft(next, 31);
        return next * PRIME64_1;
    }

    private static long mergeRound(long acc, long value) {
        long next = acc ^ round(0L, value);
        return next * PRIME64_1 + PRIME64_4;
    }

    private static long readLong(byte[] data, int offset) {
        return (data[offset] & 0xFFL)
            | ((data[offset + 1] & 0xFFL) << 8)
            | ((data[offset + 2] & 0xFFL) << 16)
            | ((data[offset + 3] & 0xFFL) << 24)
            | ((data[offset + 4] & 0xFFL) << 32)
            | ((data[offset + 5] & 0xFFL) << 40)
            | ((data[offset + 6] & 0xFFL) << 48)
            | ((data[offset + 7] & 0xFFL) << 56);
    }

    private static int readInt(byte[] data, int offset) {
        return (data[offset] & 0xFF)
            | ((data[offset + 1] & 0xFF) << 8)
            | ((data[offset + 2] & 0xFF) << 16)
            | ((data[offset + 3] & 0xFF) << 24);
    }
}
