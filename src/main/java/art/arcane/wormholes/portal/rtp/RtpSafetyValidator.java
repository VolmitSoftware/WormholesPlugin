package art.arcane.wormholes.portal.rtp;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.CompletableFuture;

public final class RtpSafetyValidator
{
	public static final double EPSILON = 0.000001D;

	private static final double MAXIMUM_ENVELOPE_SIZE = 8.0D;
	private static final int MAXIMUM_CHUNKS = 4;
	private static final int MAXIMUM_REGIONS = 4;
	private static final int NETHER_ROOF_BAND_DEPTH = 5;
	private static final Set<String> LIQUID_MATERIALS = Set.of(
			"minecraft:water",
			"minecraft:lava",
			"minecraft:bubble_column");
	private static final Set<String> HAZARD_MATERIALS = Set.of(
			"minecraft:fire",
			"minecraft:soul_fire",
			"minecraft:powder_snow",
			"minecraft:cactus",
			"minecraft:magma_block",
			"minecraft:sweet_berry_bush",
			"minecraft:wither_rose",
			"minecraft:pointed_dripstone",
			"minecraft:cobweb",
			"minecraft:nether_portal",
			"minecraft:end_portal",
			"minecraft:end_gateway");

	public CompletableFuture<RtpSafetyResult> validate(RtpValidationRequest request)
	{
		return CompletableFuture.completedFuture(validateSnapshot(Objects.requireNonNull(request, "request")));
	}

	private RtpSafetyResult validateSnapshot(RtpValidationRequest request)
	{
		RtpDestination destination = request.destination();
		if(!destination.worldKey().equals(request.snapshotWorldKey()))
		{
			return rejected(request, RtpSafetyResult.Code.WORLD_MISMATCH);
		}

		RtpValidationRequest.EntityEnvelope entityEnvelope = request.entityEnvelope();
		if(entityEnvelope.width() > MAXIMUM_ENVELOPE_SIZE
				|| entityEnvelope.height() > MAXIMUM_ENVELOPE_SIZE
				|| entityEnvelope.depth() > MAXIMUM_ENVELOPE_SIZE)
		{
			return rejected(request, RtpSafetyResult.Code.ENVELOPE_TOO_LARGE);
		}

		ValidationEnvelope envelope = ValidationEnvelope.translate(destination, entityEnvelope);
		if(destination.feetY() - 1 < request.worldMinimumY()
				|| envelope.minimumY() < request.worldMinimumY()
				|| envelope.maximumY() + EPSILON > request.worldMaximumY())
		{
			return rejected(request, RtpSafetyResult.Code.WORLD_HEIGHT);
		}

		RtpValidationRequest.WorldBorder border = request.worldBorder();
		if(envelope.minimumX() - EPSILON < border.minimumX()
				|| envelope.maximumX() + EPSILON > border.maximumX()
				|| envelope.minimumZ() - EPSILON < border.minimumZ()
				|| envelope.maximumZ() + EPSILON > border.maximumZ())
		{
			return rejected(request, RtpSafetyResult.Code.WORLD_BORDER);
		}

		if(request.dimension() == RtpValidationRequest.Dimension.NETHER
				&& envelope.maximumY() + EPSILON >= request.netherLogicalCeiling() - NETHER_ROOF_BAND_DEPTH)
		{
			return rejected(request, RtpSafetyResult.Code.NETHER_ROOF);
		}

		SnapshotIndexResult snapshotIndexResult = indexSnapshots(request.regionSnapshots());
		if(snapshotIndexResult.failure() != null)
		{
			return rejected(request, snapshotIndexResult.failure());
		}

		Set<ChunkPosition> touchedChunks = touchedChunks(envelope);
		if(touchedChunks.size() > MAXIMUM_CHUNKS)
		{
			return rejected(request, RtpSafetyResult.Code.TOO_MANY_CHUNKS);
		}
		if(!snapshotIndexResult.chunks().containsAll(touchedChunks))
		{
			return rejected(request, RtpSafetyResult.Code.MISSING_SNAPSHOT);
		}

		Map<BlockPosition, RtpValidationRequest.BlockSnapshot> blocks = snapshotIndexResult.blocks();
		List<SupportRectangle> supportRectangles = new ArrayList<SupportRectangle>();
		int minimumX = floorToInt(envelope.minimumX() - EPSILON);
		int maximumX = floorToInt(envelope.maximumX() + EPSILON);
		int minimumZ = floorToInt(envelope.minimumZ() - EPSILON);
		int maximumZ = floorToInt(envelope.maximumZ() + EPSILON);
		int minimumBodyY = floorToInt(destination.feetY() + EPSILON);
		int maximumBodyY = floorToInt(envelope.maximumY() + EPSILON);

		for(long xValue = minimumX; xValue <= maximumX; xValue++)
		{
			int x = (int) xValue;
			for(long zValue = minimumZ; zValue <= maximumZ; zValue++)
			{
				int z = (int) zValue;
				RtpValidationRequest.BlockSnapshot support = blocks.get(new BlockPosition(x, destination.feetY() - 1, z));
				if(support == null)
				{
					return rejected(request, RtpSafetyResult.Code.MISSING_SNAPSHOT);
				}
				RtpSafetyResult.Code supportFailure = blockFailure(request, support);
				if(supportFailure != null)
				{
					return rejected(request, supportFailure);
				}
				if(request.surfaceMode() && support.treePart())
				{
					return rejected(request, RtpSafetyResult.Code.HAZARD);
				}
				collectSupportRectangles(support, destination.feetY(), envelope, supportRectangles);

				for(int y = minimumBodyY; y <= maximumBodyY; y++)
				{
					RtpValidationRequest.BlockSnapshot body = blocks.get(new BlockPosition(x, y, z));
					if(body == null)
					{
						return rejected(request, RtpSafetyResult.Code.MISSING_SNAPSHOT);
					}
					RtpSafetyResult.Code bodyFailure = blockFailure(request, body);
					if(bodyFailure != null)
					{
						return rejected(request, bodyFailure);
					}
					if(request.surfaceMode() && body.treePart())
					{
						return rejected(request, RtpSafetyResult.Code.HAZARD);
					}
					if(intersectsBody(body, envelope))
					{
						return rejected(request, RtpSafetyResult.Code.BODY_COLLISION);
					}
				}
			}
		}

		if(!coversSupport(envelope, supportRectangles))
		{
			RtpSafetyResult.Code failure = request.dimension() == RtpValidationRequest.Dimension.END
					? RtpSafetyResult.Code.END_VOID
					: RtpSafetyResult.Code.UNSUPPORTED;
			return rejected(request, failure);
		}
		return RtpSafetyResult.safe(destination);
	}

