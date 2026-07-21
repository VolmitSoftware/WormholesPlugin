package art.arcane.wormholes.portal.rtp;

import java.util.Locale;
import java.util.Objects;

import org.bukkit.World;

import art.arcane.volmlib.util.bukkit.WorldIdentity;
import art.arcane.wormholes.util.JSONObject;

public final class RtpSettings
{
	private static final int DEFAULT_MINIMUM_RADIUS = 512;
	private static final int DEFAULT_MAXIMUM_RADIUS = 4096;
	private static final long DEFAULT_CYCLE_DURATION_MILLIS = 300_000L;
	private static final long DEFAULT_LEASE_IDLE_MILLIS = 30_000L;
	private static final long DEFAULT_PRIVATE_RELEASE_MILLIS = 15_000L;
	private static final long MINIMUM_CYCLE_DURATION_MILLIS = 15_000L;
	private static final long MAXIMUM_CYCLE_DURATION_MILLIS = 86_400_000L;
	private static final long MINIMUM_LEASE_IDLE_MILLIS = 5_000L;
	private static final long MAXIMUM_LEASE_IDLE_MILLIS = 600_000L;
	private static final long MINIMUM_PRIVATE_RELEASE_MILLIS = 5_000L;
	private static final long MAXIMUM_PRIVATE_RELEASE_MILLIS = 300_000L;

	private final World sourceWorld;
	private final String sourceWorldKey;
	private final World targetWorld;
	private final String targetWorldOverrideKey;
	private final RtpCenterMode centerMode;
	private final Double customCenterX;
	private final Double customCenterZ;
	private final int minimumRadius;
	private final int maximumRadius;
	private final RtpVerticalMode verticalMode;
	private final int lowerY;
	private final int upperY;
	private final int preferredY;
	private final RtpAllocationMode allocationMode;
	private final RtpRotationMode rotationMode;
	private final long cycleDurationMillis;
	private final long leaseIdleMillis;
	private final long privateReleaseMillis;
	private final boolean rimEnabled;

	private RtpSettings(Builder builder)
	{
		sourceWorld = builder.sourceWorld;
		sourceWorldKey = WorldIdentity.serialize(sourceWorld);
		targetWorld = builder.targetWorld;
		targetWorldOverrideKey = builder.targetWorldOverrideKey;
		centerMode = Objects.requireNonNull(builder.centerMode, "centerMode");
		customCenterX = builder.customCenterX;
		customCenterZ = builder.customCenterZ;
		validateCustomCenter(centerMode, customCenterX, customCenterZ);
		if(builder.minimumRadius < 0)
		{
			throw new IllegalArgumentException("minimum radius must be non-negative");
		}
		if(builder.maximumRadius <= builder.minimumRadius)
		{
			throw new IllegalArgumentException("maximum radius must be greater than minimum radius");
		}
		minimumRadius = builder.minimumRadius;
		maximumRadius = builder.maximumRadius;
		verticalMode = Objects.requireNonNull(builder.verticalMode, "verticalMode");
		World boundsWorld = targetWorld == null ? sourceWorld : targetWorld;
		int minimumFeetY = boundsWorld.getMinHeight() + 1;
		int maximumFeetY = boundsWorld.getMaxHeight() - 2;
		int clampedLowerY = clamp(builder.lowerY, minimumFeetY, maximumFeetY);
		int clampedUpperY = clamp(builder.upperY, minimumFeetY, maximumFeetY);
		if(clampedLowerY > clampedUpperY)
		{
			throw new IllegalArgumentException("lower Y must not exceed upper Y");
		}
		lowerY = clampedLowerY;
		upperY = clampedUpperY;
		preferredY = clamp(builder.preferredY, lowerY, upperY);
		allocationMode = Objects.requireNonNull(builder.allocationMode, "allocationMode");
		rotationMode = Objects.requireNonNull(builder.rotationMode, "rotationMode");
		cycleDurationMillis = clamp(builder.cycleDurationMillis, MINIMUM_CYCLE_DURATION_MILLIS, MAXIMUM_CYCLE_DURATION_MILLIS);
		leaseIdleMillis = clamp(builder.leaseIdleMillis, MINIMUM_LEASE_IDLE_MILLIS, MAXIMUM_LEASE_IDLE_MILLIS);
		privateReleaseMillis = clamp(builder.privateReleaseMillis, MINIMUM_PRIVATE_RELEASE_MILLIS, MAXIMUM_PRIVATE_RELEASE_MILLIS);
		rimEnabled = builder.rimEnabled;
	}

