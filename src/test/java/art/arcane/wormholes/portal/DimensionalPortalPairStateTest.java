package art.arcane.wormholes.portal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import org.bukkit.World;
import org.bukkit.NamespacedKey;
import org.junit.jupiter.api.Test;

import art.arcane.wormholes.util.Cuboid;
import art.arcane.wormholes.util.JSONObject;
import art.arcane.wormholes.portal.vanilla.PortalFactory;

public final class DimensionalPortalPairStateTest
{
	@Test
	public void counterpartIdentityPersistsOutsideTheTunnel()
	{
		LocalPortal portal = portal();
		UUID counterpartId = UUID.randomUUID();
		portal.setDimensionalCounterpartId(counterpartId);
		portal.setDimensionalPortalKind(DimensionalPortalKind.NETHER);

		JSONObject stored = portal.toJSON();

		assertEquals(counterpartId.toString(), stored.getString("dimensionalCounterpartId"));
		assertEquals(counterpartId, LocalPortal.resolveOptionalUuid(stored.getString("dimensionalCounterpartId")));
		assertEquals(DimensionalPortalKind.NETHER.name(), stored.getString("dimensionalPortalKind"));
	}

	@Test
	public void malformedCounterpartIdentityIsIgnored()
	{
		assertNull(LocalPortal.resolveOptionalUuid(""));
		assertNull(LocalPortal.resolveOptionalUuid("not-a-uuid"));
	}

	@Test
	public void unlinkClearsPersistedCounterpartWithoutATunnel()
	{
		LocalPortal portal = portal();
		portal.setDimensionalCounterpartId(UUID.randomUUID());
		portal.setDimensionalPortalKind(DimensionalPortalKind.END_ARRIVAL);

		portal.unlink();

		assertNull(portal.getDimensionalCounterpartId());
		assertNull(portal.getTunnel());
		assertEquals(DimensionalPortalKind.END_ARRIVAL, portal.getDimensionalPortalKind());
	}

	@Test
	public void dimensionalKindParsingIsBackwardCompatible()
	{
		assertEquals(DimensionalPortalKind.NETHER, DimensionalPortalKind.fromName("nether"));
		assertEquals(DimensionalPortalKind.END_SOURCE, DimensionalPortalKind.fromName(" END_SOURCE "));
		assertEquals(DimensionalPortalKind.NONE, DimensionalPortalKind.fromName("unknown"));
		assertEquals(DimensionalPortalKind.NONE, DimensionalPortalKind.fromName(null));
	}

	@Test
	public void endArrivalNormalizesTamperedStateForPersistence()
	{
		World world = world();
		LocalPortal arrival = portal(PortalType.GATEWAY, world);
		LocalPortal unrelated = portal(PortalType.PORTAL, world);
		UUID counterpartId = UUID.randomUUID();
		arrival.setDestination(unrelated);
		arrival.setDimensionalCounterpartId(counterpartId);
		arrival.setProjectionMode(ProjectionMode.MIRROR);
		arrival.setOutgoingTraversalsEnabled(true);
		arrival.setIncomingTraversalsEnabled(false);

		arrival.setDimensionalPortalKind(DimensionalPortalKind.END_ARRIVAL);

		assertEquals(PortalType.PORTAL, arrival.getType());
		assertEquals(ProjectionMode.OFF, arrival.getProjectionMode());
		assertFalse(arrival.isOutgoingTraversalsEnabled());
		assertTrue(arrival.isIncomingTraversalsEnabled());
		assertNull(arrival.getTunnel());
		assertEquals(counterpartId, arrival.getDimensionalCounterpartId());
		JSONObject stored = arrival.toJSON();
		assertFalse(stored.has("tunnel"));
		assertEquals(ProjectionMode.OFF.name(), stored.getString("projectionMode"));
		assertFalse(stored.getBoolean("outgoingTraversalsEnabled"));
		assertTrue(stored.getBoolean("incomingTraversalsEnabled"));
	}

	@Test
	public void endArrivalRejectsOutboundAndRelinkingMutations()
	{
		World world = world();
		LocalPortal arrival = portal(PortalType.PORTAL, world);
		LocalPortal unrelated = portal(PortalType.PORTAL, world);
		UUID counterpartId = UUID.randomUUID();
		arrival.setDimensionalCounterpartId(counterpartId);
		arrival.setDimensionalPortalKind(DimensionalPortalKind.END_ARRIVAL);

		arrival.setDestination(unrelated);
		arrival.linkRemote("other-server", UUID.randomUUID());
		arrival.setType(PortalType.GATEWAY);
		arrival.setProjectionMode(ProjectionMode.MIRROR);
		arrival.setOutgoingTraversalsEnabled(true);
		arrival.setIncomingTraversalsEnabled(false);

		assertNull(arrival.getTunnel());
		assertEquals(counterpartId, arrival.getDimensionalCounterpartId());
		assertEquals(PortalType.PORTAL, arrival.getType());
		assertEquals(ProjectionMode.OFF, arrival.getProjectionMode());
		assertFalse(arrival.isOutgoingTraversalsEnabled());
		assertTrue(arrival.isIncomingTraversalsEnabled());
	}

