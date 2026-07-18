package art.arcane.wormholes.portal.vanilla;

import java.util.List;

import art.arcane.volmlib.util.bukkit.WorldIdentity;
import org.bukkit.NamespacedKey;
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
		NetherPortalTarget target = resolveNetherTarget(overworld);
		return target == null ? null : target.world();
	}

	static NetherPortalTarget pairedNetherPortalTarget(World source)
	{
		if(source == null)
		{
			return null;
		}
		if(source.getEnvironment() == World.Environment.NETHER)
		{
			World overworld = pairedOverworld(source);
			return overworld == null ? null : new NetherPortalTarget(overworld, false);
		}
		return resolveNetherTarget(source);
	}

	private static NetherPortalTarget resolveNetherTarget(World overworld)
	{
		NamespacedKey overworldKey = WorldIdentity.key(overworld);
		NamespacedKey exact = pairedNetherKey(overworldKey);
		for(NamespacedKey candidate : pairedNetherKeys(overworldKey))
		{
			World paired = resolve(candidate, World.Environment.NETHER);
			if(paired != null)
			{
				return new NetherPortalTarget(paired, !candidate.equals(exact));
			}
		}
		return null;
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
		return resolve(pairedEndKey(WorldIdentity.key(overworld)), World.Environment.THE_END);
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
		return resolve(pairedOverworldKey(WorldIdentity.key(other)), World.Environment.NORMAL);
	}

	static NamespacedKey pairedNetherKey(NamespacedKey overworldKey)
	{
		if(NamespacedKey.minecraft("overworld").equals(overworldKey))
		{
			return NamespacedKey.minecraft("the_nether");
		}
		return new NamespacedKey(overworldKey.getNamespace(), overworldKey.getKey() + "_nether");
	}

	static List<NamespacedKey> pairedNetherKeys(NamespacedKey overworldKey)
	{
		NamespacedKey exact = pairedNetherKey(overworldKey);
		NamespacedKey canonical = NamespacedKey.minecraft("the_nether");
		return canonical.equals(exact) ? List.of(exact) : List.of(exact, canonical);
	}

	static NamespacedKey pairedEndKey(NamespacedKey overworldKey)
	{
		if(NamespacedKey.minecraft("overworld").equals(overworldKey))
		{
			return NamespacedKey.minecraft("the_end");
		}
		return new NamespacedKey(overworldKey.getNamespace(), overworldKey.getKey() + "_the_end");
	}

	static NamespacedKey pairedOverworldKey(NamespacedKey otherKey)
	{
		if(NamespacedKey.minecraft("the_nether").equals(otherKey)
			|| NamespacedKey.minecraft("the_end").equals(otherKey))
		{
			return NamespacedKey.minecraft("overworld");
		}
		String key = otherKey.getKey();
		String base = null;
		if(key.endsWith("_nether"))
		{
			base = key.substring(0, key.length() - "_nether".length());
		}
		else if(key.endsWith("_the_end"))
		{
			base = key.substring(0, key.length() - "_the_end".length());
		}
		return base == null || base.isEmpty() ? null : new NamespacedKey(otherKey.getNamespace(), base);
	}

	private static World resolve(NamespacedKey key, World.Environment environment)
	{
		if(key == null)
		{
			return null;
		}
		World world = WorldIdentity.resolve(key).orElse(null);
		return world != null && world.getEnvironment() == environment ? world : null;
	}

	record NetherPortalTarget(World world, boolean sharedFallback)
	{
	}
}
