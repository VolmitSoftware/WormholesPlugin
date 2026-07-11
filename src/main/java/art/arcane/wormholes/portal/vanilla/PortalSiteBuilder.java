package art.arcane.wormholes.portal.vanilla;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;

import art.arcane.volmlib.util.scheduling.FoliaScheduler;
import art.arcane.wormholes.Wormholes;

public final class PortalSiteBuilder
{
	private static final int MIN_INTERIOR_WIDTH = 1;
	private static final int MIN_INTERIOR_HEIGHT = 2;
	private static final int MAX_INTERIOR = 21;

	private PortalSiteBuilder()
	{
	}

	public static CompletableFuture<Set<Block>> buildNetherFrameAsync(World world, int centerX, int startY, int centerZ, boolean alongX, int interiorWidth, int interiorHeight)
	{
		CompletableFuture<Set<Block>> result = new CompletableFuture<Set<Block>>();
		int width = netherInteriorWidth(interiorWidth);
		int height = Math.max(MIN_INTERIOR_HEIGHT, Math.min(MAX_INTERIOR, interiorHeight));
		world.getChunkAtAsync(centerX >> 4, centerZ >> 4).whenComplete((chunk, loadError) ->
		{
			if(loadError != null || chunk == null)
			{
				result.completeExceptionally(loadError == null ? new IllegalStateException("Nether target chunk did not load") : loadError);
				return;
			}
			boolean scheduled = FoliaScheduler.runRegion(Wormholes.instance, world, centerX >> 4, centerZ >> 4, () ->
			{
				try
				{
					int baseY = findSafeY(world, centerX, startY, centerZ, height);
					scheduleNetherMutations(world, centerX, baseY, centerZ, alongX, width, height, result);
				}
				catch(Throwable error)
				{
					result.completeExceptionally(error);
				}
			});
			if(!scheduled)
			{
				result.completeExceptionally(new IllegalStateException("Nether target region rejected frame planning"));
			}
		});
		return result;
	}

	private static void scheduleNetherMutations(World world, int centerX, int baseY, int centerZ, boolean alongX, int width, int height,
			CompletableFuture<Set<Block>> result)
	{
		if(!netherFrameFits(baseY, height, world.getMinHeight(), world.getMaxHeight()))
		{
			result.completeExceptionally(new IllegalStateException("Nether frame would exceed safe world height"));
			return;
		}
		Map<Long, ChunkMutationPlan> plans = new HashMap<Long, ChunkMutationPlan>();
		for(NetherMutation mutation : planNetherMutations(centerX, baseY, centerZ, alongX, width, height))
		{
			addMutation(plans, mutation);
		}
		applyNetherMutationPlans(world, plans, result);
	}

	static List<NetherMutation> planNetherMutations(int centerX, int baseY, int centerZ, boolean alongX, int interiorWidth, int interiorHeight)
	{
		int width = netherInteriorWidth(interiorWidth);
		int height = Math.max(MIN_INTERIOR_HEIGHT, Math.min(MAX_INTERIOR, interiorHeight));
		int ax = alongX ? 1 : 0;
		int az = alongX ? 0 : 1;
		int nx = alongX ? 0 : 1;
		int nz = alongX ? 1 : 0;
		int bx = centerX - (alongX ? width / 2 : 0);
		int bz = centerZ - (alongX ? 0 : width / 2);
		List<NetherMutation> mutations = new ArrayList<NetherMutation>();

		for(int u = -1; u <= width; u++)
		{
			for(int v = -1; v <= height; v++)
			{
				if(isNetherFrameEdge(u, v, width, height))
				{
					mutations.add(new NetherMutation(bx + ax * u, baseY + v, bz + az * u, Material.OBSIDIAN, false, false));
				}
			}
		}

		for(int u = 0; u < width; u++)
		{
			for(int v = 0; v < height; v++)
			{
				for(int n = -1; n <= 1; n++)
				{
					if(n == 0)
					{
						continue;
					}
					mutations.add(new NetherMutation(bx + ax * u + nx * n, baseY + v, bz + az * u + nz * n, Material.AIR, true, false));
				}
			}
		}

		for(int u = 0; u < width; u++)
		{
			for(int v = 0; v < height; v++)
			{
				mutations.add(new NetherMutation(bx + ax * u, baseY + v, bz + az * u, Material.AIR, false, true));
			}
		}
		return List.copyOf(mutations);
	}