	public static RtpSettings defaults(World world)
	{
		return builder(world).build();
	}

	public static Builder builder(World world)
	{
		return new Builder(world);
	}

	public static RtpSettings fromJson(JSONObject json, WorldResolver resolver)
	{
		JSONObject requiredJson = Objects.requireNonNull(json, "json");
		WorldResolver requiredResolver = Objects.requireNonNull(resolver, "resolver");
		World sourceWorld = Objects.requireNonNull(requiredResolver.resolve(null), "source world");
		Builder builder = builder(sourceWorld);
		String targetWorldKey = requiredJson.optString("targetWorldKey", "").trim();
		if(!targetWorldKey.isEmpty())
		{
			try
			{
				String normalizedTargetWorldKey = WorldIdentity.parse(targetWorldKey).toString();
				if(WorldIdentity.serialize(sourceWorld).equals(normalizedTargetWorldKey))
				{
					builder.targetWorld(sourceWorld);
				}
				else
				{
					builder.targetWorld(normalizedTargetWorldKey, requiredResolver.resolve(normalizedTargetWorldKey));
				}
			}
			catch(IllegalArgumentException exception)
			{
				builder.targetWorld(sourceWorld);
			}
		}

		RtpCenterMode centerMode = parseEnum(RtpCenterMode.class, requiredJson.optString("centerMode", ""), RtpCenterMode.PORTAL_RELATIVE);
		Double customCenterX = readFiniteDouble(requiredJson, "customCenterX");
		Double customCenterZ = readFiniteDouble(requiredJson, "customCenterZ");
		if((customCenterX == null) != (customCenterZ == null) || centerMode == RtpCenterMode.CUSTOM && customCenterX == null)
		{
			centerMode = RtpCenterMode.PORTAL_RELATIVE;
			customCenterX = null;
			customCenterZ = null;
		}
		builder.centerMode(centerMode);
		if(customCenterX != null)
		{
			builder.customCenter(customCenterX.doubleValue(), customCenterZ.doubleValue());
		}

		int minimumRadius = requiredJson.optInt("minimumRadius", DEFAULT_MINIMUM_RADIUS);
		int maximumRadius = requiredJson.optInt("maximumRadius", DEFAULT_MAXIMUM_RADIUS);
		if(minimumRadius < 0 || maximumRadius <= minimumRadius)
		{
			minimumRadius = DEFAULT_MINIMUM_RADIUS;
			maximumRadius = DEFAULT_MAXIMUM_RADIUS;
		}
		builder.radii(minimumRadius, maximumRadius);
		builder.verticalMode(parseEnum(RtpVerticalMode.class, requiredJson.optString("verticalMode", ""), RtpVerticalMode.SURFACE));

		World boundsWorld = builder.targetWorld == null ? sourceWorld : builder.targetWorld;
		int defaultLowerY = boundsWorld.getMinHeight() + 1;
		int defaultUpperY = boundsWorld.getMaxHeight() - 2;
		int defaultPreferredY = clamp(boundsWorld.getSeaLevel() + 1, defaultLowerY, defaultUpperY);
		int lowerY = requiredJson.optInt("lowerY", defaultLowerY);
		int upperY = requiredJson.optInt("upperY", defaultUpperY);
		int clampedLowerY = clamp(lowerY, defaultLowerY, defaultUpperY);
		int clampedUpperY = clamp(upperY, defaultLowerY, defaultUpperY);
		if(clampedLowerY > clampedUpperY)
		{
			clampedLowerY = defaultLowerY;
			clampedUpperY = defaultUpperY;
		}
		builder.yBounds(clampedLowerY, clampedUpperY);
		builder.preferredY(requiredJson.optInt("preferredY", defaultPreferredY));
		builder.allocationMode(parseEnum(RtpAllocationMode.class, requiredJson.optString("allocationMode", ""), RtpAllocationMode.SHARED));
		builder.rotationMode(parseEnum(RtpRotationMode.class, requiredJson.optString("rotationMode", ""), RtpRotationMode.STATIC));
		builder.cycleDurationMillis(requiredJson.optLong("cycleDurationMillis", DEFAULT_CYCLE_DURATION_MILLIS));
		builder.leaseIdleMillis(requiredJson.optLong("leaseIdleMillis", DEFAULT_LEASE_IDLE_MILLIS));
		builder.privateReleaseMillis(requiredJson.optLong("privateReleaseMillis", DEFAULT_PRIVATE_RELEASE_MILLIS));
		builder.rimEnabled(requiredJson.optBoolean("rimEnabled", true));
		return builder.build();
	}

