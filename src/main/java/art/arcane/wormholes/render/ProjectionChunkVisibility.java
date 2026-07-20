package art.arcane.wormholes.render;

import org.bukkit.entity.Player;

@FunctionalInterface
public interface ProjectionChunkVisibility {
    boolean isChunkSent(Player observer, int chunkX, int chunkZ);

    default long revision(Player observer) {
        return Long.MIN_VALUE;
    }

    default long chunkRevision(Player observer, int chunkX, int chunkZ) {
        return Long.MIN_VALUE;
    }
}
