package art.arcane.wormholes.portal.rtp;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public final class RtpSampler
{
	private static final long GOLDEN_GAMMA = 0x9E3779B97F4A7C15L;
	private static final long GENERATION_SALT = 0xD1B54A32D192ED03L;
	private static final long ATTEMPT_SALT = 0x94D049BB133111EBL;
	private static final double UNIT_SCALE = 0x1.0p-53;

	private final double centerX;
	private final double centerZ;
	private final long seed;

	public RtpSampler(double centerX, double centerZ, long seed)
	{
		if(!Double.isFinite(centerX) || !Double.isFinite(centerZ))
		{
			throw new IllegalArgumentException("center coordinates must be finite");
		}
		this.centerX = centerX;
		this.centerZ = centerZ;
		this.seed = seed;
	}

	public RtpDestination sample(RtpSettings settings, long generation, int attempt)
	{
		RtpSettings requiredSettings = Objects.requireNonNull(settings, "settings");
		if(attempt < 0)
		{
			throw new IllegalArgumentException("attempt must be non-negative");
		}
		long state = mix64(seed ^ mix64(generation + GENERATION_SALT) ^ mix64((long) attempt + ATTEMPT_SALT));
		double radiusUnit = toUnitDouble(mix64(state + GOLDEN_GAMMA));
		double angleUnit = toUnitDouble(mix64(state + GOLDEN_GAMMA * 2L));
		double minimumRadius = requiredSettings.getMinimumRadius();
		double maximumRadius = requiredSettings.getMaximumRadius();
		double minimumSquared = minimumRadius * minimumRadius;
		double maximumSquared = maximumRadius * maximumRadius;
		double radius = Math.sqrt(minimumSquared + radiusUnit * (maximumSquared - minimumSquared));
		double angle = Math.PI * 2.0D * angleUnit;
		int blockX = floorToInt(centerX + radius * Math.cos(angle));
		int blockZ = floorToInt(centerZ + radius * Math.sin(angle));
		int feetY = requiredSettings.getVerticalMode() == RtpVerticalMode.SURFACE
				? requiredSettings.getUpperY()
				: requiredSettings.getPreferredY();
		return new RtpDestination(requiredSettings.getTargetWorldKey(), blockX, feetY, blockZ, generation, attempt);
	}

	public List<Integer> feetYProbeOrder(RtpSettings settings)
	{
		RtpSettings requiredSettings = Objects.requireNonNull(settings, "settings");
		return feetYProbeOrder(requiredSettings, requiredSettings.getUpperY());
	}

	public List<Integer> feetYProbeOrder(RtpSettings settings, int surfaceFeetYHint)
	{
		RtpSettings requiredSettings = Objects.requireNonNull(settings, "settings");
		return switch(requiredSettings.getVerticalMode())
		{
			case SURFACE -> surfaceProbeOrder(requiredSettings, surfaceFeetYHint);
			case PREFERRED_AVERAGE -> preferredProbeOrder(requiredSettings);
		};
	}

	private List<Integer> surfaceProbeOrder(RtpSettings settings, int surfaceFeetYHint)
	{
		int lowerY = settings.getLowerY();
		int upperY = settings.getUpperY();
		if(surfaceFeetYHint < lowerY || surfaceFeetYHint > upperY)
		{
			return List.of();
		}
		return List.of(Integer.valueOf(surfaceFeetYHint));
	}

	private List<Integer> preferredProbeOrder(RtpSettings settings)
	{
		int lowerY = settings.getLowerY();
		int upperY = settings.getUpperY();
		int preferredY = clamp(settings.getPreferredY(), lowerY, upperY);
		List<Integer> probes = new ArrayList<Integer>(upperY - lowerY + 1);
		probes.add(Integer.valueOf(preferredY));
		for(int distance = 1; probes.size() < upperY - lowerY + 1; distance++)
		{
			int above = preferredY + distance;
			if(above <= upperY)
			{
				probes.add(Integer.valueOf(above));
			}
			int below = preferredY - distance;
			if(below >= lowerY)
			{
				probes.add(Integer.valueOf(below));
			}
		}
		return List.copyOf(probes);
	}

	private static long mix64(long value)
	{
		long mixed = value;
		mixed = (mixed ^ mixed >>> 30) * 0xBF58476D1CE4E5B9L;
		mixed = (mixed ^ mixed >>> 27) * 0x94D049BB133111EBL;
		return mixed ^ mixed >>> 31;
	}

	private static double toUnitDouble(long value)
	{
		return (double) (value >>> 11) * UNIT_SCALE;
	}

	private static int floorToInt(double value)
	{
		if(!Double.isFinite(value) || value < Integer.MIN_VALUE || value >= (double) Integer.MAX_VALUE + 1.0D)
		{
			throw new IllegalArgumentException("sampled coordinate is outside integer block bounds");
		}
		return (int) Math.floor(value);
	}

	private static int clamp(int value, int minimum, int maximum)
	{
		return Math.max(minimum, Math.min(maximum, value));
	}
}
