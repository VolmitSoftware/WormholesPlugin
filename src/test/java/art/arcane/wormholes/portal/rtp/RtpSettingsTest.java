package art.arcane.wormholes.portal.rtp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
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

import art.arcane.wormholes.portal.DimensionalPortalKind;
import art.arcane.wormholes.portal.LocalPortal;
import art.arcane.wormholes.portal.LocalTunnel;
import art.arcane.wormholes.portal.PortalStructure;
import art.arcane.wormholes.portal.PortalType;
import art.arcane.wormholes.util.Cuboid;
import art.arcane.wormholes.util.JSONObject;

public final class RtpSettingsTest
{
	@Test
	public void defaultsMatchApprovedRtpConfiguration()
	{
		World world = world("overworld", -64, 320, 63);

		RtpSettings settings = RtpSettings.defaults(world);

		assertEquals("minecraft:overworld", settings.getSourceWorldKey());
		assertSame(world, settings.getTargetWorld());
		assertEquals("minecraft:overworld", settings.getTargetWorldKey());
		assertEquals(RtpCenterMode.PORTAL_RELATIVE, settings.getCenterMode());
		assertNull(settings.getCustomCenterX());
		assertNull(settings.getCustomCenterZ());
		assertEquals(512, settings.getMinimumRadius());
		assertEquals(4096, settings.getMaximumRadius());
		assertEquals(RtpVerticalMode.SURFACE, settings.getVerticalMode());
		assertEquals(-63, settings.getLowerY());
		assertEquals(318, settings.getUpperY());
		assertEquals(64, settings.getPreferredY());
		assertEquals(RtpAllocationMode.SHARED, settings.getAllocationMode());
		assertEquals(RtpRotationMode.ON_TRAVERSAL, settings.getRotationMode());
		assertEquals(300_000L, settings.getCycleDurationMillis());
		assertEquals(30_000L, settings.getLeaseIdleMillis());
		assertEquals(15_000L, settings.getPrivateReleaseMillis());
		assertTrue(settings.isRimEnabled());
		assertFalse(settings.toJson().has("targetWorldKey"));
	}

	@Test
	public void explicitSourceWorldKeyNormalizesToImplicitSourceWorld()
	{
		World source = world("overworld", -64, 320, 63);
		JSONObject json = RtpSettings.defaults(source).toJson();
		json.put("targetWorldKey", "minecraft:overworld");

		RtpSettings restored = RtpSettings.fromJson(json, key -> source);

		assertTrue(restored.isSourceWorldTarget());
		assertFalse(restored.toJson().has("targetWorldKey"));
	}

	@Test
	public void builderRejectsInvalidSearchGeometryAndClampsBoundedValues()
	{
		World world = world("overworld", -64, 320, 63);

		assertThrows(IllegalArgumentException.class, () -> RtpSettings.builder(world).radii(-1, 100).build());
		assertThrows(IllegalArgumentException.class, () -> RtpSettings.builder(world).radii(100, 100).build());
		assertThrows(IllegalArgumentException.class, () -> RtpSettings.builder(world).centerMode(RtpCenterMode.CUSTOM).build());
		assertThrows(IllegalArgumentException.class, () -> RtpSettings.builder(world).customCenter(Double.NaN, 4.0D).build());

		RtpSettings settings = RtpSettings.builder(world)
				.yBounds(-500, 500)
				.preferredY(500)
				.cycleDurationMillis(1L)
				.leaseIdleMillis(Long.MAX_VALUE)
				.privateReleaseMillis(1L)
				.build();

		assertEquals(-63, settings.getLowerY());
		assertEquals(318, settings.getUpperY());
		assertEquals(318, settings.getPreferredY());
		assertEquals(15_000L, settings.getCycleDurationMillis());
		assertEquals(600_000L, settings.getLeaseIdleMillis());
		assertEquals(5_000L, settings.getPrivateReleaseMillis());
	}