	@Test
	public void endArrivalRecoversCounterpartIdentityBeforeRemovingReturnTunnel()
	{
		LocalPortal arrival = portal(PortalType.PORTAL, world());
		LocalPortal source = portal(PortalType.PORTAL, world());
		arrival.setDestination(source);

		arrival.setDimensionalPortalKind(DimensionalPortalKind.END_ARRIVAL);

		assertNull(arrival.getTunnel());
		assertEquals(source.getId(), arrival.getDimensionalCounterpartId());
	}

	@Test
	public void oneWayFactoryKeepsManagedEndPairFunctional()
	{
		World world = world();
		LocalPortal source = portal(PortalType.PORTAL, world);
		LocalPortal arrival = portal(PortalType.PORTAL, world);
		source.setDimensionalPortalKind(DimensionalPortalKind.END_SOURCE);
		arrival.setDimensionalPortalKind(DimensionalPortalKind.END_ARRIVAL);

		assertTrue(PortalFactory.linkOneWay(source, arrival));

		assertSame(arrival, source.getTunnel().getDestination());
		assertNull(arrival.getTunnel());
		assertEquals(arrival.getId(), source.getDimensionalCounterpartId());
		assertEquals(source.getId(), arrival.getDimensionalCounterpartId());
		assertTrue(source.isOutgoingTraversalsEnabled());
		assertFalse(source.isIncomingTraversalsEnabled());
		assertFalse(arrival.isOutgoingTraversalsEnabled());
		assertTrue(arrival.isIncomingTraversalsEnabled());

		source.setType(PortalType.GATEWAY);
		source.setProjectionMode(ProjectionMode.MIRROR);
		source.setOutgoingTraversalsEnabled(false);
		source.setIncomingTraversalsEnabled(true);
		assertEquals(PortalType.PORTAL, source.getType());
		assertEquals(ProjectionMode.ON, source.getProjectionMode());
		assertTrue(source.isOutgoingTraversalsEnabled());
		assertFalse(source.isIncomingTraversalsEnabled());
	}

	@Test
	public void managedDimensionalPortalsAreNotGenericDestinations()
	{
		assertFalse(DimensionalPortalKind.END_SOURCE.isGenericDestination());
		assertFalse(DimensionalPortalKind.END_ARRIVAL.isGenericDestination());
		assertFalse(DimensionalPortalKind.NETHER.isGenericDestination());
		assertTrue(DimensionalPortalKind.NONE.isGenericDestination());
	}

	@Test
	public void managedNetherPortalKeepsBidirectionalVanillaState()
	{
		LocalPortal portal = portal();
		portal.setDimensionalPortalKind(DimensionalPortalKind.NETHER);

		portal.setType(PortalType.GATEWAY);
		portal.setProjectionMode(ProjectionMode.MIRROR);
		portal.setOutgoingTraversalsEnabled(false);
		portal.setIncomingTraversalsEnabled(false);

		assertEquals(PortalType.PORTAL, portal.getType());
		assertEquals(ProjectionMode.ON, portal.getProjectionMode());
		assertTrue(portal.isOutgoingTraversalsEnabled());
		assertTrue(portal.isIncomingTraversalsEnabled());
	}

	private static LocalPortal portal()
	{
		return portal(PortalType.PORTAL, world());
	}

	private static LocalPortal portal(PortalType type, World world)
	{
		PortalStructure structure = new PortalStructure();
		structure.setArea(cuboid());
		structure.setWorld(world);
		return new LocalPortal(UUID.randomUUID(), type, structure);
	}

	private static World world()
	{
		return (World) Proxy.newProxyInstance(World.class.getClassLoader(), new Class<?>[] { World.class }, (proxy, method, arguments) -> switch(method.getName())
		{
			case "getName" -> "world";
			case "getKey" -> NamespacedKey.minecraft("overworld");
			case "toString" -> "DimensionalPortalPairStateWorld";
			case "hashCode" -> Integer.valueOf(System.identityHashCode(proxy));
			case "equals" -> Boolean.valueOf(proxy == arguments[0]);
			default -> throw new UnsupportedOperationException(method.getName());
		});
	}

	private static Cuboid cuboid()
	{
		Map<String, Object> map = new HashMap<String, Object>();
		map.put("worldKey", "minecraft:overworld");
		map.put("x1", Integer.valueOf(0));
		map.put("y1", Integer.valueOf(64));
		map.put("z1", Integer.valueOf(0));
		map.put("x2", Integer.valueOf(0));
		map.put("y2", Integer.valueOf(66));
		map.put("z2", Integer.valueOf(2));
		return new Cuboid(map);
	}
}
