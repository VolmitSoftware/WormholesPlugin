package art.arcane.wormholes.geometry;

import org.bukkit.Location;

public abstract class Raycast
{
	private boolean success;
	private boolean successf;

	public Raycast(Location source, Location destination, double jumpSize)
	{
		if(!(jumpSize > 0.0D))
		{
			throw new IllegalArgumentException("Raycast jump size must be positive");
		}

		successf = false;
		success = true;
		double distance = source.distance(destination);
		int segmentCount = distance == 0.0D ? 0 : Math.max(1, (int) Math.ceil(distance / jumpSize));
		double deltaX = destination.getX() - source.getX();
		double deltaY = destination.getY() - source.getY();
		double deltaZ = destination.getZ() - source.getZ();

		for(int i = 0; i <= segmentCount; i++)
		{
			Location sample;
			if(i == 0)
			{
				sample = source.clone();
			}
			else if(i == segmentCount)
			{
				sample = destination.clone();
			}
			else
			{
				double progress = (double) i / segmentCount;
				sample = source.clone().add(deltaX * progress, deltaY * progress, deltaZ * progress);
			}

			if(!shouldContinue(sample))
			{
				if(successf)
				{
					success = true;
					return;
				}

				success = false;
				return;
			}
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
