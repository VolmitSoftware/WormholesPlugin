package art.arcane.wormholes.util;

import org.bukkit.Chunk;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;

import art.arcane.volmlib.util.collection.KList;

public final class W {
    private W() {
    }

    public static KList<Block> blockFaces(Block b) {
        KList<Block> blocks = new KList<>();
        blocks.add(b.getRelative(BlockFace.UP));
        blocks.add(b.getRelative(BlockFace.DOWN));
        blocks.add(b.getRelative(BlockFace.NORTH));
        blocks.add(b.getRelative(BlockFace.SOUTH));
        blocks.add(b.getRelative(BlockFace.EAST));
        blocks.add(b.getRelative(BlockFace.WEST));
        return blocks;
    }

    public static KList<Chunk> chunkRadius(Chunk c, int rad) {
        KList<Chunk> cx = new KList<>();
        for (int i = c.getX() - rad + 1; i < c.getX() + rad; i++) {
            for (int j = c.getZ() - rad + 1; j < c.getZ() + rad; j++) {
                cx.add(c.getWorld().getChunkAt(i, j));
            }
        }
        cx.add(c);
        return cx;
    }
}
