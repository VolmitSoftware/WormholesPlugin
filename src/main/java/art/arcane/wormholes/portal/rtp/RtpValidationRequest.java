package art.arcane.wormholes.portal.rtp;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

public final class RtpValidationRequest
{
	private final RtpDestination destination;
	private final String snapshotWorldKey;
	private final int worldMinimumY;
	private final int worldMaximumY;
	private final WorldBorder worldBorder;
	private final Dimension dimension;
	private final int netherLogicalCeiling;
	private final EntityEnvelope entityEnvelope;
	private final boolean surfaceMode;
	private final Set<String> configuredHazards;
	private final List<RegionSnapshot> regionSnapshots;

	private RtpValidationRequest(Builder builder)
	{
		destination = builder.destination;
		snapshotWorldKey = builder.snapshotWorldKey;
		if(builder.worldMinimumY == null || builder.worldMaximumY == null)
		{
			throw new IllegalStateException("world bounds are required");
		}
		worldMinimumY = builder.worldMinimumY.intValue();
		worldMaximumY = builder.worldMaximumY.intValue();
		if(worldMaximumY <= worldMinimumY)
		{
			throw new IllegalArgumentException("world maximum Y must exceed minimum Y");
		}
		worldBorder = Objects.requireNonNull(builder.worldBorder, "worldBorder");
		dimension = Objects.requireNonNull(builder.dimension, "dimension");
		netherLogicalCeiling = builder.netherLogicalCeiling;
		entityEnvelope = Objects.requireNonNull(builder.entityEnvelope, "entityEnvelope");
		surfaceMode = builder.surfaceMode;
		configuredHazards = normalizeHazards(builder.configuredHazards);
		regionSnapshots = List.copyOf(builder.regionSnapshots);
	}

	public static Builder builder(RtpDestination destination)
	{
		return new Builder(destination);
	}

	public RtpDestination destination()
	{
		return destination;
	}

	public String snapshotWorldKey()
	{
		return snapshotWorldKey;
	}

	public int worldMinimumY()
	{
		return worldMinimumY;
	}

	public int worldMaximumY()
	{
		return worldMaximumY;
	}

	public WorldBorder worldBorder()
	{
		return worldBorder;
	}

	public Dimension dimension()
	{
		return dimension;
	}

	public int netherLogicalCeiling()
	{
		return netherLogicalCeiling;
	}

	public EntityEnvelope entityEnvelope()
	{
		return entityEnvelope;
	}

	public boolean surfaceMode()
	{
		return surfaceMode;
	}

	public Set<String> configuredHazards()
	{
		return configuredHazards;
	}

	public List<RegionSnapshot> regionSnapshots()
	{
		return regionSnapshots;
	}

	private static Set<String> normalizeHazards(Set<String> hazards)
	{
		Set<String> normalized = new LinkedHashSet<String>(hazards.size());
		for(String hazard : hazards)
		{
			normalized.add(normalizeMaterialKey(hazard));
		}
		return Set.copyOf(normalized);
	}

	private static String normalizeMaterialKey(String materialKey)
	{
		String normalized = Objects.requireNonNull(materialKey, "materialKey").trim().toLowerCase(Locale.ROOT);
		if(normalized.isEmpty())
		{
			throw new IllegalArgumentException("materialKey must not be blank");
		}
		return normalized;
	}

	public enum Dimension
	{
		OVERWORLD,
		NETHER,
		END
	}

	public record WorldBorder(double minimumX, double minimumZ, double maximumX, double maximumZ)
	{
		public WorldBorder
		{
			if(!Double.isFinite(minimumX) || !Double.isFinite(minimumZ)
					|| !Double.isFinite(maximumX) || !Double.isFinite(maximumZ))
			{
				throw new IllegalArgumentException("world border coordinates must be finite");
			}
			if(maximumX <= minimumX || maximumZ <= minimumZ)
			{
				throw new IllegalArgumentException("world border maximums must exceed minimums");
			}
		}
	}

	public record EntityEnvelope(double minimumXOffset, double maximumXOffset, double minimumYOffset,
			double maximumYOffset, double minimumZOffset, double maximumZOffset)
	{
		public EntityEnvelope
		{
			if(!Double.isFinite(minimumXOffset) || !Double.isFinite(maximumXOffset)
					|| !Double.isFinite(minimumYOffset) || !Double.isFinite(maximumYOffset)
					|| !Double.isFinite(minimumZOffset) || !Double.isFinite(maximumZOffset))
			{
				throw new IllegalArgumentException("entity envelope offsets must be finite");
			}
			if(maximumXOffset <= minimumXOffset || maximumYOffset <= minimumYOffset || maximumZOffset <= minimumZOffset)
			{
				throw new IllegalArgumentException("entity envelope maximums must exceed minimums");
			}
		}

		public static EntityEnvelope baseline()
		{
			return new EntityEnvelope(-0.3D, 0.3D, 0.0D, 1.8D, -0.3D, 0.3D);
		}

		public double width()
		{
			return maximumXOffset - minimumXOffset;
		}

		public double height()
		{
			return maximumYOffset - minimumYOffset;
		}

		public double depth()
		{
			return maximumZOffset - minimumZOffset;
		}
	}

