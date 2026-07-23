package art.arcane.wormholes.portal.rtp;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

import org.bukkit.World;

import art.arcane.volmlib.util.bukkit.WorldIdentity;

public final class RtpPortalEditorModel
{
	static final double MINIMUM_COORDINATE = -30_000_000.0D;
	static final double MAXIMUM_COORDINATE = 30_000_000.0D;
	static final int MAXIMUM_RADIUS = 30_000_000;
	static final long MINIMUM_CYCLE_MILLIS = 15_000L;
	static final long MAXIMUM_CYCLE_MILLIS = 86_400_000L;
	static final long MINIMUM_LEASE_MILLIS = 5_000L;
	static final long MAXIMUM_LEASE_MILLIS = 600_000L;
	static final long MINIMUM_RESERVATION_MILLIS = 5_000L;
	static final long MAXIMUM_RESERVATION_MILLIS = 300_000L;

	private RtpPortalEditorModel()
	{
	}

	public static RtpSettings applyMutation(
			RtpSettings settings,
			Mutation mutation,
			World sourceWorld,
			RtpSettings.WorldResolver worldResolver)
	{
		RtpSettings requiredSettings = Objects.requireNonNull(settings, "settings");
		Mutation requiredMutation = Objects.requireNonNull(mutation, "mutation");
		World requiredSourceWorld = Objects.requireNonNull(sourceWorld, "sourceWorld");
		RtpSettings.WorldResolver requiredWorldResolver = Objects.requireNonNull(worldResolver, "worldResolver");
		if(!WorldIdentity.serialize(requiredSourceWorld).equals(requiredSettings.getSourceWorldKey()))
		{
			throw new IllegalArgumentException("source world does not match RTP settings");
		}

		RtpSettings.Builder builder = requiredSettings.toBuilder();
		switch(requiredMutation)
		{
			case TargetWorldMutation change ->
			{
				World targetWorld = requiredWorldResolver.resolve(change.worldKey());
				if(targetWorld == null)
				{
					throw new IllegalArgumentException("target world is not loaded: " + change.worldKey());
				}
				builder.targetWorld(targetWorld);
			}
			case CenterModeMutation change ->
			{
				builder.centerMode(change.mode());
				if(change.customX() != null)
				{
					builder.customCenter(change.customX().doubleValue(), change.customZ().doubleValue());
				}
			}
			case ResetCenterTargetMutation ignored -> builder
					.targetWorld(requiredSourceWorld)
					.centerMode(RtpCenterMode.PORTAL_RELATIVE)
					.clearCustomCenter();
			case CustomCenterMutation change -> builder.customCenter(change.x(), change.z());
			case RadiiMutation change -> builder.radii(change.minimumRadius(), change.maximumRadius());
			case VerticalModeMutation change -> builder.verticalMode(change.mode());
			case YMutation change -> builder
					.yBounds(change.lowerY(), change.upperY())
					.preferredY(change.preferredY());
			case AllocationMutation change -> builder.allocationMode(change.mode());
			case RotationMutation change -> builder.rotationMode(change.mode());
			case CycleDurationMutation change -> builder.cycleDurationMillis(change.durationMillis());
			case LeaseIdleMutation change -> builder.leaseIdleMillis(change.durationMillis());
			case ReservationTimeoutMutation change -> builder.privateReleaseMillis(change.durationMillis());
			case RimMutation change -> builder.rimEnabled(change.enabled());
			case SoundMutation change -> builder.soundEnabled(change.enabled());
		}
		return builder.build();
	}

	public sealed interface Mutation permits AllocationMutation, CenterModeMutation, CustomCenterMutation,
			CycleDurationMutation, LeaseIdleMutation, RadiiMutation, ReservationTimeoutMutation,
			ResetCenterTargetMutation, RimMutation, RotationMutation, SoundMutation, TargetWorldMutation,
			VerticalModeMutation, YMutation
	{
	}

	public enum ManualAction
	{
		REROLL,
		REBUILD_POOL
	}

	public enum StatusState
	{
		IDLE,
		WARMING,
		READY,
		REROLLING,
		BACKOFF,
		TARGET_WORLD_UNAVAILABLE,
		INTEGRATION_FAILED,
		FAILED
	}

