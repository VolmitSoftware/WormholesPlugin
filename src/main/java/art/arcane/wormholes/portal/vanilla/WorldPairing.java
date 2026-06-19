package art.arcane.wormholes.portal.vanilla;

import org.bukkit.Bukkit;
import org.bukkit.World;

public final class WorldPairing
{
	private static final int NETHER_SCALE = 8;

	private WorldPairing()
	{
	}

	public static int overworldToNether(int coord)
	{
		return Math.floorDiv(coord, NETHER_SCALE);
	}

	public static int netherToOverworld(int coord)
	{
		return coord * NETHER_SCALE;
	}

	public static int scaleHorizontal(World from, World to, int coord)
	{
		if(from == null || to == null)
		{
			return coord;
		}
		World.Environment a = from.getEnvironment();
		World.Environment b = to.getEnvironment();
		if(a == World.Environment.NORMAL && b == World.Environment.NETHER)
		{
			return overworldToNether(coord);
		}
		if(a == World.Environment.NETHER && b == World.Environment.NORMAL)
		{
			return netherToOverworld(coord);
		}
		return coord;
	}

	public static World pairedNether(World overworld)
	{
		if(overworld == null)
		{
			return null;
		}
		if(overworld.getEnvironment() == World.Environment.NETHER)
		{
			return overworld;
		}
		World byName = Bukkit.getWorld(overworld.getName() + "_nether");
		if(byName != null && byName.getEnvironment() == World.Environment.NETHER)
		{
			return byName;
		}
		return firstOfEnvironment(World.Environment.NETHER);
	}

	public static World pairedEnd(World overworld)
	{
		if(overworld == null)
		{
			return null;
		}
		if(overworld.getEnvironment() == World.Environment.THE_END)
		{
			return overworld;
		}
		World byName = Bukkit.getWorld(overworld.getName() + "_the_end");
		if(byName != null && byName.getEnvironment() == World.Environment.THE_END)
		{
			return byName;
		}
		return firstOfEnvironment(World.Environment.THE_END);
	}

	public static World pairedOverworld(World other)
	{
		if(other == null)
		{
			return null;
		}
		if(other.getEnvironment() == World.Environment.NORMAL)
		{
			return other;
		}
		String name = other.getName();
		String base = null;
		if(name.endsWith("_nether"))
		{
			base = name.substring(0, name.length() - "_nether".length());
		}
		else if(name.endsWith("_the_end"))
		{
			base = name.substring(0, name.length() - "_the_end".length());
		}
		if(base != null && !base.isEmpty())
		{
			World resolved = Bukkit.getWorld(base);
			if(resolved != null && resolved.getEnvironment() == World.Environment.NORMAL)
			{
				return resolved;
			}
		}
		return firstOfEnvironment(World.Environment.NORMAL);
	}

	private static World firstOfEnvironment(World.Environment environment)
	{
		for(World world : Bukkit.getWorlds())
		{
			if(world.getEnvironment() == environment)
			{
				return world;
			}
		}
		return null;
	}
}