	public record CollisionBox(double minimumX, double minimumY, double minimumZ,
			double maximumX, double maximumY, double maximumZ)
	{
		public CollisionBox
		{
			if(!Double.isFinite(minimumX) || !Double.isFinite(minimumY) || !Double.isFinite(minimumZ)
					|| !Double.isFinite(maximumX) || !Double.isFinite(maximumY) || !Double.isFinite(maximumZ))
			{
				throw new IllegalArgumentException("collision coordinates must be finite");
			}
			if(maximumX <= minimumX || maximumY <= minimumY || maximumZ <= minimumZ)
			{
				throw new IllegalArgumentException("collision maximums must exceed minimums");
			}
		}

		public static CollisionBox fullBlock()
		{
			return new CollisionBox(0.0D, 0.0D, 0.0D, 1.0D, 1.0D, 1.0D);
		}
	}

	public record BlockSnapshot(int x, int y, int z, String materialKey, boolean liquid, boolean active, boolean treePart,
			List<CollisionBox> collisionBoxes)
	{
		public BlockSnapshot
		{
			materialKey = normalizeMaterialKey(materialKey);
			collisionBoxes = List.copyOf(collisionBoxes);
		}

		public static BlockSnapshot air(int x, int y, int z)
		{
			return air(x, y, z, "minecraft:air");
		}

		public static BlockSnapshot air(int x, int y, int z, String materialKey)
		{
			return new BlockSnapshot(x, y, z, materialKey, false, false, false, List.of());
		}

		public static BlockSnapshot solid(int x, int y, int z, String materialKey)
		{
			return new BlockSnapshot(x, y, z, materialKey, false, false, false, List.of(CollisionBox.fullBlock()));
		}

		public static BlockSnapshot of(int x, int y, int z, String materialKey, boolean liquid, boolean active,
				List<CollisionBox> collisionBoxes)
		{
			return new BlockSnapshot(x, y, z, materialKey, liquid, active, false, collisionBoxes);
		}

		public static BlockSnapshot of(int x, int y, int z, String materialKey, boolean liquid, boolean active,
				boolean treePart, List<CollisionBox> collisionBoxes)
		{
			return new BlockSnapshot(x, y, z, materialKey, liquid, active, treePart, collisionBoxes);
		}
	}

	public record RegionSnapshot(String regionKey, int chunkX, int chunkZ, List<BlockSnapshot> blocks)
	{
		public RegionSnapshot
		{
			regionKey = Objects.requireNonNull(regionKey, "regionKey");
			if(regionKey.isBlank())
			{
				throw new IllegalArgumentException("regionKey must not be blank");
			}
			blocks = List.copyOf(blocks);
		}
	}

	public static final class Builder
	{
		private final RtpDestination destination;
		private String snapshotWorldKey;
		private Integer worldMinimumY;
		private Integer worldMaximumY;
		private WorldBorder worldBorder;
		private Dimension dimension;
		private int netherLogicalCeiling;
		private EntityEnvelope entityEnvelope;
		private boolean surfaceMode;
		private Set<String> configuredHazards;
		private List<RegionSnapshot> regionSnapshots;

		private Builder(RtpDestination destination)
		{
			this.destination = Objects.requireNonNull(destination, "destination");
			snapshotWorldKey = destination.worldKey();
			worldMinimumY = null;
			worldMaximumY = null;
			worldBorder = null;
			dimension = Dimension.OVERWORLD;
			netherLogicalCeiling = Integer.MAX_VALUE;
			entityEnvelope = EntityEnvelope.baseline();
			surfaceMode = false;
			configuredHazards = Set.of();
			regionSnapshots = List.of();
		}

		public Builder snapshotWorldKey(String worldKey)
		{
			snapshotWorldKey = Objects.requireNonNull(worldKey, "worldKey");
			return this;
		}

		public Builder worldBounds(int minimumY, int maximumY)
		{
			worldMinimumY = Integer.valueOf(minimumY);
			worldMaximumY = Integer.valueOf(maximumY);
			return this;
		}

		public Builder worldBorder(WorldBorder border)
		{
			worldBorder = Objects.requireNonNull(border, "border");
			return this;
		}

		public Builder dimension(Dimension value)
		{
			dimension = Objects.requireNonNull(value, "value");
			return this;
		}

		public Builder netherLogicalCeiling(int ceiling)
		{
			netherLogicalCeiling = ceiling;
			return this;
		}

		public Builder entityEnvelope(EntityEnvelope envelope)
		{
			entityEnvelope = Objects.requireNonNull(envelope, "envelope");
			return this;
		}

		public Builder surfaceMode(boolean enabled)
		{
			surfaceMode = enabled;
			return this;
		}

		public Builder configuredHazards(Set<String> hazards)
		{
			configuredHazards = Set.copyOf(Objects.requireNonNull(hazards, "hazards"));
			return this;
		}

		public Builder regionSnapshots(List<RegionSnapshot> snapshots)
		{
			regionSnapshots = List.copyOf(Objects.requireNonNull(snapshots, "snapshots"));
			return this;
		}

		public RtpValidationRequest build()
		{
			return new RtpValidationRequest(this);
		}
	}
}
