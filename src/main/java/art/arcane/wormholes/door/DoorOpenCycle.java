package art.arcane.wormholes.door;

/**
 * Thread-safe lifecycle for one placed dimensional door.
 *
 * <p>A successful transit consumes the current physical open cycle. If
 * redstone holds the block open, the cycle remains consumed and no second
 * traveler can trigger it. The state arms again only after the real door is
 * observed closed and then open.</p>
 */
public final class DoorOpenCycle
{
	private Phase phase;
	private boolean physicallyOpen;

	public DoorOpenCycle()
	{
		phase = Phase.CLOSED;
		physicallyOpen = false;
	}

	/**
	 * Records the latest value read from {@code Openable.isOpen()}.
	 */
	public synchronized Phase observe(boolean open)
	{
		boolean wasOpen = physicallyOpen;
		physicallyOpen = open;

		if(phase == Phase.IN_TRANSIT)
		{
			return phase;
		}

		if(!open)
		{
			phase = Phase.CLOSED;
		}
		else if(!wasOpen && phase == Phase.CLOSED)
		{
			phase = Phase.ARMED;
		}

		return phase;
	}

	/**
	 * Atomically claims the current open cycle for one traveler transit.
	 */
	public synchronized boolean tryBegin(boolean open)
	{
		observe(open);
		if(!physicallyOpen || phase != Phase.ARMED)
		{
			return false;
		}

		phase = Phase.IN_TRANSIT;
		return true;
	}

	/**
	 * Completes the claimed transit using a freshly observed physical door
	 * state. A failed teleport may be retried during the same open cycle; a
	 * successful one may not.
	 */
	public synchronized Phase complete(boolean success, boolean open)
	{
		if(phase != Phase.IN_TRANSIT)
		{
			throw new IllegalStateException("No dimensional-door transit is in progress");
		}

		physicallyOpen = open;
		if(success)
		{
			phase = open ? Phase.CONSUMED : Phase.CLOSED;
		}
		else
		{
			phase = open ? Phase.ARMED : Phase.CLOSED;
		}
		return phase;
	}

	public synchronized Phase phase()
	{
		return phase;
	}

	public synchronized boolean physicallyOpen()
	{
		return physicallyOpen;
	}

	public enum Phase
	{
		CLOSED,
		ARMED,
		IN_TRANSIT,
		CONSUMED
	}
}
