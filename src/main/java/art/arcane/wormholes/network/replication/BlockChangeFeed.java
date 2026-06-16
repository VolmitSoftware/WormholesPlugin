package art.arcane.wormholes.network.replication;

import org.bukkit.World;

public interface BlockChangeFeed {
    void onBlockChange(World world, long chunkKey, BlockChange change);

    void onLightChange(World world, long chunkKey, LightDiff diff);

    void onBlockEntityChange(World world, long chunkKey, BlockEntityDiff diff);

    void onTickEnd();
}
