package art.arcane.wormholes.network.replication;

public record LightDiff(int sectionY, byte lightType, byte[] data) {
    public static final byte TYPE_BLOCKLIGHT = 0;
    public static final byte TYPE_SKYLIGHT = 1;
    public static final int DATA_LENGTH = 2048;
}