	public JSONObject toJson()
	{
		JSONObject json = new JSONObject();
		if(targetWorldOverrideKey != null)
		{
			json.put("targetWorldKey", targetWorldOverrideKey);
		}
		json.put("centerMode", centerMode.name());
		if(customCenterX != null)
		{
			json.put("customCenterX", customCenterX.doubleValue());
			json.put("customCenterZ", customCenterZ.doubleValue());
		}
		json.put("minimumRadius", minimumRadius);
		json.put("maximumRadius", maximumRadius);
		json.put("verticalMode", verticalMode.name());
		json.put("lowerY", lowerY);
		json.put("upperY", upperY);
		json.put("preferredY", preferredY);
		json.put("allocationMode", allocationMode.name());
		json.put("rotationMode", rotationMode.name());
		json.put("cycleDurationMillis", cycleDurationMillis);
		json.put("leaseIdleMillis", leaseIdleMillis);
		json.put("privateReleaseMillis", privateReleaseMillis);
		json.put("rimEnabled", rimEnabled);
		return json;
	}

	public String getSourceWorldKey()
	{
		return sourceWorldKey;
	}

	public World getTargetWorld()
	{
		return targetWorld;
	}

	public String getTargetWorldKey()
	{
		return targetWorldOverrideKey == null ? sourceWorldKey : targetWorldOverrideKey;
	}

	public boolean isSourceWorldTarget()
	{
		return targetWorldOverrideKey == null;
	}

	public RtpCenterMode getCenterMode()
	{
		return centerMode;
	}

	public Double getCustomCenterX()
	{
		return customCenterX;
	}

	public Double getCustomCenterZ()
	{
		return customCenterZ;
	}

	public int getMinimumRadius()
	{
		return minimumRadius;
	}

	public int getMaximumRadius()
	{
		return maximumRadius;
	}

	public RtpVerticalMode getVerticalMode()
	{
		return verticalMode;
	}

	public int getLowerY()
	{
		return lowerY;
	}

	public int getUpperY()
	{
		return upperY;
	}

	public int getPreferredY()
	{
		return preferredY;
	}

	public RtpAllocationMode getAllocationMode()
	{
		return allocationMode;
	}

	public RtpRotationMode getRotationMode()
	{
		return rotationMode;
	}

	public long getCycleDurationMillis()
	{
		return cycleDurationMillis;
	}

	public long getLeaseIdleMillis()
	{
		return leaseIdleMillis;
	}

	public long getPrivateReleaseMillis()
	{
		return privateReleaseMillis;
	}

	public boolean isRimEnabled()
	{
		return rimEnabled;
	}

