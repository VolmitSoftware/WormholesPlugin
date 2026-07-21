package art.arcane.wormholes.portal.rtp;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

public final class RtpProjectionView
{
	private final State state;
	private final UUID viewerId;
	private final long snapshotRevision;
	private final ReadyData readyData;

	private RtpProjectionView(State state, UUID viewerId, long snapshotRevision, ReadyData readyData)
	{
		this.state = Objects.requireNonNull(state, "state");
		this.viewerId = Objects.requireNonNull(viewerId, "viewerId");
		if(snapshotRevision < 0L)
		{
			throw new IllegalArgumentException("snapshotRevision must be non-negative");
		}
		if((state == State.READY) != (readyData != null))
		{
			throw new IllegalArgumentException("only READY may contain ready data");
		}
		this.snapshotRevision = snapshotRevision;
		this.readyData = readyData;
	}

	public static RtpProjectionView none(UUID viewerId, long snapshotRevision)
	{
		return new RtpProjectionView(State.NONE, viewerId, snapshotRevision, null);
	}

	public static RtpProjectionView warming(UUID viewerId, long snapshotRevision)
	{
		return new RtpProjectionView(State.WARMING, viewerId, snapshotRevision, null);
	}

	public static RtpProjectionView ready(UUID viewerId, long snapshotRevision, ReadyData readyData)
	{
		return new RtpProjectionView(State.READY, viewerId, snapshotRevision, Objects.requireNonNull(readyData, "readyData"));
	}

	public static RtpProjectionView denied(UUID viewerId, long snapshotRevision)
	{
		return new RtpProjectionView(State.DENIED, viewerId, snapshotRevision, null);
	}

	public static RtpProjectionView failed(UUID viewerId, long snapshotRevision)
	{
		return new RtpProjectionView(State.FAILED, viewerId, snapshotRevision, null);
	}

	public State state()
	{
		return state;
	}

	public long snapshotRevision()
	{
		return snapshotRevision;
	}

	public boolean isForViewer(UUID requestedViewerId)
	{
		return viewerId.equals(Objects.requireNonNull(requestedViewerId, "requestedViewerId"));
	}

	public Optional<ReadyData> readyFor(UUID requestedViewerId)
	{
		if(state != State.READY || !isForViewer(requestedViewerId))
		{
			return Optional.empty();
		}
		return Optional.of(readyData);
	}

	public enum State
	{
		NONE,
		WARMING,
		READY,
		DENIED,
		FAILED
	}

	public record Point3(double x, double y, double z)
	{
		public Point3
		{
			requireFinite(x, "x");
			requireFinite(y, "y");
			requireFinite(z, "z");
		}
	}

	public record Vector3(double x, double y, double z)
	{
		public Vector3
		{
			requireFinite(x, "x");
			requireFinite(y, "y");
			requireFinite(z, "z");
		}
	}

	public record SourceFrame(
			String worldKey,
			Point3 center,
			Vector3 right,
			Vector3 up,
			Vector3 forward,
			double width,
			double height,
			long revision)
	{
		public SourceFrame
		{
			worldKey = requireWorldKey(worldKey);
			Objects.requireNonNull(center, "center");
			Objects.requireNonNull(right, "right");
			Objects.requireNonNull(up, "up");
			Objects.requireNonNull(forward, "forward");
			requirePositiveFinite(width, "width");
			requirePositiveFinite(height, "height");
			if(revision < 0L)
			{
				throw new IllegalArgumentException("revision must be non-negative");
			}
		}
	}

	public record Target(
			String worldKey,
			Point3 safeFeet,
			Vector3 right,
			Vector3 up,
			Vector3 forward)
	{
		public Target
		{
			worldKey = requireWorldKey(worldKey);
			Objects.requireNonNull(safeFeet, "safeFeet");
			Objects.requireNonNull(right, "right");
			Objects.requireNonNull(up, "up");
			Objects.requireNonNull(forward, "forward");
		}
	}

	public record ReadyData(UUID routeId, long routeRevision, SourceFrame sourceFrame, Target target)
	{
		public ReadyData
		{
			Objects.requireNonNull(routeId, "routeId");
			if(routeRevision < 0L)
			{
				throw new IllegalArgumentException("routeRevision must be non-negative");
			}
			Objects.requireNonNull(sourceFrame, "sourceFrame");
			Objects.requireNonNull(target, "target");
		}
	}

	private static String requireWorldKey(String worldKey)
	{
		String requiredWorldKey = Objects.requireNonNull(worldKey, "worldKey");
		if(requiredWorldKey.isBlank())
		{
			throw new IllegalArgumentException("worldKey must not be blank");
		}
		return requiredWorldKey;
	}

	private static void requireFinite(double value, String name)
	{
		if(!Double.isFinite(value))
		{
			throw new IllegalArgumentException(name + " must be finite");
		}
	}

	private static void requirePositiveFinite(double value, String name)
	{
		requireFinite(value, name);
		if(value <= 0.0D)
		{
			throw new IllegalArgumentException(name + " must be positive");
		}
	}
}
