package art.arcane.wormholes.render.view;

import org.bukkit.World;

public interface ProjectionWorldViewProvider {
    ProjectionWorldView view(World world);

    default boolean usesRegionSnapshots() {
        return false;
    }

    default void close() {
    }

    static ProjectionWorldViewProvider live() {
        return new LiveWorldViewProvider();
    }
}