	@Override
	public boolean equals(Object object)
	{
		if(this == object)
		{
			return true;
		}
		if(!(object instanceof RtpSettings other))
		{
			return false;
		}
		return minimumRadius == other.minimumRadius
				&& maximumRadius == other.maximumRadius
				&& lowerY == other.lowerY
				&& upperY == other.upperY
				&& preferredY == other.preferredY
				&& cycleDurationMillis == other.cycleDurationMillis
				&& leaseIdleMillis == other.leaseIdleMillis
				&& privateReleaseMillis == other.privateReleaseMillis
				&& rimEnabled == other.rimEnabled
				&& sourceWorldKey.equals(other.sourceWorldKey)
				&& Objects.equals(targetWorldOverrideKey, other.targetWorldOverrideKey)
				&& centerMode == other.centerMode
				&& Objects.equals(customCenterX, other.customCenterX)
				&& Objects.equals(customCenterZ, other.customCenterZ)
				&& verticalMode == other.verticalMode
				&& allocationMode == other.allocationMode
				&& rotationMode == other.rotationMode;
	}

	@Override
	public int hashCode()
	{
		return Objects.hash(sourceWorldKey, targetWorldOverrideKey, centerMode, customCenterX, customCenterZ,
				Integer.valueOf(minimumRadius), Integer.valueOf(maximumRadius), verticalMode, Integer.valueOf(lowerY),
				Integer.valueOf(upperY), Integer.valueOf(preferredY), allocationMode, rotationMode,
				Long.valueOf(cycleDurationMillis), Long.valueOf(leaseIdleMillis), Long.valueOf(privateReleaseMillis), Boolean.valueOf(rimEnabled));
	}

	private static void validateCustomCenter(RtpCenterMode centerMode, Double customCenterX, Double customCenterZ)
	{
		if((customCenterX == null) != (customCenterZ == null))
		{
			throw new IllegalArgumentException("custom center X and Z must both be present or absent");
		}
		if(customCenterX != null && (!Double.isFinite(customCenterX.doubleValue()) || !Double.isFinite(customCenterZ.doubleValue())))
		{
			throw new IllegalArgumentException("custom center coordinates must be finite");
		}
		if(centerMode == RtpCenterMode.CUSTOM && customCenterX == null)
		{
			throw new IllegalArgumentException("custom center coordinates are required in custom mode");
		}
	}

	private static Double readFiniteDouble(JSONObject json, String key)
	{
		if(!json.has(key))
		{
			return null;
		}
		double value = json.optDouble(key, Double.NaN);
		return Double.isFinite(value) ? Double.valueOf(value) : null;
	}

	private static <E extends Enum<E>> E parseEnum(Class<E> type, String value, E fallback)
	{
		if(value == null || value.isBlank())
		{
			return fallback;
		}
		try
		{
			return Enum.valueOf(type, value.trim().toUpperCase(Locale.ROOT));
		}
		catch(IllegalArgumentException exception)
		{
			return fallback;
		}
	}

	private static int clamp(int value, int minimum, int maximum)
	{
		return Math.max(minimum, Math.min(maximum, value));
	}

	private static long clamp(long value, long minimum, long maximum)
	{
		return Math.max(minimum, Math.min(maximum, value));
	}

	@FunctionalInterface
	public interface WorldResolver
	{
		World resolve(String worldKey);
	}

	public static final class Builder
	{
		private final World sourceWorld;
		private World targetWorld;
		private String targetWorldOverrideKey;
		private RtpCenterMode centerMode;
		private Double customCenterX;
		private Double customCenterZ;
		private int minimumRadius;
		private int maximumRadius;
		private RtpVerticalMode verticalMode;
		private int lowerY;
		private int upperY;
		private int preferredY;
		private RtpAllocationMode allocationMode;
		private RtpRotationMode rotationMode;
		private long cycleDurationMillis;
		private long leaseIdleMillis;
		private long privateReleaseMillis;
		private boolean rimEnabled;

