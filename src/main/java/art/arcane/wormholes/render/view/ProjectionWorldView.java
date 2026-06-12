package art.arcane.wormholes.render.view;

import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.data.BlockData;

public interface ProjectionWorldView {
    int LIGHT_UNAVAILABLE = -1;

    World getWorld();

    int getMinHeight();

    int getMaxHeight();

    BlockData sampleBlockData(int x, int y, int z);

    int getLight(int x, int y, int z);

    static boolean isAir(Material material) {
        return material == Material.AIR || material == Material.CAVE_AIR || material == Material.VOID_AIR;
    }

    static int packLight(int sky, int block) {
        return ((sky & 0x0F) << 4) | (block & 0x0F);
    }

    static int unpackSkyLight(int packed) {
        return (packed >> 4) & 0x0F;
    }

    static int unpackBlockLight(int packed) {
        return packed & 0x0F;
    }
}
