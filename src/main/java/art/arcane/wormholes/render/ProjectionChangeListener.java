package art.arcane.wormholes.render;

import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.BlockFace;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockBurnEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockFadeEvent;
import org.bukkit.event.block.BlockFertilizeEvent;
import org.bukkit.event.block.BlockFormEvent;
import org.bukkit.event.block.BlockFromToEvent;
import org.bukkit.event.block.BlockGrowEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.block.BlockSpreadEvent;
import org.bukkit.event.block.FluidLevelChangeEvent;
import org.bukkit.event.block.LeavesDecayEvent;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.world.StructureGrowEvent;
import org.bukkit.event.world.WorldUnloadEvent;

public final class ProjectionChangeListener implements Listener {
    private final ProjectionWorldChangeTracker tracker;

    public ProjectionChangeListener(ProjectionWorldChangeTracker tracker) {
        this.tracker = tracker;
    }

    private void mark(Block block) {
        if (block != null) {
            tracker.markChanged(block.getWorld().getUID(), block.getX(), block.getZ());
        }
    }

    private void mark(BlockState state) {
        if (state != null) {
            mark(state.getBlock());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void on(BlockPlaceEvent e) {
        mark(e.getBlock());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void on(BlockBreakEvent e) {
        mark(e.getBlock());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void on(BlockBurnEvent e) {
        mark(e.getBlock());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void on(BlockFadeEvent e) {
        mark(e.getBlock());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void on(BlockFormEvent e) {
        mark(e.getBlock());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void on(BlockGrowEvent e) {
        mark(e.getBlock());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void on(BlockSpreadEvent e) {
        mark(e.getBlock());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void on(BlockFromToEvent e) {
        mark(e.getBlock());
        mark(e.getToBlock());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void on(FluidLevelChangeEvent e) {
        mark(e.getBlock());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void on(LeavesDecayEvent e) {
        mark(e.getBlock());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void on(EntityChangeBlockEvent e) {
        mark(e.getBlock());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void on(BlockFertilizeEvent e) {
        mark(e.getBlock());
        for (BlockState state : e.getBlocks()) {
            mark(state);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void on(StructureGrowEvent e) {
        for (BlockState state : e.getBlocks()) {
            mark(state);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void on(BlockExplodeEvent e) {
        mark(e.getBlock());
        for (Block block : e.blockList()) {
            mark(block);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void on(EntityExplodeEvent e) {
        for (Block block : e.blockList()) {
            mark(block);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void on(BlockPistonExtendEvent e) {
        BlockFace direction = e.getDirection();
        mark(e.getBlock());
        for (Block block : e.getBlocks()) {
            mark(block);
            mark(block.getRelative(direction));
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void on(BlockPistonRetractEvent e) {
        BlockFace direction = e.getDirection();
        mark(e.getBlock());
        for (Block block : e.getBlocks()) {
            mark(block);
            mark(block.getRelative(direction));
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void on(WorldUnloadEvent e) {
        tracker.clearWorld(e.getWorld().getUID());
    }
}
