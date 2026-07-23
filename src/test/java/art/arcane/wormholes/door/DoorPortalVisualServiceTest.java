package art.arcane.wormholes.door;

import org.bukkit.Axis;
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
	private static final float PIXEL = 0.0625F;

	@Test
	void portalKeepsTheHingeInsetAndExtendsFlushToEveryLatchEdge()
	{
		assertLateralBounds(BlockFace.NORTH, Door.Hinge.LEFT, PIXEL, 1.0F);
		assertLateralBounds(BlockFace.NORTH, Door.Hinge.RIGHT, 0.0F, 1.0F - PIXEL);
		assertLateralBounds(BlockFace.SOUTH, Door.Hinge.LEFT, 0.0F, 1.0F - PIXEL);
		assertLateralBounds(BlockFace.SOUTH, Door.Hinge.RIGHT, PIXEL, 1.0F);
		assertLateralBounds(BlockFace.EAST, Door.Hinge.LEFT, PIXEL, 1.0F);
		assertLateralBounds(BlockFace.EAST, Door.Hinge.RIGHT, 0.0F, 1.0F - PIXEL);
		assertLateralBounds(BlockFace.WEST, Door.Hinge.LEFT, 0.0F, 1.0F - PIXEL);
		assertLateralBounds(BlockFace.WEST, Door.Hinge.RIGHT, PIXEL, 1.0F);
	}

	@Test
	void everyCardinalPlaneIsInsetFromTheDoorFrame()
	{
		for(BlockFace facing : new BlockFace[] {BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST})
		{
			for(Door.Hinge hinge : Door.Hinge.values())
			{
				DoorPortalVisualService.PortalPlaneGeometry geometry =
					DoorPortalVisualService.geometry(facing, hinge);
				assertEquals(PIXEL, geometry.translationY(), EPSILON);
				assertEquals(1.9375F, geometry.translationY() + geometry.scaleY(), EPSILON);
				assertEquals(1.875F, geometry.scaleY(), EPSILON);
				assertEquals(0.9375F, Math.max(geometry.scaleX(), geometry.scaleZ()), EPSILON);
			}
		}
	}

	@Test
	void movementThresholdMatchesTheVisiblePortalSurface()
	{
		for(BlockFace facing : new BlockFace[] {BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST})
		{
			for(Door.Hinge hinge : Door.Hinge.values())
			{
				DoorPortalVisualService.PortalPlaneGeometry geometry =
					DoorPortalVisualService.geometry(facing, hinge);
				double localCenterX = geometry.translationX() + (geometry.scaleX() / 2.0D);
				double localCenterZ = geometry.translationZ() + (geometry.scaleZ() / 2.0D);
				double visibleOffset = (localCenterX * facing.getModX()) + (localCenterZ * facing.getModZ());
				DoorwayPlane plane = new DoorwayPlane(0, 64, 0, facing);

				assertEquals(DoorwayPlane.PORTAL_THRESHOLD_OFFSET, visibleOffset, EPSILON);
				assertEquals(DoorwayPlane.PORTAL_THRESHOLD_OFFSET,
					((plane.center().x() - 0.5D) * facing.getModX())
						+ ((plane.center().z() - 0.5D) * facing.getModZ()),
					EPSILON);
			}
		}
	}

	@Test
	void portalUsesAnOpaqueBackingAndAnimatedOverlay()
	{
		assertEquals(Material.CRYING_OBSIDIAN, DoorPortalVisualService.PORTAL_MATERIAL);
		assertEquals(Material.NETHER_PORTAL, DoorPortalVisualService.PORTAL_OVERLAY_MATERIAL);
		assertEquals(Axis.X, DoorPortalVisualService.overlayAxis(BlockFace.NORTH));
		assertEquals(Axis.X, DoorPortalVisualService.overlayAxis(BlockFace.SOUTH));
		assertEquals(Axis.Z, DoorPortalVisualService.overlayAxis(BlockFace.EAST));
		assertEquals(Axis.Z, DoorPortalVisualService.overlayAxis(BlockFace.WEST));
	}

	@Test
	void animatedOverlayStraddlesBothBackingFacesWithoutZFighting()
	{
		for(BlockFace facing : new BlockFace[] {BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST})
		{
			for(Door.Hinge hinge : Door.Hinge.values())
			{
				DoorPortalVisualService.PortalPlaneGeometry backing =
					DoorPortalVisualService.geometry(facing, hinge);
				DoorPortalVisualService.PortalPlaneGeometry overlay =
					DoorPortalVisualService.overlayGeometry(backing, facing);
				float backingStart = normalTranslation(backing, facing);
				float backingEnd = backingStart + normalScale(backing, facing);
				float overlayStart = normalTranslation(overlay, facing);
				float overlayScale = normalScale(overlay, facing);

				assertEquals(backingStart - 0.00125F, overlayStart + (overlayScale * 0.375F), EPSILON);
				assertEquals(backingEnd + 0.00125F, overlayStart + (overlayScale * 0.625F), EPSILON);
				assertEquals(backing.translationY(), overlay.translationY(), EPSILON);
				assertEquals(backing.scaleY(), overlay.scaleY(), EPSILON);
			}
		}
	}

	@Test
	void nonCardinalFacingsAreRejected()
	{
		assertThrows(IllegalArgumentException.class,
			() -> DoorPortalVisualService.geometry(BlockFace.UP, Door.Hinge.LEFT));
		assertThrows(NullPointerException.class,
			() -> DoorPortalVisualService.geometry(null, Door.Hinge.LEFT));
		assertThrows(NullPointerException.class,
			() -> DoorPortalVisualService.geometry(BlockFace.NORTH, null));
		assertThrows(IllegalArgumentException.class, () -> DoorPortalVisualService.overlayAxis(BlockFace.UP));
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
	void hidingPortalRemovesBackingAndAnimatedOverlay()
	{
		UUID worldId = UUID.randomUUID();
		DoorItemIdentity identity = DoorItemIdentity.personal(UUID.randomUUID());
		AtomicBoolean backingRemoved = new AtomicBoolean();
		AtomicBoolean overlayRemoved = new AtomicBoolean();
		AtomicInteger spawnCalls = new AtomicInteger();
		BlockDisplay backing = display(backingRemoved);
		BlockDisplay overlay = display(overlayRemoved);
		World world = (World) Proxy.newProxyInstance(
			World.class.getClassLoader(),
			new Class<?>[] {World.class},
			(proxy, method, arguments) ->
			{
				if(method.getName().equals("spawn"))
				{
					return spawnCalls.getAndIncrement() == 0 ? backing : overlay;
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
				case "isEnabled" -> false;
				default -> throw new AssertionError("Unexpected plugin method " + method.getName());
			});
		DoorPortalVisualService service = new DoorPortalVisualService(plugin);
		PlacedDoorEndpoint endpoint = new PlacedDoorEndpoint(
			new DoorPosition(worldId, "minecraft:overworld", 1, 2, 3),
			identity);
		VanillaDoorSnapshot snapshot = new VanillaDoorSnapshot(
			worldId,
			new DoorwayPlane(1, 2, 3, BlockFace.NORTH),
			Door.Hinge.LEFT,
			true,
			false);

		service.show(endpoint, snapshot);
		service.hide(identity.itemId());

		assertEquals(2, spawnCalls.get());
		assertTrue(backingRemoved.get());
		assertTrue(overlayRemoved.get());
	}

	@Test
	void failedOverlaySpawnRemovesUntrackedBacking()
	{
		UUID worldId = UUID.randomUUID();
		AtomicBoolean backingRemoved = new AtomicBoolean();
		AtomicInteger spawnCalls = new AtomicInteger();
		BlockDisplay backing = display(backingRemoved);
		World world = (World) Proxy.newProxyInstance(
			World.class.getClassLoader(),
			new Class<?>[] {World.class},
			(proxy, method, arguments) ->
			{
				if(method.getName().equals("spawn"))
				{
					if(spawnCalls.getAndIncrement() == 0)
					{
						return backing;
					}
					throw new IllegalStateException("overlay spawn failed");
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
				case "isEnabled" -> false;
				default -> throw new AssertionError("Unexpected plugin method " + method.getName());
			});
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

		assertThrows(IllegalStateException.class, () -> service.show(endpoint, snapshot));

		assertEquals(2, spawnCalls.get());
		assertTrue(backingRemoved.get());
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
				case "isEnabled" -> false;
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

	@Test
	void animateFrameDrivesInterpolatedTransformAndSurfaceParticles()
	{
		boolean particlesEnabled = art.arcane.wormholes.Settings.ENABLE_PARTICLES;
		art.arcane.wormholes.Settings.ENABLE_PARTICLES = true;
		try
		{
			AtomicInteger particleSpawns = new AtomicInteger();
			AtomicInteger interpolationDelay = new AtomicInteger(-1);
			AtomicInteger interpolationDuration = new AtomicInteger(-1);
			AtomicReference<org.bukkit.util.Transformation> applied = new AtomicReference<>();
			BlockDisplay overlay = (BlockDisplay) Proxy.newProxyInstance(
				BlockDisplay.class.getClassLoader(),
				new Class<?>[] {BlockDisplay.class},
				(proxy, method, arguments) -> switch(method.getName())
				{
					case "setInterpolationDelay" ->
					{
						interpolationDelay.set((Integer) arguments[0]);
						yield null;
					}
					case "setInterpolationDuration" ->
					{
						interpolationDuration.set((Integer) arguments[0]);
						yield null;
					}
					case "setTransformation" ->
					{
						applied.set((org.bukkit.util.Transformation) arguments[0]);
						yield null;
					}
					default -> throw new AssertionError("Unexpected overlay method " + method.getName());
				});
			BlockDisplay backing = display(new AtomicBoolean());
			World world = (World) Proxy.newProxyInstance(
				World.class.getClassLoader(),
				new Class<?>[] {World.class},
				(proxy, method, arguments) -> switch(method.getName())
				{
					case "spawnParticle" ->
					{
						particleSpawns.incrementAndGet();
						yield null;
					}
					case "playSound" -> null;
					default -> throw new AssertionError("Unexpected world method " + method.getName());
				});
			Plugin plugin = (Plugin) Proxy.newProxyInstance(
				Plugin.class.getClassLoader(),
				new Class<?>[] {Plugin.class},
				(proxy, method, arguments) ->
				{
					if(method.getName().equals("namespace"))
					{
						return "test";
					}
					throw new AssertionError("Unexpected plugin method " + method.getName());
				});
			DoorPortalVisualService service = new DoorPortalVisualService(plugin);
			DoorPortalVisualService.Visual visual = new DoorPortalVisualService.Visual(
				new DoorPosition(UUID.randomUUID(), "minecraft:overworld", 1, 2, 3),
				backing,
				overlay);
			DoorPortalVisualService.PortalPlaneGeometry base =
				DoorPortalVisualService.overlayGeometry(
					DoorPortalVisualService.geometry(BlockFace.NORTH, Door.Hinge.LEFT), BlockFace.NORTH);
			org.bukkit.Location anchor = new org.bukkit.Location(world, 1.5D, 2.0D, 3.5D);

			service.animateFrame(visual, world, anchor, BlockFace.NORTH, base, 2);

			assertEquals(0, interpolationDelay.get());
			assertEquals(DoorPortalAnimation.FRAME_PERIOD_TICKS, interpolationDuration.get());
			assertTrue(applied.get() != null);
			assertEquals(2, particleSpawns.get());

			particleSpawns.set(0);
			service.animateFrame(visual, world, anchor, BlockFace.NORTH, base, 0);
			assertEquals(3, particleSpawns.get());
		}
		finally
		{
			art.arcane.wormholes.Settings.ENABLE_PARTICLES = particlesEnabled;
		}
	}

	@Test
	void animateFrameSkipsParticlesWhenDisabled()
	{
		boolean particlesEnabled = art.arcane.wormholes.Settings.ENABLE_PARTICLES;
		art.arcane.wormholes.Settings.ENABLE_PARTICLES = false;
		try
		{
			AtomicInteger overlayCalls = new AtomicInteger();
			BlockDisplay overlay = (BlockDisplay) Proxy.newProxyInstance(
				BlockDisplay.class.getClassLoader(),
				new Class<?>[] {BlockDisplay.class},
				(proxy, method, arguments) -> switch(method.getName())
				{
					case "setInterpolationDelay", "setInterpolationDuration", "setTransformation" ->
					{
						overlayCalls.incrementAndGet();
						yield null;
					}
					default -> throw new AssertionError("Unexpected overlay method " + method.getName());
				});
			World world = (World) Proxy.newProxyInstance(
				World.class.getClassLoader(),
				new Class<?>[] {World.class},
				(proxy, method, arguments) ->
				{
					throw new AssertionError("Unexpected world method " + method.getName());
				});
			Plugin plugin = (Plugin) Proxy.newProxyInstance(
				Plugin.class.getClassLoader(),
				new Class<?>[] {Plugin.class},
				(proxy, method, arguments) ->
				{
					if(method.getName().equals("namespace"))
					{
						return "test";
					}
					throw new AssertionError("Unexpected plugin method " + method.getName());
				});
			DoorPortalVisualService service = new DoorPortalVisualService(plugin);
			DoorPortalVisualService.Visual visual = new DoorPortalVisualService.Visual(
				new DoorPosition(UUID.randomUUID(), "minecraft:overworld", 1, 2, 3),
				display(new AtomicBoolean()),
				overlay);
			DoorPortalVisualService.PortalPlaneGeometry base =
				DoorPortalVisualService.overlayGeometry(
					DoorPortalVisualService.geometry(BlockFace.EAST, Door.Hinge.RIGHT), BlockFace.EAST);

			service.animateFrame(
				visual, world, new org.bukkit.Location(world, 1.5D, 2.0D, 3.5D), BlockFace.EAST, base, 4);

			assertEquals(3, overlayCalls.get());
		}
		finally
		{
			art.arcane.wormholes.Settings.ENABLE_PARTICLES = particlesEnabled;
		}
	}

	@Test
	void animationHaltsForUntrackedOrClosedVisuals()
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
				throw new AssertionError("Unexpected plugin method " + method.getName());
			});
		DoorPortalVisualService service = new DoorPortalVisualService(plugin);
		DoorPortalVisualService.Visual visual = new DoorPortalVisualService.Visual(
			new DoorPosition(UUID.randomUUID(), "minecraft:overworld", 1, 2, 3),
			display(new AtomicBoolean()),
			display(new AtomicBoolean()));

		assertTrue(!service.shouldContinueAnimating(UUID.randomUUID(), visual));

		service.close();
		assertTrue(!service.shouldContinueAnimating(UUID.randomUUID(), visual));
	}

	private static void assertLateralBounds(
		BlockFace facing,
		Door.Hinge hinge,
		float expectedMin,
		float expectedMax)
	{
		DoorPortalVisualService.PortalPlaneGeometry geometry =
			DoorPortalVisualService.geometry(facing, hinge);
		float translation = facing == BlockFace.NORTH || facing == BlockFace.SOUTH
			? geometry.translationX()
			: geometry.translationZ();
		float scale = facing == BlockFace.NORTH || facing == BlockFace.SOUTH
			? geometry.scaleX()
			: geometry.scaleZ();

		assertEquals(expectedMin, translation + 0.5F, EPSILON, facing + " " + hinge + " min");
		assertEquals(expectedMax, translation + 0.5F + scale, EPSILON, facing + " " + hinge + " max");
		assertEquals(0.9375F, scale, EPSILON, facing + " " + hinge + " width");
	}

	private static float normalTranslation(
		DoorPortalVisualService.PortalPlaneGeometry geometry,
		BlockFace facing)
	{
		return facing == BlockFace.NORTH || facing == BlockFace.SOUTH
			? geometry.translationZ()
			: geometry.translationX();
	}

	private static float normalScale(
		DoorPortalVisualService.PortalPlaneGeometry geometry,
		BlockFace facing)
	{
		return facing == BlockFace.NORTH || facing == BlockFace.SOUTH
			? geometry.scaleZ()
			: geometry.scaleX();
	}

	private static BlockDisplay display(AtomicBoolean removed)
	{
		return (BlockDisplay) Proxy.newProxyInstance(
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
	}
}