	@Test
	public void jsonRoundTripPreservesConfigurationAndDropsRuntimeIdentity()
	{
		World source = world("overworld", -64, 320, 63);
		World target = world("the_nether", 0, 256, 32);
		RtpSettings settings = RtpSettings.builder(source)
				.targetWorld(target)
				.centerMode(RtpCenterMode.CUSTOM)
				.customCenter(12.25D, -42.75D)
				.radii(100, 900)
				.verticalMode(RtpVerticalMode.PREFERRED_AVERAGE)
				.yBounds(5, 200)
				.preferredY(90)
				.allocationMode(RtpAllocationMode.PER_PLAYER)
				.rotationMode(RtpRotationMode.STATIC)
				.cycleDurationMillis(900_000L)
				.leaseIdleMillis(45_000L)
				.privateReleaseMillis(25_000L)
				.rimEnabled(false)
				.build();
		JSONObject json = settings.toJson();
		json.put("playerId", UUID.randomUUID().toString());
		json.put("runtimeState", "READY");
		json.put("futureState", "pending");

		RtpSettings restored = RtpSettings.fromJson(json, key -> resolveWorld(key, source, target));
		JSONObject persisted = restored.toJson();

		assertEquals(settings, restored);
		assertEquals("minecraft:the_nether", persisted.getString("targetWorldKey"));
		assertFalse(persisted.has("playerId"));
		assertFalse(persisted.has("runtimeState"));
		assertFalse(persisted.has("futureState"));
	}

	@Test
	public void malformedJsonNormalizesToApprovedDefaults()
	{
		World source = world("overworld", -64, 320, 63);
		JSONObject json = new JSONObject();
		json.put("centerMode", "unknown");
		json.put("minimumRadius", -10);
		json.put("maximumRadius", -1);
		json.put("verticalMode", "invalid");
		json.put("allocationMode", "invalid");
		json.put("rotationMode", "invalid");
		json.put("cycleDurationMillis", 1L);
		json.put("leaseIdleMillis", 1L);
		json.put("privateReleaseMillis", Long.MAX_VALUE);

		RtpSettings settings = RtpSettings.fromJson(json, key -> source);

		assertEquals(RtpCenterMode.PORTAL_RELATIVE, settings.getCenterMode());
		assertEquals(512, settings.getMinimumRadius());
		assertEquals(4096, settings.getMaximumRadius());
		assertEquals(RtpVerticalMode.SURFACE, settings.getVerticalMode());
		assertEquals(RtpAllocationMode.SHARED, settings.getAllocationMode());
		assertEquals(RtpRotationMode.ON_TRAVERSAL, settings.getRotationMode());
		assertEquals(15_000L, settings.getCycleDurationMillis());
		assertEquals(5_000L, settings.getLeaseIdleMillis());
		assertEquals(300_000L, settings.getPrivateReleaseMillis());
	}

	@Test
	public void portalTypeIncludesRtp()
	{
		assertEquals(PortalType.RTP, PortalType.valueOf("RTP"));
	}

	@Test
	public void enteringRtpDisablesMirrorAndRemovesLinks()
	{
		World world = world("overworld", -64, 320, 63);
		LocalPortal source = portal(PortalType.WORMHOLE, world);
		LocalPortal destination = portal(PortalType.PORTAL, world);
		source.setDestination(destination);
		source.setMirrorMode(true);

		source.setType(PortalType.RTP);

		assertEquals(PortalType.RTP, source.getType());
		assertFalse(source.isMirrorMode());
		assertNull(source.getTunnel());
		assertTrue(source.getRtpSettings() != null);
	}

	@Test
	public void inboundLocalLinkInvalidatesAndCleansUpWhenDestinationBecomesRtp()
	{
		World world = world("overworld", -64, 320, 63);
		LocalPortal source = portal(PortalType.PORTAL, world);
		LocalPortal destination = portal(PortalType.PORTAL, world);
		source.setAmbientAttended(false);
		source.setDestination(destination);
		assertTrue(source.getTunnel().isValid());

		destination.setType(PortalType.RTP);

		assertFalse(source.getTunnel().isValid());
		assertNull(source.getTunnel().getDestination());
		source.update();
		assertNull(source.getTunnel());
	}