	public record EditorSnapshot(
			long configurationRevision,
			String title,
			SettingsSnapshot settings,
			StatusSnapshot status,
			List<WorldOption> loadedWorlds,
			double sourceCenterX,
			double sourceCenterZ)
	{
		public EditorSnapshot
		{
			if(configurationRevision < 0L)
			{
				throw new IllegalArgumentException("configurationRevision must be non-negative");
			}
			title = requireText(title, "title");
			Objects.requireNonNull(settings, "settings");
			Objects.requireNonNull(status, "status");
			if(!Double.isFinite(sourceCenterX) || !Double.isFinite(sourceCenterZ))
			{
				throw new IllegalArgumentException("source center must be finite");
			}
			ArrayList<WorldOption> sortedWorlds = new ArrayList<WorldOption>(Objects.requireNonNull(loadedWorlds, "loadedWorlds"));
			sortedWorlds.sort(Comparator.comparing(WorldOption::key, String.CASE_INSENSITIVE_ORDER).thenComparing(WorldOption::key));
			loadedWorlds = List.copyOf(sortedWorlds);
		}

		public WorldOption world(String key)
		{
			for(WorldOption world : loadedWorlds)
			{
				if(world.key().equalsIgnoreCase(key))
				{
					return world;
				}
			}
			return null;
		}
	}

	public record SettingsSnapshot(
			String sourceWorldKey,
			String targetWorldKey,
			RtpCenterMode centerMode,
			Double customCenterX,
			Double customCenterZ,
			int minimumRadius,
			int maximumRadius,
			RtpVerticalMode verticalMode,
			int lowerY,
			int upperY,
			int preferredY,
			RtpAllocationMode allocationMode,
			RtpRotationMode rotationMode,
			long cycleDurationMillis,
			long leaseIdleMillis,
			long reservationTimeoutMillis,
			boolean rimEnabled,
			boolean soundEnabled)
	{
		public SettingsSnapshot
		{
			sourceWorldKey = requireText(sourceWorldKey, "sourceWorldKey");
			targetWorldKey = requireText(targetWorldKey, "targetWorldKey");
			Objects.requireNonNull(centerMode, "centerMode");
			validateCoordinatePair(customCenterX, customCenterZ);
			if(centerMode == RtpCenterMode.CUSTOM && customCenterX == null)
			{
				throw new IllegalArgumentException("custom center requires coordinates");
			}
			if(minimumRadius < 0 || maximumRadius <= minimumRadius || maximumRadius > MAXIMUM_RADIUS)
			{
				throw new IllegalArgumentException("radius bounds are invalid");
			}
			Objects.requireNonNull(verticalMode, "verticalMode");
			if(lowerY > preferredY || preferredY > upperY)
			{
				throw new IllegalArgumentException("Y bounds must contain preferred Y");
			}
			Objects.requireNonNull(allocationMode, "allocationMode");
			Objects.requireNonNull(rotationMode, "rotationMode");
			if(cycleDurationMillis < MINIMUM_CYCLE_MILLIS || cycleDurationMillis > MAXIMUM_CYCLE_MILLIS)
			{
				throw new IllegalArgumentException("cycle duration is outside editor bounds");
			}
			if(leaseIdleMillis < MINIMUM_LEASE_MILLIS || leaseIdleMillis > MAXIMUM_LEASE_MILLIS)
			{
				throw new IllegalArgumentException("lease idle duration is outside editor bounds");
			}
			if(reservationTimeoutMillis < MINIMUM_RESERVATION_MILLIS || reservationTimeoutMillis > MAXIMUM_RESERVATION_MILLIS)
			{
				throw new IllegalArgumentException("reservation timeout is outside editor bounds");
			}
		}

		public static SettingsSnapshot from(RtpSettings settings)
		{
			RtpSettings requiredSettings = Objects.requireNonNull(settings, "settings");
			return new SettingsSnapshot(
					requiredSettings.getSourceWorldKey(),
					requiredSettings.getTargetWorldKey(),
					requiredSettings.getCenterMode(),
					requiredSettings.getCustomCenterX(),
					requiredSettings.getCustomCenterZ(),
					requiredSettings.getMinimumRadius(),
					requiredSettings.getMaximumRadius(),
					requiredSettings.getVerticalMode(),
					requiredSettings.getLowerY(),
					requiredSettings.getUpperY(),
					requiredSettings.getPreferredY(),
					requiredSettings.getAllocationMode(),
					requiredSettings.getRotationMode(),
					requiredSettings.getCycleDurationMillis(),
					requiredSettings.getLeaseIdleMillis(),
					requiredSettings.getPrivateReleaseMillis(),
					requiredSettings.isRimEnabled(),
					requiredSettings.isSoundEnabled());
		}
	}

