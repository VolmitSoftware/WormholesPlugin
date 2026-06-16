package art.arcane.wormholes.network.replication;

public record BlockEntityDiff(int packedXyz, byte[] nbt) {
}