	private SnapshotIndexResult indexSnapshots(List<RtpValidationRequest.RegionSnapshot> snapshots)
	{
		Set<ChunkPosition> chunks = new HashSet<ChunkPosition>();
		Set<String> regions = new HashSet<String>();
		for(RtpValidationRequest.RegionSnapshot snapshot : snapshots)
		{
			chunks.add(new ChunkPosition(snapshot.chunkX(), snapshot.chunkZ()));
			regions.add(snapshot.regionKey());
		}
		if(chunks.size() > MAXIMUM_CHUNKS)
		{
			return SnapshotIndexResult.failed(RtpSafetyResult.Code.TOO_MANY_CHUNKS);
		}
		if(regions.size() > MAXIMUM_REGIONS)
		{
			return SnapshotIndexResult.failed(RtpSafetyResult.Code.TOO_MANY_REGIONS);
		}
		if(snapshots.size() != chunks.size())
		{
			return SnapshotIndexResult.failed(RtpSafetyResult.Code.INVALID_SNAPSHOT);
		}

		Map<BlockPosition, RtpValidationRequest.BlockSnapshot> blocks = new HashMap<BlockPosition, RtpValidationRequest.BlockSnapshot>();
		for(RtpValidationRequest.RegionSnapshot snapshot : snapshots)
		{
			for(RtpValidationRequest.BlockSnapshot block : snapshot.blocks())
			{
				int blockChunkX = Math.floorDiv(block.x(), 16);
				int blockChunkZ = Math.floorDiv(block.z(), 16);
				if(blockChunkX != snapshot.chunkX() || blockChunkZ != snapshot.chunkZ())
				{
					return SnapshotIndexResult.failed(RtpSafetyResult.Code.INVALID_SNAPSHOT);
				}
				BlockPosition position = new BlockPosition(block.x(), block.y(), block.z());
				if(blocks.putIfAbsent(position, block) != null)
				{
					return SnapshotIndexResult.failed(RtpSafetyResult.Code.INVALID_SNAPSHOT);
				}
			}
		}
		return SnapshotIndexResult.success(Set.copyOf(chunks), Map.copyOf(blocks));
	}