	static boolean isNetherFrameEdge(int u, int v, int width, int height)
	{
		return u == -1 || u == width || v == -1 || v == height;
	}

	static int netherInteriorWidth(int requestedWidth)
	{
		return Math.max(MIN_INTERIOR_WIDTH, Math.min(MAX_INTERIOR, requestedWidth));
	}

	static boolean netherFrameFits(int baseY, int interiorHeight, int minHeight, int maxHeight)
	{
		int height = Math.max(MIN_INTERIOR_HEIGHT, Math.min(MAX_INTERIOR, interiorHeight));
		return baseY - 1 >= minHeight && baseY + height <= maxHeight - 3;
	}

	private static void addMutation(Map<Long, ChunkMutationPlan> plans, NetherMutation mutation)
	{
		int chunkX = mutation.x() >> 4;
		int chunkZ = mutation.z() >> 4;
		long key = (((long) chunkX) << 32) ^ (chunkZ & 0xffffffffL);
		ChunkMutationPlan plan = plans.computeIfAbsent(Long.valueOf(key), ignored -> new ChunkMutationPlan(chunkX, chunkZ, new ArrayList<NetherMutation>()));
		plan.mutations().add(mutation);
	}

	private static void applyNetherMutationPlans(World world, Map<Long, ChunkMutationPlan> plans, CompletableFuture<Set<Block>> result)
	{
		if(plans.isEmpty())
		{
			result.complete(Set.of());
			return;
		}
		List<CompletableFuture<?>> chunkLoads = new ArrayList<CompletableFuture<?>>(plans.size());
		for(ChunkMutationPlan plan : plans.values())
		{
			chunkLoads.add(world.getChunkAtAsync(plan.chunkX(), plan.chunkZ()).thenAccept(chunk ->
			{
				if(chunk == null)
				{
					throw new IllegalStateException("Nether frame chunk did not load");
				}
			}));
		}
		CompletableFuture.allOf(chunkLoads.toArray(CompletableFuture[]::new)).whenComplete((ignored, loadError) ->
		{
			if(loadError != null)
			{
				result.completeExceptionally(loadError);
				return;
			}
			applyLoadedNetherMutationPlans(world, plans, result);
		});
	}

	private static void applyLoadedNetherMutationPlans(World world, Map<Long, ChunkMutationPlan> plans, CompletableFuture<Set<Block>> result)
	{
		Set<Block> interior = ConcurrentHashMap.newKeySet();
		Map<BlockPosition, BlockRollback> originals = new ConcurrentHashMap<BlockPosition, BlockRollback>();
		AtomicReference<Throwable> failure = new AtomicReference<Throwable>();
		AtomicInteger remaining = new AtomicInteger(plans.size());
		for(ChunkMutationPlan plan : plans.values())
		{
			boolean scheduled = FoliaScheduler.runRegion(Wormholes.instance, world, plan.chunkX(), plan.chunkZ(), () ->
			{
				try
				{
					if(failure.get() == null)
					{
						for(NetherMutation mutation : plan.mutations())
						{
							Block block = world.getBlockAt(mutation.x(), mutation.y(), mutation.z());
							if(!mutation.preserveObsidian() || block.getType() != Material.OBSIDIAN)
							{
								BlockPosition position = new BlockPosition(mutation.x(), mutation.y(), mutation.z());
								originals.putIfAbsent(position, new BlockRollback(block.getBlockData(), mutation.material()));
								block.setType(mutation.material(), false);
							}
							if(mutation.interior())
							{
								interior.add(block);
							}
						}
					}
				}
				catch(Throwable error)
				{
					failure.compareAndSet(null, error);
				}
				finally
				{
					finishNetherMutationPlan(world, result, interior, originals, failure, remaining);
				}
			});
			if(!scheduled)
			{
				failure.compareAndSet(null, new IllegalStateException("Nether frame region rejected mutations for " + plan.chunkX() + "," + plan.chunkZ()));
				finishNetherMutationPlan(world, result, interior, originals, failure, remaining);
			}
		}
	}

	private static void finishNetherMutationPlan(World world, CompletableFuture<Set<Block>> result, Set<Block> interior,
			Map<BlockPosition, BlockRollback> originals, AtomicReference<Throwable> failure, AtomicInteger remaining)
	{
		if(remaining.decrementAndGet() != 0)
		{
			return;
		}
		Throwable error = failure.get();
		if(error == null)
		{
			result.complete(Set.copyOf(interior));
			return;
		}
		rollbackNetherMutations(world, originals, error, result);
	}

