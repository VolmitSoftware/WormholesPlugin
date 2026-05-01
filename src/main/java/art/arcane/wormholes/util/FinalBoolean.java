package art.arcane.wormholes.util;

/**
 * Represents a number that can be finalized and be changed
 *
 * @author cyberpwn
 */
public class FinalBoolean
{
	private boolean i;

	/**
	 * Create a final boolean
	 *
	 * @param i
	 *            the initial boolean
	 */
	public FinalBoolean(boolean i)
	{
		this.i = i;
	}

	/**
	 * Get the value
	 *
	 * @return the boolean value
	 */
	public boolean get()
	{
		return i;
	}

	/**
	 * Set the value
	 *
	 * @param i
	 *            the boolean value
	 */
	public void set(boolean i)
	{
		this.i = i;
	}

	public void toggle()
	{
		set(!get());
	}
}
