package art.arcane.wormholes.network.replication;

public record BlockChange(int packedXyz, String state, byte flags) {
    public static final byte FLAG_NONE = 0;
    public static final byte FLAG_BLOCK_ENTITY_FOLLOWS = 1;

    public static int pack(int lx, int ly, int lz) {
        int x = lx & 0xF;
        int z = lz & 0xF;
        int y = ly & 0xFFFF;
        return (y << 8) | (z << 4) | x;
    }

    public static int unpackX(int packedXyz) {
        return packedXyz & 0xF;
    }

    public static int unpackZ(int packedXyz) {
        return (packedXyz >>> 4) & 0xF;
    }

    public static int unpackY(int packedXyz) {
        return (short) ((packedXyz >>> 8) & 0xFFFF);
    }
}
