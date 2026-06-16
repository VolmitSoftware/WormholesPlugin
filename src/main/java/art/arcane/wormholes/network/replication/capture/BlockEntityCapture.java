package art.arcane.wormholes.network.replication.capture;

import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.TileState;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockDispenseEvent;
import org.bukkit.event.block.BlockRedstoneEvent;
import org.bukkit.event.block.SignChangeEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.InventoryHolder;

import java.io.IOException;
import java.util.logging.Level;
import java.util.logging.Logger;

public final class BlockEntityCapture implements Listener {
    public static final int MAX_NBT_BYTES = 2048;

    private final RegionalDiffAccumulator accumulator;
    private final Logger logger;

    public BlockEntityCapture(RegionalDiffAccumulator accumulator, Logger logger) {
        this.accumulator = accumulator;
        this.logger = logger;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onSignChange(SignChangeEvent event) {
        captureFromBlock(event.getBlock());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onInventoryClose(InventoryCloseEvent event) {
        InventoryHolder holder = event.getInventory().getHolder();
        if (!(holder instanceof BlockState state)) {
            return;
        }
        captureFromBlock(state.getBlock());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerInteract(PlayerInteractEvent event) {
        Block clicked = event.getClickedBlock();
        if (clicked == null) {
            return;
        }
        captureFromBlock(clicked);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockDispense(BlockDispenseEvent event) {
        captureFromBlock(event.getBlock());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockRedstone(BlockRedstoneEvent event) {
        if (event.getOldCurrent() == event.getNewCurrent()) {
            return;
        }
        captureFromBlock(event.getBlock());
    }

    public void captureFromBlock(Block block) {
        if (block == null) {
            return;
        }
        Location location = block.getLocation();
        int worldX = location.getBlockX();
        int worldY = location.getBlockY();
        int worldZ = location.getBlockZ();
        BlockState state;
        try {
            state = block.getState(false);
        } catch (Throwable ex) {
            return;
        }
        if (!(state instanceof TileState tileState)) {
            return;
        }
        byte[] payload;
        try {
            payload = tileState.getPersistentDataContainer().serializeToBytes();
        } catch (IOException ex) {
            if (logger != null) {
                logger.log(Level.WARNING, "Block-entity NBT serialization failed at " + worldX + "," + worldY + "," + worldZ, ex);
            }
            return;
        }
        if (payload == null) {
            return;
        }
        if (payload.length > MAX_NBT_BYTES) {
            return;
        }
        accumulator.recordBlockEntityChange(block.getWorld(), worldX, worldY, worldZ, payload);
    }
}
