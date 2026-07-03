package art.arcane.wormholes.network.replication;

public record LightDiff(int sectionY, byte lightType, byte[] data, int[] sparseCells, byte[] sparseLevels) {
    public static final byte TYPE_BLOCKLIGHT = 0;
    public static final byte TYPE_SKYLIGHT = 1;
    public static final int DATA_LENGTH = 2048;
    public static final int SPARSE_MAX_CELLS = 1024;

    public static LightDiff full(int sectionY, byte lightType, byte[] data) {
        return new LightDiff(sectionY, lightType, data, null, null);
    }

    public static LightDiff pending(int sectionY, byte lightType, byte[] data, int[] sparseCells) {
        return new LightDiff(sectionY, lightType, data, sparseCells, null);
    }

    public static LightDiff sparse(int sectionY, byte lightType, int[] sparseCells, byte[] sparseLevels) {
        return new LightDiff(sectionY, lightType, null, sparseCells, sparseLevels);
    }
}
