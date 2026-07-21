package art.arcane.wormholes.portal.rtp;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Lightable;
import org.bukkit.plugin.Plugin;
import org.bukkit.util.BoundingBox;

import art.arcane.volmlib.util.bukkit.WorldIdentity;
import art.arcane.volmlib.util.scheduling.FoliaScheduler;
import art.arcane.wormholes.chunk.BukkitChunkLeaseProvider;
import art.arcane.wormholes.chunk.ChunkLease;

public final class BukkitRtpCandidateLoader implements AutoCloseable
{
	private final Plugin plugin;
	private final RtpSafetyValidator validator;
	private final Set<CompositeRetention> activeRetentions;
	private final AtomicBoolean closed;

	public BukkitRtpCandidateLoader(Plugin plugin)
	{
		this.plugin = Objects.requireNonNull(plugin, "plugin");
		validator = new RtpSafetyValidator();
		activeRetentions = ConcurrentHashMap.newKeySet();
		closed = new AtomicBoolean(false);
	}

	public CompletionStage<RtpService.LoadedCandidate> search(RtpService.SearchRequest request)
	{
		RtpService.SearchRequest requiredRequest = Objects.requireNonNull(request, "request");
		RtpValidationRequest.EntityEnvelope envelope = RtpValidationRequest.EntityEnvelope.baseline();
		World world = resolveWorld(requiredRequest.destination().worldKey());
		if(world == null)
		{
			return CompletableFuture.failedFuture(new IllegalStateException("RTP target world is unavailable"));
		}
		return retain(world, requiredRequest.destination(), envelope).thenCompose(retention ->
		{
			try
			{
				return surfaceFeetY(world, requiredRequest.destination()).handle((surfaceFeetY, failure) ->
				{
					if(failure != null || surfaceFeetY == null)
					{
						retention.close();
						throw propagate("RTP surface probe failed", failure);
					}
					return surfaceFeetY;
				}).thenCompose(surfaceFeetY ->
						probeSearch(requiredRequest, world, envelope, retention, surfaceFeetY.intValue()));
			}
			catch(RuntimeException | Error failure)
			{
				retention.close();
				throw failure;
			}
		});
	}

	public CompletionStage<RtpService.LoadedCandidate> exact(
			RtpService.SearchRequest request,
			RtpValidationRequest.EntityEnvelope envelope)
	{
		RtpService.SearchRequest requiredRequest = Objects.requireNonNull(request, "request");
		RtpValidationRequest.EntityEnvelope requiredEnvelope = Objects.requireNonNull(envelope, "envelope");
		World world = resolveWorld(requiredRequest.destination().worldKey());
		if(world == null)
		{
			return CompletableFuture.failedFuture(new IllegalStateException("RTP target world is unavailable"));
		}
		return retain(world, requiredRequest.destination(), requiredEnvelope).thenCompose(retention ->
		{
			try
			{
				return capture(world, requiredRequest.destination(), requiredEnvelope).handle((validationRequest, failure) ->
				{
					if(failure != null || validationRequest == null)
					{
						retention.close();
						throw propagate("RTP traversal snapshot capture failed", failure);
					}
					return new RtpService.LoadedCandidate(validationRequest, retention);
				});
			}
			catch(RuntimeException | Error failure)
			{
				retention.close();
				throw failure;
			}
		});
	}

	public void worldUnloaded(UUID worldId)
	{
		UUID requiredWorldId = Objects.requireNonNull(worldId, "worldId");
		for(CompositeRetention retention : List.copyOf(activeRetentions))
		{
			if(requiredWorldId.equals(retention.worldId()))
			{
				retention.close();
			}
		}
	}

	@Override
	public void close()
	{
		if(!closed.compareAndSet(false, true))
		{
			return;
		}
		for(CompositeRetention retention : List.copyOf(activeRetentions))
		{
			retention.close();
		}
		activeRetentions.clear();
	}

	private CompletionStage<RtpService.LoadedCandidate> probeSearch(
			RtpService.SearchRequest request,
			World world,
			RtpValidationRequest.EntityEnvelope envelope,
			CompositeRetention retention,
			int surfaceFeetY)
	{
		RtpSampler sampler = new RtpSampler(request.destination().blockX() + 0.5D, request.destination().blockZ() + 0.5D, 0L);
		List<Integer> probes = sampler.feetYProbeOrder(request.settings(), surfaceFeetY);
		CompletableFuture<RtpService.LoadedCandidate> result = new CompletableFuture<RtpService.LoadedCandidate>();
		probeSearch(request, world, envelope, retention, probes, 0, result);
		return result;
	}

