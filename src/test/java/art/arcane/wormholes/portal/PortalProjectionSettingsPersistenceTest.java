package art.arcane.wormholes.portal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.block.data.BlockData;
import org.junit.jupiter.api.Test;

import art.arcane.wormholes.util.Cuboid;
import art.arcane.wormholes.util.JSONObject;

public final class PortalProjectionSettingsPersistenceTest
{
	@Test
	public void saveJsonWritesBlackoutAndActivationKeys() throws Exception
	{
		World world = world("overworld", -64, 320, 63);
		LocalPortal portal = portal(world);
		portal.setBlackoutBackground(false);
		portal.setBlackoutColor(BlackoutColor.RED);
		portal.setActivationRange(96);

		JSONObject json = portal.toJSON();

		assertFalse(json.getBoolean("blackoutBackground"));
		assertEquals("RED", json.getString("blackoutColor"));
		assertEquals(96, json.getInt("activationRange"));

		LocalPortal reloaded = loadPortal(json, world);
		assertFalse(reloaded.isBlackoutBackground());
		assertEquals(BlackoutColor.RED, reloaded.getBlackoutColor());
		assertEquals(96, reloaded.getActivationRange());
	}

	@Test
	public void loadJsonDefaultsMissingKeysToEnabledBlackAndGlobal() throws Exception
	{
		World world = world("overworld", -64, 320, 63);
		LocalPortal portal = portal(world);
		JSONObject json = portal.toJSON();
		json.remove("blackoutBackground");
		json.remove("blackoutColor");
		json.remove("activationRange");

		LocalPortal reloaded = loadPortal(json, world);

		assertTrue(reloaded.isBlackoutBackground());
		assertEquals(BlackoutColor.BLACK, reloaded.getBlackoutColor());
		assertEquals(0, reloaded.getActivationRange());
	}

	@Test
	public void settersNormalizeActivationRange()
	{
		World world = world("overworld", -64, 320, 63);
		LocalPortal portal = portal(world);

		portal.setActivationRange(-5);
		assertEquals(0, portal.getActivationRange());

		portal.setActivationRange(4);
		assertEquals(8, portal.getActivationRange());

		portal.setActivationRange(1000);
		assertEquals(256, portal.getActivationRange());

		portal.setActivationRange(96);
		assertEquals(96, portal.getActivationRange());
	}

	private static LocalPortal portal(World world)
	{
		PortalStructure structure = new PortalStructure();
		structure.setArea(cuboid());
		structure.setWorld(world);
		return new LocalPortal(UUID.randomUUID(), PortalType.WORMHOLE, structure);
	}

	private static LocalPortal loadPortal(JSONObject stored, World world) throws Exception
	{
		PortalStructure structure = new PortalStructure();
		structure.setArea(cuboid());
		structure.setWorld(world);
		LocalPortal portal = new LocalPortal(UUID.fromString(stored.getString("id")), PortalType.valueOf(stored.getString("type")), structure);
		return withBukkitWorld(world, () ->
		{
			portal.loadJSON(stored);
			return portal;
		});
	}

	private static <T> T withBukkitWorld(World world, Supplier<T> action) throws Exception
	{
		synchronized(Bukkit.class)
		{
			Field serverField = Bukkit.class.getDeclaredField("server");
			serverField.setAccessible(true);
			Object previous = serverField.get(null);
			serverField.set(null, server(world));
			try
			{
				return action.get();
			}
			finally
			{
				serverField.set(null, previous);
			}
		}
	}

	private static Server server(World world)
	{
		return (Server) Proxy.newProxyInstance(Server.class.getClassLoader(), new Class<?>[] { Server.class }, (proxy, method, arguments) -> switch(method.getName())
		{
			case "getWorlds" -> List.of(world);
			case "createBlockData" -> blockData((String) arguments[0]);
			case "getName" -> "PortalProjectionSettingsTestServer";
			case "toString" -> "PortalProjectionSettingsTestServer";
			case "hashCode" -> Integer.valueOf(System.identityHashCode(proxy));
			case "equals" -> Boolean.valueOf(proxy == arguments[0]);
			default -> throw new UnsupportedOperationException(method.getName());
		});
	}

	private static BlockData blockData(String state)
	{
		return (BlockData) Proxy.newProxyInstance(BlockData.class.getClassLoader(), new Class<?>[] { BlockData.class }, (proxy, method, arguments) -> switch(method.getName())
		{
			case "getAsString" -> state;
			case "toString" -> state;
			case "hashCode" -> Integer.valueOf(System.identityHashCode(proxy));
			case "equals" -> Boolean.valueOf(proxy == arguments[0]);
			default -> throw new UnsupportedOperationException(method.getName());
		});
	}

	private static Cuboid cuboid()
	{
		Map<String, Object> values = new HashMap<String, Object>();
		values.put("worldKey", "minecraft:overworld");
		values.put("x1", Integer.valueOf(0));
		values.put("y1", Integer.valueOf(64));
		values.put("z1", Integer.valueOf(0));
		values.put("x2", Integer.valueOf(0));
		values.put("y2", Integer.valueOf(66));
		values.put("z2", Integer.valueOf(2));
		return new Cuboid(values);
	}

	private static World world(String key, int minimumHeight, int maximumHeight, int seaLevel)
	{
		NamespacedKey namespacedKey = NamespacedKey.minecraft(key);
		return (World) Proxy.newProxyInstance(World.class.getClassLoader(), new Class<?>[] { World.class }, (proxy, method, arguments) -> switch(method.getName())
		{
			case "getName" -> key;
			case "getKey" -> namespacedKey;
			case "getUID" -> UUID.nameUUIDFromBytes(namespacedKey.toString().getBytes());
			case "getMinHeight" -> Integer.valueOf(minimumHeight);
			case "getMaxHeight" -> Integer.valueOf(maximumHeight);
			case "getSeaLevel" -> Integer.valueOf(seaLevel);
			case "toString" -> "PortalProjectionSettingsTestWorld[" + namespacedKey + "]";
			case "hashCode" -> Integer.valueOf(System.identityHashCode(proxy));
			case "equals" -> Boolean.valueOf(proxy == arguments[0]);
			default -> throw new UnsupportedOperationException(method.getName());
		});
	}
}