	private Set<ChunkPosition> touchedChunks(ValidationEnvelope envelope)
	{
		int minimumBlockX = floorToInt(envelope.minimumX() - EPSILON);
		int maximumBlockX = floorToInt(envelope.maximumX() + EPSILON);
		int minimumBlockZ = floorToInt(envelope.minimumZ() - EPSILON);
		int maximumBlockZ = floorToInt(envelope.maximumZ() + EPSILON);
		int minimumChunkX = Math.floorDiv(minimumBlockX, 16);
		int maximumChunkX = Math.floorDiv(maximumBlockX, 16);
		int minimumChunkZ = Math.floorDiv(minimumBlockZ, 16);
		int maximumChunkZ = Math.floorDiv(maximumBlockZ, 16);
		Set<ChunkPosition> chunks = new HashSet<ChunkPosition>();
		for(int chunkX = minimumChunkX; chunkX <= maximumChunkX; chunkX++)
		{
			for(int chunkZ = minimumChunkZ; chunkZ <= maximumChunkZ; chunkZ++)
			{
				chunks.add(new ChunkPosition(chunkX, chunkZ));
			}
		}
		return Set.copyOf(chunks);
	}

	private RtpSafetyResult.Code blockFailure(RtpValidationRequest request, RtpValidationRequest.BlockSnapshot block)
	{
		if(block.liquid() || LIQUID_MATERIALS.contains(block.materialKey()))
		{
			return RtpSafetyResult.Code.LIQUID;
		}
		if(request.configuredHazards().contains(block.materialKey()) || HAZARD_MATERIALS.contains(block.materialKey()))
		{
			return RtpSafetyResult.Code.HAZARD;
		}
		if(block.active() && (block.materialKey().equals("minecraft:campfire")
				|| block.materialKey().equals("minecraft:soul_campfire")))
		{
			return RtpSafetyResult.Code.HAZARD;
		}
		return null;
	}

	private void collectSupportRectangles(RtpValidationRequest.BlockSnapshot block, int supportY,
			ValidationEnvelope envelope, List<SupportRectangle> rectangles)
	{
		for(RtpValidationRequest.CollisionBox collision : block.collisionBoxes())
		{
			double top = block.y() + collision.maximumY();
			if(Math.abs(top - supportY) > EPSILON)
			{
				continue;
			}
			double minimumX = Math.max(envelope.minimumX() - EPSILON, block.x() + collision.minimumX());
			double maximumX = Math.min(envelope.maximumX() + EPSILON, block.x() + collision.maximumX());
			double minimumZ = Math.max(envelope.minimumZ() - EPSILON, block.z() + collision.minimumZ());
			double maximumZ = Math.min(envelope.maximumZ() + EPSILON, block.z() + collision.maximumZ());
			if(maximumX > minimumX && maximumZ > minimumZ)
			{
				rectangles.add(new SupportRectangle(minimumX, maximumX, minimumZ, maximumZ));
			}
		}
	}

	private boolean intersectsBody(RtpValidationRequest.BlockSnapshot block, ValidationEnvelope envelope)
	{
		double minimumX = envelope.minimumX() - EPSILON;
		double maximumX = envelope.maximumX() + EPSILON;
		double minimumY = envelope.minimumY() + EPSILON;
		double maximumY = envelope.maximumY() + EPSILON;
		double minimumZ = envelope.minimumZ() - EPSILON;
		double maximumZ = envelope.maximumZ() + EPSILON;
		for(RtpValidationRequest.CollisionBox collision : block.collisionBoxes())
		{
			double collisionMinimumX = block.x() + collision.minimumX();
			double collisionMaximumX = block.x() + collision.maximumX();
			double collisionMinimumY = block.y() + collision.minimumY();
			double collisionMaximumY = block.y() + collision.maximumY();
			double collisionMinimumZ = block.z() + collision.minimumZ();
			double collisionMaximumZ = block.z() + collision.maximumZ();
			if(collisionMaximumX > minimumX && collisionMinimumX < maximumX
					&& collisionMaximumY > minimumY && collisionMinimumY < maximumY
					&& collisionMaximumZ > minimumZ && collisionMinimumZ < maximumZ)
			{
				return true;
			}
		}
		return false;
	}

