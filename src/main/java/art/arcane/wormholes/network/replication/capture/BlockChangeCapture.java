package art.arcane.wormholes.network.replication.capture;

import art.arcane.wormholes.network.replication.BlockChange;

import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.data.BlockData;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockBurnEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockFadeEvent;
import org.bukkit.event.block.BlockFormEvent;
import org.bukkit.event.block.BlockFromToEvent;
import org.bukkit.event.block.BlockGrowEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.block.BlockSpreadEvent;
import org.bukkit.event.block.LeavesDecayEvent;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.world.StructureGrowEvent;

import java.util.List;

public final class BlockChangeCapture implements Listener {
    private final RegionalDiffAccumulator accumulator;
    private final BlockEntityCapture blockEntityCapture;

    public BlockChangeCapture(RegionalDiffAccumulator accumulator, BlockEntityCapture blockEntityCapture) {
        this.accumulator = accumulator;
        this.blockEntityCapture = blockEntityCapture;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockPlace(BlockPlaceEvent event) {
        Block block = event.getBlockPlaced();
        recordBlock(block.getWorld(), block, block.getBlockData());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        Block block = event.getBlock();
        recordAirAt(block);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockBurn(BlockBurnEvent event) {
        recordAirAt(event.getBlock());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent event) {
        explodeBlocks(event.getBlock().getWorld(), event.blockList());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityExplode(EntityExplodeEvent event) {
        explodeBlocks(event.getEntity().getWorld(), event.blockList());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockFromTo(BlockFromToEvent event) {
        Block toBlock = event.getToBlock();
        recordBlock(toBlock.getWorld(), toBlock, toBlock.getBlockData());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onLeavesDecay(LeavesDecayEvent event) {
        recordAirAt(event.getBlock());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockFade(BlockFadeEvent event) {
        Block block = event.getBlock();
        BlockData target = event.getNewState() == null ? null : event.getNewState().getBlockData();
        if (target == null) {
            target = block.getWorld().getBlockAt(block.getLocation()).getBlockData();
        }
        recordBlock(block.getWorld(), block, target);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockForm(BlockFormEvent event) {
        Block block = event.getBlock();
        BlockState newState = event.getNewState();
        if (newState == null) {
            return;
        }
        recordBlock(block.getWorld(), block, newState.getBlockData());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockGrow(BlockGrowEvent event) {
        Block block = event.getBlock();
        BlockState newState = event.getNewState();
        if (newState == null) {
            return;
        }
        recordBlock(block.getWorld(), block, newState.getBlockData());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockSpread(BlockSpreadEvent event) {
        Block block = event.getBlock();
        BlockState newState = event.getNewState();
        if (newState == null) {
            return;
        }
        recordBlock(block.getWorld(), block, newState.getBlockData());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPistonExtend(BlockPistonExtendEvent event) {
        World world = event.getBlock().getWorld();
        for (Block block : event.getBlocks()) {
            recordBlock(world, block, block.getBlockData());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPistonRetract(BlockPistonRetractEvent event) {
        World world = event.getBlock().getWorld();
        for (Block block : event.getBlocks()) {
            recordBlock(world, block, block.getBlockData());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onStructureGrow(StructureGrowEvent event) {
        World world = event.getWorld();
        for (BlockState state : event.getBlocks()) {
            int worldX = state.getX();
            int worldY = state.getY();
            int worldZ = state.getZ();
            BlockData data = state.getBlockData();
            recordRaw(world, worldX, worldY, worldZ, data);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityChangeBlock(EntityChangeBlockEvent event) {
        Block block = event.getBlock();
        BlockData target = event.getBlockData();
        if (target == null) {
            target = block.getBlockData();
        }
        recordBlock(block.getWorld(), block, target);
        if (blockEntityCapture != null) {
            blockEntityCapture.captureFromBlock(block);
        }
    }

    private void explodeBlocks(World world, List<Block> blocks) {
        Material airType = Material.AIR;
        BlockData air = airType.createBlockData();
        for (Block block : blocks) {
            recordRaw(world, block.getX(), block.getY(), block.getZ(), air);
        }
    }

    private void recordAirAt(Block block) {
        BlockData air = Material.AIR.createBlockData();
        recordRaw(block.getWorld(), block.getX(), block.getY(), block.getZ(), air);
    }

    private void recordBlock(World world, Block block, BlockData data) {
        if (data == null) {
            return;
        }
        recordRaw(world, block.getX(), block.getY(), block.getZ(), data);
        if (blockEntityCapture != null) {
            blockEntityCapture.captureFromBlock(block);
        }
    }

    private void recordRaw(World world, int worldX, int worldY, int worldZ, BlockData data) {
        if (world == null || data == null) {
            return;
        }
        accumulator.recordBlockChange(world, worldX, worldY, worldZ, data, BlockChange.FLAG_NONE);
    }
}
