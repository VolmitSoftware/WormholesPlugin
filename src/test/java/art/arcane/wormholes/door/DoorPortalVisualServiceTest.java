package art.arcane.wormholes.door;

import org.bukkit.Material;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.type.Door;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DoorPortalVisualServiceTest
{
	private static final float EPSILON = 0.00001F;

	@Test
	void northAndSouthFillTheDoorwayAcrossTheXAxis()
	{
		DoorPortalVisualService.PortalPlaneGeometry north = DoorPortalVisualService.geometry(BlockFace.NORTH);
		DoorPortalVisualService.PortalPlaneGeometry south = DoorPortalVisualService.geometry(BlockFace.SOUTH);

		assertEquals(north, south);
		assertEquals(-0.5F, north.translationX(), EPSILON);
		assertEquals(0.0F, north.translationY(), EPSILON);
		assertEquals(-0.0175F, north.translationZ(), EPSILON);
		assertEquals(1.0F, north.scaleX(), EPSILON);
		assertEquals(2.0F, north.scaleY(), EPSILON);
		assertEquals(0.035F, north.scaleZ(), EPSILON);
	}

	@Test
	void eastAndWestFillTheDoorwayAcrossTheZAxis()
	{
		DoorPortalVisualService.PortalPlaneGeometry east = DoorPortalVisualService.geometry(BlockFace.EAST);
		DoorPortalVisualService.PortalPlaneGeometry west = DoorPortalVisualService.geometry(BlockFace.WEST);

		assertEquals(east, west);
		assertEquals(-0.0175F, east.translationX(), EPSILON);
		assertEquals(0.0F, east.translationY(), EPSILON);
		assertEquals(-0.5F, east.translationZ(), EPSILON);
		assertEquals(0.035F, east.scaleX(), EPSILON);
		assertEquals(2.0F, east.scaleY(), EPSILON);
		assertEquals(1.0F, east.scaleZ(), EPSILON);
	}

	@Test
	void everyCardinalPlaneRemainsCenteredOnTheDoorway()
	{
		for(BlockFace facing : new BlockFace[] {BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST})
		{
			DoorPortalVisualService.PortalPlaneGeometry geometry = DoorPortalVisualService.geometry(facing);
			assertEquals(0.0F, geometry.translationX() + geometry.scaleX() / 2.0F, EPSILON);
			assertEquals(0.0F, geometry.translationZ() + geometry.scaleZ() / 2.0F, EPSILON);
		}
	}

	@Test
	void portalSurfaceUsesAnOpaqueBlock()
	{
		assertEquals(Material.LIGHT_BLUE_CONCRETE, DoorPortalVisualService.PORTAL_MATERIAL);
	}

	@Test
	void nonCardinalFacingsAreRejected()
	{
		assertThrows(IllegalArgumentException.class, () -> DoorPortalVisualService.geometry(BlockFace.UP));
		assertThrows(NullPointerException.class, () -> DoorPortalVisualService.geometry(null));
	}

	@Test
	void closeIsIdempotentAndPreventsLaterWorldAccess()
	{
		Plugin plugin = (Plugin) Proxy.newProxyInstance(
			Plugin.class.getClassLoader(),
			new Class<?>[] {Plugin.class},
			(proxy, method, arguments) ->
			{
				if(method.getName().equals("namespace"))
				{
					return "test";
				}
				throw new AssertionError("Closed service accessed plugin method " + method.getName());
			});
		UUID worldId = UUID.randomUUID();
		DoorPortalVisualService service = new DoorPortalVisualService(plugin);
		PlacedDoorEndpoint endpoint = new PlacedDoorEndpoint(
			new DoorPosition(worldId, "minecraft:overworld", 1, 2, 3),
			DoorItemIdentity.personal(UUID.randomUUID()));
		VanillaDoorSnapshot snapshot = new VanillaDoorSnapshot(
			worldId,
			new DoorwayPlane(1, 2, 3, BlockFace.NORTH),
			Door.Hinge.LEFT,
			true,
			false);

		assertDoesNotThrow(service::close);
		assertDoesNotThrow(service::close);
		assertDoesNotThrow(() -> service.show(endpoint, snapshot));
	}

	@Test
	void displaySpawnedWhileCloseWinsIsRemovedAndNeverRetained()
	{
		UUID worldId = UUID.randomUUID();
		AtomicBoolean removed = new AtomicBoolean();
		AtomicInteger spawnCalls = new AtomicInteger();
		AtomicReference<DoorPortalVisualService> serviceReference = new AtomicReference<>();
		BlockDisplay display = (BlockDisplay) Proxy.newProxyInstance(
			BlockDisplay.class.getClassLoader(),
			new Class<?>[] {BlockDisplay.class},
			(proxy, method, arguments) -> switch(method.getName())
			{
				case "isValid" -> !removed.get();
				case "remove" ->
				{
					removed.set(true);
					yield null;
				}
				default -> throw new AssertionError("Unexpected display method " + method.getName());
			});
		World world = (World) Proxy.newProxyInstance(
			World.class.getClassLoader(),
			new Class<?>[] {World.class},
			(proxy, method, arguments) ->
			{
				if(method.getName().equals("spawn"))
				{
					spawnCalls.incrementAndGet();
					serviceReference.get().close();
					return display;
				}
				throw new AssertionError("Unexpected world method " + method.getName());
			});
		Server server = (Server) Proxy.newProxyInstance(
			Server.class.getClassLoader(),
			new Class<?>[] {Server.class},
			(proxy, method, arguments) ->
			{
				if(method.getName().equals("getWorld"))
				{
					return world;
				}
				throw new AssertionError("Unexpected server method " + method.getName());
			});
		Plugin plugin = (Plugin) Proxy.newProxyInstance(
			Plugin.class.getClassLoader(),
			new Class<?>[] {Plugin.class},
			(proxy, method, arguments) -> switch(method.getName())
			{
				case "namespace" -> "test";
				case "getServer" -> server;
				default -> throw new AssertionError("Unexpected plugin method " + method.getName());
			});
		DoorPortalVisualService service = new DoorPortalVisualService(plugin);
		serviceReference.set(service);
		PlacedDoorEndpoint endpoint = new PlacedDoorEndpoint(
			new DoorPosition(worldId, "minecraft:overworld", 1, 2, 3),
			DoorItemIdentity.personal(UUID.randomUUID()));
		VanillaDoorSnapshot snapshot = new VanillaDoorSnapshot(
			worldId,
			new DoorwayPlane(1, 2, 3, BlockFace.NORTH),
			Door.Hinge.LEFT,
			true,
			false);

		service.show(endpoint, snapshot);
		service.show(endpoint, snapshot);

		assertTrue(removed.get());
		assertEquals(1, spawnCalls.get());
	}
}