	public record StatusSnapshot(
			StatusState state,
			boolean targetWorldAvailable,
			boolean integrationAvailable,
			boolean activeReady,
			boolean standbyReady,
			long remainingCycleMillis,
			long remainingBackoffMillis,
			int freeCount,
			int reservedCount,
			int validatingCount,
			int inFlightCount)
	{
		public StatusSnapshot
		{
			Objects.requireNonNull(state, "state");
			if(remainingCycleMillis < 0L || remainingBackoffMillis < 0L || freeCount < 0 || reservedCount < 0 || validatingCount < 0 || inFlightCount < 0)
			{
				throw new IllegalArgumentException("status values cannot be negative");
			}
		}

		public static StatusSnapshot from(RtpRuntimeSnapshot runtime, StatusContext context)
		{
			RtpRuntimeSnapshot requiredRuntime = Objects.requireNonNull(runtime, "runtime");
			StatusContext requiredContext = Objects.requireNonNull(context, "context");
			long remainingCycle = requiredRuntime.nextRotationAtMillis() <= 0L
					? 0L
					: Math.max(0L, requiredRuntime.nextRotationAtMillis() - requiredContext.nowMillis());
			long remainingBackoff = Math.max(0L, requiredContext.backoffUntilMillis() - requiredContext.nowMillis());
			int validating = requiredRuntime.searchInFlight() ? 1 : 0;
			int inFlight = requiredRuntime.sharedClaims() + requiredRuntime.playerClaims() + requiredRuntime.anonymousClaims();
			return new StatusSnapshot(
					statusState(requiredRuntime, requiredContext),
					requiredContext.targetWorldAvailable(),
					requiredContext.integrationAvailable(),
					requiredRuntime.active() != null,
					requiredRuntime.standby() != null,
					remainingCycle,
					remainingBackoff,
					requiredRuntime.freeCandidates(),
					requiredRuntime.reservedPlayers(),
					validating,
					inFlight);
		}

		private static StatusState statusState(RtpRuntimeSnapshot runtime, StatusContext context)
		{
			if(!context.targetWorldAvailable())
			{
				return StatusState.TARGET_WORLD_UNAVAILABLE;
			}
			if(!context.integrationAvailable())
			{
				return StatusState.INTEGRATION_FAILED;
			}
			if(context.backoffUntilMillis() > context.nowMillis())
			{
				return StatusState.BACKOFF;
			}
			if(runtime.rerolling())
			{
				return StatusState.REROLLING;
			}
			if(runtime.ready())
			{
				return StatusState.READY;
			}
			if(runtime.searchInFlight() || runtime.timedRotationPending())
			{
				return StatusState.WARMING;
			}
			return StatusState.IDLE;
		}
	}

	public record StatusContext(boolean targetWorldAvailable, boolean integrationAvailable, long nowMillis, long backoffUntilMillis)
	{
		public StatusContext
		{
			if(nowMillis < 0L || backoffUntilMillis < 0L)
			{
				throw new IllegalArgumentException("status times cannot be negative");
			}
		}
	}

	public record WorldOption(String key, String displayName, int minimumFeetY, int maximumFeetY)
	{
		public WorldOption
		{
			key = requireText(key, "key");
			displayName = requireText(displayName, "displayName");
			if(minimumFeetY > maximumFeetY)
			{
				throw new IllegalArgumentException("minimumFeetY must not exceed maximumFeetY");
			}
		}

		public static WorldOption from(World world)
		{
			World requiredWorld = Objects.requireNonNull(world, "world");
			return new WorldOption(WorldIdentity.serialize(requiredWorld), requiredWorld.getName(),
					requiredWorld.getMinHeight() + 1, requiredWorld.getMaxHeight() - 2);
		}
	}