	private boolean coversSupport(ValidationEnvelope envelope, List<SupportRectangle> rectangles)
	{
		if(rectangles.isEmpty())
		{
			return false;
		}
		double minimumX = envelope.minimumX() - EPSILON;
		double maximumX = envelope.maximumX() + EPSILON;
		double minimumZ = envelope.minimumZ() - EPSILON;
		double maximumZ = envelope.maximumZ() + EPSILON;
		TreeSet<Double> xEdges = new TreeSet<Double>();
		TreeSet<Double> zEdges = new TreeSet<Double>();
		xEdges.add(Double.valueOf(minimumX));
		xEdges.add(Double.valueOf(maximumX));
		zEdges.add(Double.valueOf(minimumZ));
		zEdges.add(Double.valueOf(maximumZ));
		for(SupportRectangle rectangle : rectangles)
		{
			xEdges.add(Double.valueOf(rectangle.minimumX()));
			xEdges.add(Double.valueOf(rectangle.maximumX()));
			zEdges.add(Double.valueOf(rectangle.minimumZ()));
			zEdges.add(Double.valueOf(rectangle.maximumZ()));
		}
		List<Double> orderedX = List.copyOf(xEdges);
		List<Double> orderedZ = List.copyOf(zEdges);
		for(int xIndex = 0; xIndex < orderedX.size() - 1; xIndex++)
		{
			double cellMinimumX = orderedX.get(xIndex).doubleValue();
			double cellMaximumX = orderedX.get(xIndex + 1).doubleValue();
			if(cellMaximumX <= cellMinimumX)
			{
				continue;
			}
			double sampleX = (cellMinimumX + cellMaximumX) / 2.0D;
			for(int zIndex = 0; zIndex < orderedZ.size() - 1; zIndex++)
			{
				double cellMinimumZ = orderedZ.get(zIndex).doubleValue();
				double cellMaximumZ = orderedZ.get(zIndex + 1).doubleValue();
				if(cellMaximumZ <= cellMinimumZ)
				{
					continue;
				}
				double sampleZ = (cellMinimumZ + cellMaximumZ) / 2.0D;
				if(!covered(sampleX, sampleZ, rectangles))
				{
					return false;
				}
			}
		}
		return true;
	}

	private boolean covered(double x, double z, List<SupportRectangle> rectangles)
	{
		for(SupportRectangle rectangle : rectangles)
		{
			if(x >= rectangle.minimumX() && x <= rectangle.maximumX()
					&& z >= rectangle.minimumZ() && z <= rectangle.maximumZ())
			{
				return true;
			}
		}
		return false;
	}

	private RtpSafetyResult rejected(RtpValidationRequest request, RtpSafetyResult.Code code)
	{
		return RtpSafetyResult.rejected(code, request.destination());
	}

	private static int floorToInt(double value)
	{
		if(value < Integer.MIN_VALUE || value >= (double) Integer.MAX_VALUE + 1.0D)
		{
			throw new IllegalArgumentException("validation envelope exceeds integer block bounds");
		}
		return (int) Math.floor(value);
	}

	private record ValidationEnvelope(double minimumX, double maximumX, double minimumY, double maximumY,
			double minimumZ, double maximumZ)
	{
		private static ValidationEnvelope translate(RtpDestination destination, RtpValidationRequest.EntityEnvelope source)
		{
			double centerX = destination.blockX() + 0.5D;
			double centerZ = destination.blockZ() + 0.5D;
			double anchorX = centerX - (source.minimumXOffset() + source.maximumXOffset()) / 2.0D;
			double anchorY = destination.feetY() - source.minimumYOffset();
			double anchorZ = centerZ - (source.minimumZOffset() + source.maximumZOffset()) / 2.0D;
			return new ValidationEnvelope(
					anchorX + source.minimumXOffset(),
					anchorX + source.maximumXOffset(),
					anchorY + source.minimumYOffset(),
					anchorY + source.maximumYOffset(),
					anchorZ + source.minimumZOffset(),
					anchorZ + source.maximumZOffset());
		}
	}

	private record SupportRectangle(double minimumX, double maximumX, double minimumZ, double maximumZ)
	{
	}

	private record BlockPosition(int x, int y, int z)
	{
	}

	private record ChunkPosition(int x, int z)
	{
	}

	private record SnapshotIndexResult(Set<ChunkPosition> chunks,
			Map<BlockPosition, RtpValidationRequest.BlockSnapshot> blocks, RtpSafetyResult.Code failure)
	{
		private static SnapshotIndexResult success(Set<ChunkPosition> chunks,
				Map<BlockPosition, RtpValidationRequest.BlockSnapshot> blocks)
		{
			return new SnapshotIndexResult(chunks, blocks, null);
		}

		private static SnapshotIndexResult failed(RtpSafetyResult.Code failure)
		{
			return new SnapshotIndexResult(Set.of(), Map.of(), failure);
		}
	}
}
