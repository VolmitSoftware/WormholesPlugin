package art.arcane.wormholes.portal.rtp;

import java.util.Objects;
import java.util.Optional;

public final class RtpAccessResult
{
	private static final RtpAccessResult ALLOWED = new RtpAccessResult(Status.ALLOWED, null);
	private static final RtpAccessResult DENIED = new RtpAccessResult(Status.DENIED, null);

	private final Status status;
	private final Throwable failure;

	private RtpAccessResult(Status status, Throwable failure)
	{
		this.status = status;
		this.failure = failure;
	}

	public static RtpAccessResult allowedResult()
	{
		return ALLOWED;
	}

	public static RtpAccessResult deniedResult()
	{
		return DENIED;
	}

	public static RtpAccessResult failureResult(Throwable failure)
	{
		return new RtpAccessResult(Status.FAILURE, Objects.requireNonNull(failure, "failure"));
	}

	public Status status()
	{
		return status;
	}

	public boolean allowed()
	{
		return status == Status.ALLOWED;
	}

	public Optional<Throwable> failure()
	{
		return Optional.ofNullable(failure);
	}

	public enum Status
	{
		ALLOWED,
		DENIED,
		FAILURE
	}
}