	public record TargetWorldMutation(String worldKey) implements Mutation
	{
		public TargetWorldMutation
		{
			worldKey = requireText(worldKey, "worldKey");
		}
	}

	public record CenterModeMutation(RtpCenterMode mode, Double customX, Double customZ) implements Mutation
	{
		public CenterModeMutation
		{
			Objects.requireNonNull(mode, "mode");
			validateCoordinatePair(customX, customZ);
			if(mode == RtpCenterMode.CUSTOM && customX == null)
			{
				throw new IllegalArgumentException("custom center requires coordinates");
			}
		}
	}

	public record ResetCenterTargetMutation() implements Mutation
	{
	}

	public record CustomCenterMutation(double x, double z) implements Mutation
	{
		public CustomCenterMutation
		{
			validateCoordinate(x, "x");
			validateCoordinate(z, "z");
		}
	}

	public record RadiiMutation(int minimumRadius, int maximumRadius) implements Mutation
	{
		public RadiiMutation
		{
			if(minimumRadius < 0 || maximumRadius <= minimumRadius || maximumRadius > MAXIMUM_RADIUS)
			{
				throw new IllegalArgumentException("radius bounds are invalid");
			}
		}
	}

	public record VerticalModeMutation(RtpVerticalMode mode) implements Mutation
	{
		public VerticalModeMutation
		{
			Objects.requireNonNull(mode, "mode");
		}
	}

	public record YMutation(int lowerY, int upperY, int preferredY) implements Mutation
	{
		public YMutation
		{
			if(lowerY > preferredY || preferredY > upperY)
			{
				throw new IllegalArgumentException("Y bounds must contain preferred Y");
			}
		}
	}

	public record AllocationMutation(RtpAllocationMode mode) implements Mutation
	{
		public AllocationMutation
		{
			Objects.requireNonNull(mode, "mode");
		}
	}

	public record RotationMutation(RtpRotationMode mode) implements Mutation
	{
		public RotationMutation
		{
			Objects.requireNonNull(mode, "mode");
		}
	}

	public record CycleDurationMutation(long durationMillis) implements Mutation
	{
		public CycleDurationMutation
		{
			if(durationMillis < MINIMUM_CYCLE_MILLIS || durationMillis > MAXIMUM_CYCLE_MILLIS)
			{
				throw new IllegalArgumentException("cycle duration is outside editor bounds");
			}
		}
	}

	public record LeaseIdleMutation(long durationMillis) implements Mutation
	{
		public LeaseIdleMutation
		{
			if(durationMillis < MINIMUM_LEASE_MILLIS || durationMillis > MAXIMUM_LEASE_MILLIS)
			{
				throw new IllegalArgumentException("lease idle duration is outside editor bounds");
			}
		}
	}

	public record ReservationTimeoutMutation(long durationMillis) implements Mutation
	{
		public ReservationTimeoutMutation
		{
			if(durationMillis < MINIMUM_RESERVATION_MILLIS || durationMillis > MAXIMUM_RESERVATION_MILLIS)
			{
				throw new IllegalArgumentException("reservation timeout is outside editor bounds");
			}
		}
	}

	public record RimMutation(boolean enabled) implements Mutation
	{
	}

	public record SoundMutation(boolean enabled) implements Mutation
	{
	}

	private static void validateCoordinatePair(Double x, Double z)
	{
		if((x == null) != (z == null))
		{
			throw new IllegalArgumentException("custom coordinates must both be present or absent");
		}
		if(x != null)
		{
			validateCoordinate(x.doubleValue(), "customX");
			validateCoordinate(z.doubleValue(), "customZ");
		}
	}

	private static void validateCoordinate(double value, String name)
	{
		if(!Double.isFinite(value) || value < MINIMUM_COORDINATE || value > MAXIMUM_COORDINATE)
		{
			throw new IllegalArgumentException(name + " is outside the Minecraft coordinate range");
		}
	}

	private static String requireText(String value, String name)
	{
		String requiredValue = Objects.requireNonNull(value, name);
		if(requiredValue.isBlank())
		{
			throw new IllegalArgumentException(name + " must not be blank");
		}
		return requiredValue;
	}
}