	@Test
	public void unresolvedLocalLinkIsRetainedWhileDestinationIsUnavailable() throws Exception
	{
		World world = world("overworld", -64, 320, 63);
		LocalPortal source = portal(PortalType.PORTAL, world);
		UUID destinationId = UUID.randomUUID();
		JSONObject storedTunnel = new JSONObject();
		storedTunnel.put("type", "LOCAL");
		storedTunnel.put("destination", destinationId.toString());
		LocalTunnel unresolved = new LocalTunnel(null);
		unresolved.loadJSON(storedTunnel);
		setTunnel(source, unresolved);
		source.setAmbientAttended(false);

		source.update();

		assertSame(unresolved, source.getTunnel());
		assertEquals(destinationId, source.getTunnel().getDestinationId());
	}

	@Test
	public void selectingMirrorFromRtpNormalizesPortalType()
	{
		LocalPortal portal = portal(PortalType.RTP, world("overworld", -64, 320, 63));

		portal.setMirrorMode(true);

		assertEquals(PortalType.PORTAL, portal.getType());
		assertTrue(portal.isMirrorMode());
	}

	@Test
	public void rtpRejectsManagedConversionAndDestinationAssignment()
	{
		World world = world("overworld", -64, 320, 63);
		LocalPortal portal = portal(PortalType.RTP, world);
		LocalPortal destination = portal(PortalType.PORTAL, world);

		portal.setDestination(destination);
		portal.linkRemote("other-server", UUID.randomUUID());
		portal.setDimensionalPortalKind(DimensionalPortalKind.NETHER);
		portal.setDimensionalCounterpartId(UUID.randomUUID());
		destination.setDestination(portal);

		assertEquals(PortalType.RTP, portal.getType());
		assertEquals(DimensionalPortalKind.NONE, portal.getDimensionalPortalKind());
		assertNull(portal.getDimensionalCounterpartId());
		assertNull(portal.getTunnel());
		assertNull(destination.getTunnel());
	}

	@Test
	public void managedDimensionalPortalRejectsRtpMode()
	{
		LocalPortal portal = portal(PortalType.PORTAL, world("overworld", -64, 320, 63));
		portal.setDimensionalPortalKind(DimensionalPortalKind.NETHER);

		portal.setType(PortalType.RTP);

		assertEquals(PortalType.PORTAL, portal.getType());
		assertEquals(DimensionalPortalKind.NETHER, portal.getDimensionalPortalKind());
	}

	@Test
	public void localPortalLoadCanonicalizesRtpJsonAndMarksPersistenceDirty() throws Exception
	{
		World world = world("overworld", -64, 320, 63);
		LocalPortal original = portal(PortalType.RTP, world);
		JSONObject stored = original.toJSON();
		JSONObject rtp = stored.getJSONObject("rtp");
		rtp.put("targetWorldKey", "minecraft:overworld");
		rtp.put("minimumRadius", -1);
		rtp.put("maximumRadius", -1);
		rtp.put("runtimeState", "READY");

		LocalPortal loaded = loadPortal(stored, world);
		JSONObject canonical = loaded.getRtpSettings().toJson();

		assertEquals(RtpSettings.defaults(world), loaded.getRtpSettings());
		assertTrue(loaded.needsSaving());
		assertTrue(canonical.similar(loaded.toJSON().getJSONObject("rtp")));
		assertFalse(canonical.has("targetWorldKey"));
		assertFalse(canonical.has("runtimeState"));
	}

	@Test
	public void canonicalRtpJsonLoadedFromTextDoesNotMarkPersistenceDirty() throws Exception
	{
		World world = world("overworld", -64, 320, 63);
		JSONObject stored = new JSONObject(portal(PortalType.RTP, world).toJSON().toString());

		LocalPortal loaded = loadPortal(stored, world);

		assertEquals(RtpSettings.defaults(world), loaded.getRtpSettings());
		assertFalse(loaded.needsSaving());
	}