	private void probeSearch(
			RtpService.SearchRequest request,
			World world,
			RtpValidationRequest.EntityEnvelope envelope,
			CompositeRetention retention,
			List<Integer> probes,
			int index,
			CompletableFuture<RtpService.LoadedCandidate> result)
	{
		if(result.isDone())
		{
			return;
		}
		if(closed.get() || index >= probes.size())
		{
			retention.close();
			result.completeExceptionally(new IllegalStateException("RTP search found no safe vertical destination"));
			return;
		}
		RtpDestination sampled = request.destination();
		RtpDestination candidate = new RtpDestination(
				sampled.worldKey(),
				sampled.blockX(),
				probes.get(index).intValue(),
				sampled.blockZ(),
				sampled.generation(),
				sampled.attempt());
		capture(world, candidate, envelope).whenComplete((validationRequest, failure) ->
		{
			if(failure != null || validationRequest == null)
			{
				retention.close();
				result.completeExceptionally(failure == null
						? new IllegalStateException("RTP snapshot capture returned no result") : failure);
				return;
			}
			validator.validate(validationRequest).whenComplete((safety, validationFailure) ->
			{
				if(validationFailure != null)
				{
					retention.close();
					result.completeExceptionally(validationFailure);
					return;
				}
				if(safety != null && safety.safe())
				{
					result.complete(new RtpService.LoadedCandidate(validationRequest, retention));
					return;
				}
				probeSearch(request, world, envelope, retention, probes, index + 1, result);
			});
		});
	}

	private CompletionStage<CompositeRetention> retain(
			World world,
			RtpDestination destination,
			RtpValidationRequest.EntityEnvelope envelope)
	{
		if(closed.get())
		{
			return CompletableFuture.failedFuture(new IllegalStateException("RTP candidate loader is closed"));
		}
		Set<ChunkCoordinate> chunks = touchedChunks(destination, envelope);
		List<ChunkLease> leases = new ArrayList<ChunkLease>(chunks.size());
		for(ChunkCoordinate chunk : chunks)
		{
			leases.add(BukkitChunkLeaseProvider.registry().retain(world, world.getUID(), chunk.x(), chunk.z()));
		}
		CompositeRetention retention = new CompositeRetention(world.getUID(), leases);
		activeRetentions.add(retention);
		CompletableFuture<?>[] readiness = new CompletableFuture<?>[leases.size()];
		for(int index = 0; index < leases.size(); index++)
		{
			readiness[index] = leases.get(index).ready();
		}
		return CompletableFuture.allOf(readiness).thenCompose(ignored ->
		{
			for(ChunkLease lease : leases)
			{
				if(!Boolean.TRUE.equals(lease.ready().getNow(Boolean.FALSE)))
				{
					retention.close();
					return CompletableFuture.failedFuture(new IllegalStateException("RTP destination chunk retention failed"));
				}
			}
			return CompletableFuture.completedFuture(retention);
		});
	}

	private CompletionStage<Integer> surfaceFeetY(World world, RtpDestination destination)
	{
		CompletableFuture<Integer> result = new CompletableFuture<Integer>();
		int chunkX = Math.floorDiv(destination.blockX(), 16);
		int chunkZ = Math.floorDiv(destination.blockZ(), 16);
		boolean scheduled = FoliaScheduler.runRegion(plugin, world, chunkX, chunkZ, () ->
		{
			try
			{
				result.complete(Integer.valueOf(world.getHighestBlockYAt(destination.blockX(), destination.blockZ()) + 1));
			}
			catch(RuntimeException exception)
			{
				result.completeExceptionally(exception);
			}
		});
		if(!scheduled)
		{
			result.completeExceptionally(new IllegalStateException("Owning region rejected RTP surface query"));
		}
		return result;
	}

	private CompletionStage<RtpValidationRequest> capture(
			World world,
			RtpDestination destination,
			RtpValidationRequest.EntityEnvelope envelope)
	{
		Set<ChunkCoordinate> chunks = touchedChunks(destination, envelope);
		List<CompletableFuture<RtpValidationRequest.RegionSnapshot>> snapshots = new ArrayList<CompletableFuture<RtpValidationRequest.RegionSnapshot>>(chunks.size());
		for(ChunkCoordinate chunk : chunks)
		{
			CompletableFuture<RtpValidationRequest.RegionSnapshot> snapshot = new CompletableFuture<RtpValidationRequest.RegionSnapshot>();
			snapshots.add(snapshot);
			boolean scheduled = FoliaScheduler.runRegion(plugin, world, chunk.x(), chunk.z(), () ->
			{
				try
				{
					snapshot.complete(captureChunk(world, destination, envelope, chunk));
				}
				catch(RuntimeException exception)
				{
					snapshot.completeExceptionally(exception);
				}
			});
			if(!scheduled)
			{
				snapshot.completeExceptionally(new IllegalStateException("Owning region rejected RTP block snapshot capture"));
			}
		}
		CompletableFuture<?>[] completions = snapshots.toArray(new CompletableFuture<?>[0]);
		return CompletableFuture.allOf(completions).thenApply(ignored ->
		{
			List<RtpValidationRequest.RegionSnapshot> completed = new ArrayList<RtpValidationRequest.RegionSnapshot>(snapshots.size());
			for(CompletableFuture<RtpValidationRequest.RegionSnapshot> snapshot : snapshots)
			{
				completed.add(snapshot.join());
			}
			return validationRequest(world, destination, envelope, completed);
		});
	}

