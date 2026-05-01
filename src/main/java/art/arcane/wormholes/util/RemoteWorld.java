package art.arcane.wormholes.util;

public class RemoteWorld
{
	private final String name;
	private final String world;

	public RemoteWorld(String name, String world)
	{
		this.world = world;
		this.name = name;
	}

	public String getName()
	{
		return name;
	}

	public String getWorld()
	{
		return world;
	}
}
