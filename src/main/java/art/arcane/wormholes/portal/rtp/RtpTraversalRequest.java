package art.arcane.wormholes.portal.rtp;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

public final class RtpTraversalRequest
{
	private final long generation;
	private final Runnable terminalCleanup;
	private final AtomicReference<State> state;
	private final AtomicBoolean cleanupStarted;

	private RtpTraversalRequest(long generation, Runnable terminalCleanup)
	{
		this.generation = generation;
		this.terminalCleanup = Objects.requireNonNull(terminalCleanup);
		this.state = new AtomicReference<>(State.PREPARING);
		this.cleanupStarted = new AtomicBoolean(false);
	}

	public static RtpTraversalRequest preparing(long generation, Runnable terminalCleanup)
	{
		return new RtpTraversalRequest(generation, terminalCleanup);
	}

	public long generation()
	{
		return generation;
	}

	public State state()
	{
		return state.get();
	}

	public boolean isTerminal()
	{
		return state().isTerminal();
	}

	public boolean markDispatched(long currentGeneration)
	{
		return transition(currentGeneration, State.PREPARING, State.DISPATCHED);
	}

	public boolean markSucceeded(long currentGeneration)
	{
		return transition(currentGeneration, State.DISPATCHED, State.SUCCEEDED);
	}

	public boolean markFailed(long currentGeneration)
	{
		return transition(currentGeneration, State.DISPATCHED, State.FAILED);
	}

	public boolean timeoutPreparing(long currentGeneration)
	{
		return transition(currentGeneration, State.PREPARING, State.TIMED_OUT);
	}

	public boolean cancel(long currentGeneration)
	{
		return transition(currentGeneration, State.PREPARING, State.CANCELLED);
	}

	public boolean reconcileDispatchedTimeout(long currentGeneration, boolean succeeded)
	{
		return transition(currentGeneration, State.DISPATCHED, succeeded ? State.SUCCEEDED : State.FAILED);
	}

	private boolean transition(long currentGeneration, State expected, State next)
	{
		if (currentGeneration != generation || !state.compareAndSet(expected, next))
		{
			return false;
		}
		if (next.isTerminal())
		{
			runTerminalCleanup();
		}
		return true;
	}

	private void runTerminalCleanup()
	{
		if (cleanupStarted.compareAndSet(false, true))
		{
			terminalCleanup.run();
		}
	}

	public enum State
	{
		PREPARING,
		DISPATCHED,
		SUCCEEDED,
		FAILED,
		TIMED_OUT,
		CANCELLED;

		public boolean isTerminal()
		{
			return switch (this)
			{
				case SUCCEEDED, FAILED, TIMED_OUT, CANCELLED -> true;
				case PREPARING, DISPATCHED -> false;
			};
		}
	}
}
