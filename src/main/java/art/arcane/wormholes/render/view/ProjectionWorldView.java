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

    String sampleBiome(int x, int y, int z);

    int getLight(int x, int y, int z);

    int getSkyDarken();

    static int computeSkyDarken(long dayTime) {
        double d = (dayTime / 24000.0D) - 0.25D;
        d = d - Math.floor(d);
        double e = 0.5D - Math.cos(d * Math.PI) / 2.0D;
        double celestialAngle = (d * 2.0D + e) / 3.0D;
        double f = 1.0D - (Math.cos(celestialAngle * Math.PI * 2.0D) * 2.0D + 0.5D);
        f = Math.max(0.0D, Math.min(1.0D, f));
        return (int) (f * 11.0D);
    }

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
