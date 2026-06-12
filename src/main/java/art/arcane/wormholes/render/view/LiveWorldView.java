package art.arcane.wormholes.render.view;

import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;

public final class LiveWorldView implements ProjectionWorldView {
    private final World world;
    private final BlockData sharedAir;

    public LiveWorldView(World world) {
        this.world = world;
        this.sharedAir = Material.AIR.createBlockData();
    }

    @Override
    public World getWorld() {
        return world;
    }

    @Override
    public int getMinHeight() {
        return world.getMinHeight();
    }

    @Override
    public int getMaxHeight() {
        return world.getMaxHeight();
    }

    @Override
    public BlockData sampleBlockData(int x, int y, int z) {
        if (y < world.getMinHeight() || y > world.getMaxHeight() - 1) {
            return null;
        }
        Block block = world.getBlockAt(x, y, z);
        if (ProjectionWorldView.isAir(block.getType())) {
            return sharedAir;
        }
        return block.getBlockData();
    }

    @Override
    public int getLight(int x, int y, int z) {
        if (y < world.getMinHeight() || y > world.getMaxHeight() - 1) {
            return LIGHT_UNAVAILABLE;
        }
        Block block = world.getBlockAt(x, y, z);
        return ProjectionWorldView.packLight(block.getLightFromSky(), block.getLightFromBlocks());
    }

    @Override
    public int getSkyDarken() {
        return ProjectionWorldView.computeSkyDarken(world.getTime());
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof LiveWorldView view)) {
            return false;
        }
        return world.equals(view.world);
    }

    @Override
    public int hashCode() {
        return world.hashCode();
    }
}