	private RtpValidationRequest.RegionSnapshot captureChunk(
			World world,
			RtpDestination destination,
			RtpValidationRequest.EntityEnvelope envelope,
			ChunkCoordinate chunk)
	{
		BlockRange range = blockRange(destination, envelope);
		int chunkMinimumX = chunk.x() * 16;
		int chunkMinimumZ = chunk.z() * 16;
		int minimumX = Math.max(range.minimumX(), chunkMinimumX);
		int maximumX = Math.min(range.maximumX(), chunkMinimumX + 15);
		int minimumZ = Math.max(range.minimumZ(), chunkMinimumZ);
		int maximumZ = Math.min(range.maximumZ(), chunkMinimumZ + 15);
		List<RtpValidationRequest.BlockSnapshot> blocks = new ArrayList<RtpValidationRequest.BlockSnapshot>();
		for(int x = minimumX; x <= maximumX; x++)
		{
			for(int z = minimumZ; z <= maximumZ; z++)
			{
				for(int y = range.minimumY(); y <= range.maximumY(); y++)
				{
					blocks.add(blockSnapshot(world.getBlockAt(x, y, z)));
				}
			}
		}
		return new RtpValidationRequest.RegionSnapshot(
				world.getUID() + ":" + chunk.x() + ":" + chunk.z(),
				chunk.x(),
				chunk.z(),
				blocks);
	}

	private RtpValidationRequest.BlockSnapshot blockSnapshot(Block block)
	{
		BlockData data = block.getBlockData();
		boolean active = data instanceof Lightable lightable && lightable.isLit();
		Collection<BoundingBox> shape = block.getCollisionShape().getBoundingBoxes();
		List<RtpValidationRequest.CollisionBox> collisions = new ArrayList<RtpValidationRequest.CollisionBox>(shape.size());
		for(BoundingBox box : shape)
		{
			double minimumX = normalizeCollisionCoordinate(box.getMinX(), block.getX());
			double minimumY = normalizeCollisionCoordinate(box.getMinY(), block.getY());
			double minimumZ = normalizeCollisionCoordinate(box.getMinZ(), block.getZ());
			double maximumX = normalizeCollisionCoordinate(box.getMaxX(), block.getX());
			double maximumY = normalizeCollisionCoordinate(box.getMaxY(), block.getY());
			double maximumZ = normalizeCollisionCoordinate(box.getMaxZ(), block.getZ());
			if(maximumX > minimumX && maximumY > minimumY && maximumZ > minimumZ)
			{
				collisions.add(new RtpValidationRequest.CollisionBox(
						minimumX, minimumY, minimumZ, maximumX, maximumY, maximumZ));
			}
		}
		return RtpValidationRequest.BlockSnapshot.of(
				block.getX(),
				block.getY(),
				block.getZ(),
				block.getType().getKey().toString(),
				block.isLiquid(),
				active,
				collisions);
	}

	private double normalizeCollisionCoordinate(double value, int blockCoordinate)
	{
		return value >= -RtpSafetyValidator.EPSILON && value <= 1.0D + RtpSafetyValidator.EPSILON
				? value : value - blockCoordinate;
	}

	private RtpValidationRequest validationRequest(
			World world,
			RtpDestination destination,
			RtpValidationRequest.EntityEnvelope envelope,
			List<RtpValidationRequest.RegionSnapshot> snapshots)
	{
		org.bukkit.WorldBorder border = world.getWorldBorder();
		Location center = border.getCenter();
		double halfSize = border.getSize() / 2.0D;
		RtpValidationRequest.Dimension dimension = switch(world.getEnvironment())
		{
			case NETHER -> RtpValidationRequest.Dimension.NETHER;
			case THE_END -> RtpValidationRequest.Dimension.END;
			default -> RtpValidationRequest.Dimension.OVERWORLD;
		};
		return RtpValidationRequest.builder(destination)
				.snapshotWorldKey(WorldIdentity.serialize(world))
				.worldBounds(world.getMinHeight(), world.getMaxHeight())
				.worldBorder(new RtpValidationRequest.WorldBorder(
						center.getX() - halfSize,
						center.getZ() - halfSize,
						center.getX() + halfSize,
						center.getZ() + halfSize))
				.dimension(dimension)
				.netherLogicalCeiling(dimension == RtpValidationRequest.Dimension.NETHER
						? Math.min(world.getMaxHeight(), 128)
						: world.getMaxHeight())
				.entityEnvelope(envelope)
				.configuredHazards(Set.of())
				.regionSnapshots(snapshots)
				.build();
	}