	@Test
	public void nonRtpLoadWithoutRtpJsonDoesNotCreateDormantDefaults() throws Exception
	{
		World world = world("overworld", -64, 320, 63);
		JSONObject stored = portal(PortalType.PORTAL, world).toJSON();
		assertFalse(stored.has("rtp"));

		LocalPortal loaded = loadPortal(stored, world);

		assertNull(loaded.getRtpSettings());
		assertFalse(loaded.toJSON().has("rtp"));
		assertFalse(loaded.needsSaving());
	}

	@Test
	public void nonRtpLoadRetainsPreviouslyPersistedRtpSettings() throws Exception
	{
		World world = world("overworld", -64, 320, 63);
		LocalPortal original = portal(PortalType.RTP, world);
		RtpSettings settings = RtpSettings.builder(world).radii(300, 1800).build();
		original.setRtpSettings(settings);
		original.setType(PortalType.PORTAL);
		JSONObject stored = original.toJSON();

		LocalPortal loaded = loadPortal(stored, world);

		assertEquals(PortalType.PORTAL, loaded.getType());
		assertEquals(settings, loaded.getRtpSettings());
		assertTrue(loaded.toJSON().has("rtp"));
	}

	@Test
	public void localPortalRejectsSettingsBoundToAnotherSourceWorld()
	{
		World source = world("overworld", -64, 320, 63);
		World otherSource = world("the_nether", 0, 256, 32);
		LocalPortal portal = portal(PortalType.RTP, source);
		RtpSettings original = portal.getRtpSettings();

		assertThrows(IllegalArgumentException.class, () -> portal.setRtpSettings(RtpSettings.defaults(otherSource)));
		assertEquals(original, portal.getRtpSettings());
	}

	@Test
	public void localPortalPersistsNestedRtpSettingsOnly()
	{
		World world = world("overworld", -64, 320, 63);
		LocalPortal portal = portal(PortalType.RTP, world);
		RtpSettings settings = RtpSettings.builder(world)
				.centerMode(RtpCenterMode.CUSTOM)
				.customCenter(18.5D, -22.5D)
				.radii(256, 2048)
				.build();

		portal.setRtpSettings(settings);
		JSONObject stored = portal.toJSON();
		JSONObject rtp = stored.getJSONObject("rtp");

		assertEquals(settings.toJson().toString(), rtp.toString());
		assertFalse(rtp.has("playerId"));
		assertFalse(rtp.has("runtimeState"));
		assertFalse(rtp.has("futureState"));
	}

	private static void setTunnel(LocalPortal portal, LocalTunnel tunnel) throws Exception
	{
		Field tunnelField = LocalPortal.class.getDeclaredField("tunnel");
		tunnelField.setAccessible(true);
		tunnelField.set(portal, tunnel);
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
			case "getName" -> "RtpSettingsTestServer";
			case "toString" -> "RtpSettingsTestServer";
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

	private static World resolveWorld(String key, World source, World target)
	{
		if(key == null || key.isBlank() || "minecraft:overworld".equals(key))
		{
			return source;
		}
		if("minecraft:the_nether".equals(key))
		{
			return target;
		}
		return null;
	}

	private static LocalPortal portal(PortalType type, World world)
	{
		PortalStructure structure = new PortalStructure();
		structure.setArea(cuboid());
		structure.setWorld(world);
		return new LocalPortal(UUID.randomUUID(), type, structure);
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
			case "toString" -> "RtpSettingsTestWorld[" + namespacedKey + "]";
			case "hashCode" -> Integer.valueOf(System.identityHashCode(proxy));
			case "equals" -> Boolean.valueOf(proxy == arguments[0]);
			default -> throw new UnsupportedOperationException(method.getName());
		});
	}
}