		private Builder(World sourceWorld)
		{
			this.sourceWorld = Objects.requireNonNull(sourceWorld, "sourceWorld");
			targetWorld = sourceWorld;
			targetWorldOverrideKey = null;
			centerMode = RtpCenterMode.PORTAL_RELATIVE;
			customCenterX = null;
			customCenterZ = null;
			minimumRadius = DEFAULT_MINIMUM_RADIUS;
			maximumRadius = DEFAULT_MAXIMUM_RADIUS;
			verticalMode = RtpVerticalMode.SURFACE;
			applyWorldDefaults(sourceWorld);
			allocationMode = RtpAllocationMode.SHARED;
			rotationMode = RtpRotationMode.STATIC;
			cycleDurationMillis = DEFAULT_CYCLE_DURATION_MILLIS;
			leaseIdleMillis = DEFAULT_LEASE_IDLE_MILLIS;
			privateReleaseMillis = DEFAULT_PRIVATE_RELEASE_MILLIS;
			rimEnabled = true;
		}

		public Builder targetWorld(World world)
		{
			World requiredWorld = Objects.requireNonNull(world, "world");
			String worldKey = WorldIdentity.serialize(requiredWorld);
			String sourceKey = WorldIdentity.serialize(sourceWorld);
			targetWorld = requiredWorld;
			targetWorldOverrideKey = sourceKey.equals(worldKey) ? null : worldKey;
			applyWorldDefaults(requiredWorld);
			return this;
		}

		private Builder targetWorld(String worldKey, World world)
		{
			String requiredWorldKey = Objects.requireNonNull(worldKey, "worldKey");
			if(WorldIdentity.serialize(sourceWorld).equals(requiredWorldKey))
			{
				return targetWorld(sourceWorld);
			}
			targetWorldOverrideKey = requiredWorldKey;
			targetWorld = world;
			if(world != null)
			{
				applyWorldDefaults(world);
			}
			return this;
		}

		public Builder centerMode(RtpCenterMode mode)
		{
			centerMode = Objects.requireNonNull(mode, "mode");
			return this;
		}

		public Builder customCenter(double x, double z)
		{
			customCenterX = Double.valueOf(x);
			customCenterZ = Double.valueOf(z);
			return this;
		}

		public Builder radii(int minimum, int maximum)
		{
			minimumRadius = minimum;
			maximumRadius = maximum;
			return this;
		}

		public Builder verticalMode(RtpVerticalMode mode)
		{
			verticalMode = Objects.requireNonNull(mode, "mode");
			return this;
		}

		public Builder yBounds(int lower, int upper)
		{
			lowerY = lower;
			upperY = upper;
			return this;
		}

		public Builder preferredY(int preferred)
		{
			preferredY = preferred;
			return this;
		}

		public Builder allocationMode(RtpAllocationMode mode)
		{
			allocationMode = Objects.requireNonNull(mode, "mode");
			return this;
		}

		public Builder rotationMode(RtpRotationMode mode)
		{
			rotationMode = Objects.requireNonNull(mode, "mode");
			return this;
		}

		public Builder cycleDurationMillis(long durationMillis)
		{
			cycleDurationMillis = durationMillis;
			return this;
		}

		public Builder leaseIdleMillis(long idleMillis)
		{
			leaseIdleMillis = idleMillis;
			return this;
		}

		public Builder privateReleaseMillis(long releaseMillis)
		{
			privateReleaseMillis = releaseMillis;
			return this;
		}

		public Builder rimEnabled(boolean enabled)
		{
			rimEnabled = enabled;
			return this;
		}

		public RtpSettings build()
		{
			return new RtpSettings(this);
		}

		private void applyWorldDefaults(World world)
		{
			lowerY = world.getMinHeight() + 1;
			upperY = world.getMaxHeight() - 2;
			preferredY = clamp(world.getSeaLevel() + 1, lowerY, upperY);
		}
	}
}
