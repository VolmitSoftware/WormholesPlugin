package art.arcane.wormholes.portal.rtp;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

public final class RtpRimRenderer
{
	private static final long FAILURE_PULSE_MILLIS = 2_000L;

	public Optional<Sample> calculate(Input input)
	{
		Input requiredInput = Objects.requireNonNull(input, "input");
		if(!requiredInput.enabled() || !requiredInput.attended() || !requiredInput.view().isForViewer(requiredInput.viewerId()))
		{
			return Optional.empty();
		}
		return switch(requiredInput.view().state())
		{
			case NONE, DENIED -> Optional.empty();
			case WARMING -> Optional.of(new Sample(Color.YELLOW, 0.0D));
			case FAILED -> failedSample(requiredInput.elapsedMillis());
			case READY -> Optional.of(readySample(requiredInput));
		};
	}

	private Optional<Sample> failedSample(long elapsedMillis)
	{
		if(elapsedMillis >= FAILURE_PULSE_MILLIS)
		{
			return Optional.empty();
		}
		return Optional.of(new Sample(Color.RED, 1.0D));
	}

	private Sample readySample(Input input)
	{
		return switch(input.phase())
		{
			case CLOSING -> new Sample(Color.RED, 1.0D);
			case PREPARING -> new Sample(Color.YELLOW, 0.0D);
			case READY -> switch(input.rotationMode())
			{
				case STATIC, ON_TRAVERSAL -> new Sample(Color.GREEN, 1.0D);
				case TIMED -> timedSample(input.elapsedMillis(), input.durationMillis());
			};
		};
	}

	private Sample timedSample(long elapsedMillis, long durationMillis)
	{
		if(durationMillis <= 0L)
		{
			throw new IllegalArgumentException("durationMillis must be positive for TIMED readiness");
		}
		double progress = Math.max(0.0D, Math.min(1.0D, (double) elapsedMillis / (double) durationMillis));
		return new Sample(interpolateTimedColor(progress), progress);
	}

	private Color interpolateTimedColor(double progress)
	{
		if(progress <= 0.5D)
		{
			double localProgress = progress * 2.0D;
			return new Color((int) Math.round(255.0D * localProgress), 255, 0);
		}
		double localProgress = (progress - 0.5D) * 2.0D;
		return new Color(255, (int) Math.round(255.0D * (1.0D - localProgress)), 0);
	}

	public enum Phase
	{
		READY,
		CLOSING,
		PREPARING
	}

	public record Input(
			UUID viewerId,
			RtpProjectionView view,
			boolean enabled,
			boolean attended,
			RtpRotationMode rotationMode,
			Phase phase,
			long elapsedMillis,
			long durationMillis)
	{
		public Input
		{
			Objects.requireNonNull(viewerId, "viewerId");
			Objects.requireNonNull(view, "view");
			Objects.requireNonNull(rotationMode, "rotationMode");
			Objects.requireNonNull(phase, "phase");
			if(elapsedMillis < 0L)
			{
				throw new IllegalArgumentException("elapsedMillis must be non-negative");
			}
			if(durationMillis < 0L)
			{
				throw new IllegalArgumentException("durationMillis must be non-negative");
			}
		}
	}

	public record Sample(Color color, double progress)
	{
		public Sample
		{
			Objects.requireNonNull(color, "color");
			if(!Double.isFinite(progress) || progress < 0.0D || progress > 1.0D)
			{
				throw new IllegalArgumentException("progress must be between zero and one");
			}
		}
	}

	public record Color(int red, int green, int blue)
	{
		public static final Color GREEN = new Color(0, 255, 0);
		public static final Color YELLOW = new Color(255, 255, 0);
		public static final Color RED = new Color(255, 0, 0);

		public Color
		{
			requireChannel(red, "red");
			requireChannel(green, "green");
			requireChannel(blue, "blue");
		}

		private static void requireChannel(int value, String name)
		{
			if(value < 0 || value > 255)
			{
				throw new IllegalArgumentException(name + " must be between zero and 255");
			}
		}
	}
}
