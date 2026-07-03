package art.arcane.wormholes.network.replication;

import org.bukkit.World;

import java.util.List;

public interface BlockChangeFeed {
    void onChunkDrain(World world, long chunkKey, List<BlockChange> blocks, List<LightDiff> lights, List<BlockEntityDiff> entities);

    void onTickEnd();
}
