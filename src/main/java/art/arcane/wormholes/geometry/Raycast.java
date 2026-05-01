package art.arcane.wormholes.geometry;

import org.bukkit.Location;
import org.bukkit.util.Vector;

import art.arcane.wormholes.util.VectorMath;

public abstract class Raycast
{
	private boolean success;
	private boolean successf;

	public Raycast(Location source, Location destination, double jumpSize)
	{
		successf = false;
		success = true;
		Location cursor = source.clone();
		Vector direction = VectorMath.direction(source, destination).multiply(jumpSize);
		int tj = (int) (cursor.distance(destination) / jumpSize);

		for(int i = 0; i < tj; i++)
		{
			if(!shouldContinue(cursor))
			{
				if(successf)
				{
					success = true;
					return;
				}

				success = false;
				return;
			}

			cursor.add(direction);
		}
	}

	public boolean finishSuccess()
	{
		successf = true;
		return false;
	}

	public abstract boolean shouldContinue(Location l);

	public boolean hadSuccess()
	{
		return success;
	}
}