	private Set<ChunkCoordinate> touchedChunks(
			RtpDestination destination,
			RtpValidationRequest.EntityEnvelope envelope)
	{
		BlockRange range = blockRange(destination, envelope);
		int minimumChunkX = Math.floorDiv(range.minimumX(), 16);
		int maximumChunkX = Math.floorDiv(range.maximumX(), 16);
		int minimumChunkZ = Math.floorDiv(range.minimumZ(), 16);
		int maximumChunkZ = Math.floorDiv(range.maximumZ(), 16);
		Set<ChunkCoordinate> chunks = new LinkedHashSet<ChunkCoordinate>();
		for(int chunkX = minimumChunkX; chunkX <= maximumChunkX; chunkX++)
		{
			for(int chunkZ = minimumChunkZ; chunkZ <= maximumChunkZ; chunkZ++)
			{
				chunks.add(new ChunkCoordinate(chunkX, chunkZ));
			}
		}
		if(chunks.size() > 4)
		{
			throw new IllegalArgumentException("RTP entity envelope spans more than four chunks");
		}
		return Set.copyOf(chunks);
	}

	private BlockRange blockRange(
			RtpDestination destination,
			RtpValidationRequest.EntityEnvelope envelope)
	{
		double centerX = destination.blockX() + 0.5D;
		double centerZ = destination.blockZ() + 0.5D;
		double anchorX = centerX - (envelope.minimumXOffset() + envelope.maximumXOffset()) / 2.0D;
		double anchorY = destination.feetY() - envelope.minimumYOffset();
		double anchorZ = centerZ - (envelope.minimumZOffset() + envelope.maximumZOffset()) / 2.0D;
		double minimumX = anchorX + envelope.minimumXOffset();
		double maximumX = anchorX + envelope.maximumXOffset();
		double maximumY = anchorY + envelope.maximumYOffset();
		double minimumZ = anchorZ + envelope.minimumZOffset();
		double maximumZ = anchorZ + envelope.maximumZOffset();
		return new BlockRange(
				floor(minimumX - RtpSafetyValidator.EPSILON),
				floor(maximumX + RtpSafetyValidator.EPSILON),
				destination.feetY() - 1,
				floor(maximumY + RtpSafetyValidator.EPSILON),
				floor(minimumZ - RtpSafetyValidator.EPSILON),
				floor(maximumZ + RtpSafetyValidator.EPSILON));
	}

	private int floor(double value)
	{
		if(value < Integer.MIN_VALUE || value >= (double) Integer.MAX_VALUE + 1.0D)
		{
			throw new IllegalArgumentException("RTP snapshot coordinate exceeds integer bounds");
		}
		return (int) Math.floor(value);
	}

	private World resolveWorld(String worldKey)
	{
		return WorldIdentity.resolve(worldKey).orElse(null);
	}

	private RuntimeException propagate(String message, Throwable failure)
	{
		if(failure instanceof RuntimeException runtimeException)
		{
			return runtimeException;
		}
		return new IllegalStateException(message, failure);
	}

	private record ChunkCoordinate(int x, int z)
	{
	}

	private record BlockRange(
			int minimumX,
			int maximumX,
			int minimumY,
			int maximumY,
			int minimumZ,
			int maximumZ)
	{
	}

	private final class CompositeRetention implements RtpService.Retention
	{
		private final UUID worldId;
		private final List<ChunkLease> leases;
		private final AtomicBoolean released;

		private CompositeRetention(UUID worldId, List<ChunkLease> leases)
		{
			this.worldId = Objects.requireNonNull(worldId, "worldId");
			this.leases = List.copyOf(Objects.requireNonNull(leases, "leases"));
			released = new AtomicBoolean(false);
		}

		private UUID worldId()
		{
			return worldId;
		}

		@Override
		public void close()
		{
			if(!released.compareAndSet(false, true))
			{
				return;
			}
			activeRetentions.remove(this);
			for(ChunkLease lease : leases)
			{
				lease.close();
			}
		}
	}
}
