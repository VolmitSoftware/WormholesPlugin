package art.arcane.wormholes.render.view;

import org.bukkit.World;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

final class LiveWorldViewProvider implements ProjectionWorldViewProvider {
    private final Map<World, ProjectionWorldView> views;

    LiveWorldViewProvider() {
        this.views = new ConcurrentHashMap<World, ProjectionWorldView>();
    }

    @Override
    public ProjectionWorldView view(World world) {
        if (world == null) {
            return null;
        }
        return views.computeIfAbsent(world, LiveWorldView::new);
    }

    @Override
    public void close() {
        views.clear();
    }
}