	private static void rollbackNetherMutations(World world, Map<BlockPosition, BlockRollback> originals, Throwable error,
			CompletableFuture<Set<Block>> result)
	{
		if(originals.isEmpty())
		{
			result.completeExceptionally(error);
			return;
		}
		Map<Long, List<Map.Entry<BlockPosition, BlockRollback>>> byChunk = new HashMap<Long, List<Map.Entry<BlockPosition, BlockRollback>>>();
		for(Map.Entry<BlockPosition, BlockRollback> entry : originals.entrySet())
		{
			BlockPosition position = entry.getKey();
			int chunkX = position.x() >> 4;
			int chunkZ = position.z() >> 4;
			long key = (((long) chunkX) << 32) ^ (chunkZ & 0xffffffffL);
			byChunk.computeIfAbsent(Long.valueOf(key), ignored -> new ArrayList<Map.Entry<BlockPosition, BlockRollback>>()).add(entry);
		}
		AtomicInteger remaining = new AtomicInteger(byChunk.size());
		for(List<Map.Entry<BlockPosition, BlockRollback>> entries : byChunk.values())
		{
			BlockPosition anchor = entries.get(0).getKey();
			boolean scheduled = FoliaScheduler.runRegion(Wormholes.instance, world, anchor.x() >> 4, anchor.z() >> 4, () ->
			{
				try
				{
					for(Map.Entry<BlockPosition, BlockRollback> entry : entries)
					{
						BlockPosition position = entry.getKey();
						BlockRollback rollback = entry.getValue();
						Block block = world.getBlockAt(position.x(), position.y(), position.z());
						if(block.getType() == rollback.appliedMaterial())
						{
							block.setBlockData(rollback.originalData(), false);
						}
					}
				}
				finally
				{
					if(remaining.decrementAndGet() == 0)
					{
						result.completeExceptionally(error);
					}
				}
			});
			if(!scheduled && remaining.decrementAndGet() == 0)
			{
				result.completeExceptionally(error);
			}
		}
	}

	record NetherMutation(int x, int y, int z, Material material, boolean preserveObsidian, boolean interior)
	{
	}

	private record ChunkMutationPlan(int chunkX, int chunkZ, List<NetherMutation> mutations)
	{
	}

	private record BlockPosition(int x, int y, int z)
	{
	}

	private record BlockRollback(BlockData originalData, Material appliedMaterial)
	{
	}

	public static Set<Block> buildHorizontalWindow(World world, int centerX, int y, int centerZ, int half)
	{
		Set<Block> cells = horizontalWindowCells(world, centerX, y, centerZ, half);
		for(Block cell : cells)
		{
			cell.setType(Material.AIR, false);
		}
		return cells;
	}

	public static Set<Block> horizontalWindowCells(World world, int centerX, int y, int centerZ, int half)
	{
		Set<Block> cells = new HashSet<Block>();
		for(int dx = -half; dx <= half; dx++)
		{
			for(int dz = -half; dz <= half; dz++)
			{
				Block cell = world.getBlockAt(centerX + dx, y, centerZ + dz);
				cells.add(cell);
			}
		}
		return cells;
	}

	private static int findSafeY(World world, int x, int startY, int z, int height)
	{
		int min = world.getMinHeight() + 5;
		int max = Math.min(world.getMaxHeight() - (height + 3), world.getEnvironment() == World.Environment.NETHER ? 122 : world.getMaxHeight());
		int y = Math.max(min, Math.min(max, startY));
		for(int cy = y; cy >= min; cy--)
		{
			if(isFloor(world, x, cy, z, height))
			{
				return cy + 1;
			}
		}
		for(int cy = y + 1; cy <= max; cy++)
		{
			if(isFloor(world, x, cy, z, height))
			{
				return cy + 1;
			}
		}
		return y;
	}

	private static boolean isFloor(World world, int x, int y, int z, int height)
	{
		Block floor = world.getBlockAt(x, y, z);
		if(!floor.getType().isSolid() || isLiquid(floor.getType()))
		{
			return false;
		}
		for(int i = 1; i <= height + 1; i++)
		{
			Block above = world.getBlockAt(x, y + i, z);
			if(above.getType().isSolid() || isLiquid(above.getType()))
			{
				return false;
			}
		}
		return true;
	}

	private static boolean isLiquid(Material material)
	{
		return material == Material.LAVA || material == Material.WATER;
	}
}
