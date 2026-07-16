package art.arcane.wormholes.render;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;
import org.junit.jupiter.api.Test;

import art.arcane.volmlib.util.collection.KList;
import art.arcane.wormholes.Settings;
import art.arcane.wormholes.portal.ILocalPortal;
import art.arcane.wormholes.portal.PortalFrame;
import art.arcane.wormholes.portal.PortalStructure;
import art.arcane.wormholes.util.Axis;
import art.arcane.wormholes.util.Direction;

public final class PortalToolPreviewRendererTest
{
	private static final double EPSILON = 0.000001D;

	@Test
	public void rectangleProducesOnlyItsExactOuterBoundary()
	{
		ArrayList<Vector> blocks = new ArrayList<Vector>();
		for(int x = 0; x < 2; x++)
		{
			for(int y = 64; y < 67; y++)
			{
				blocks.add(new Vector(x, y, 8));
			}
		}

		PortalToolPreviewRenderer.Geometry geometry = PortalToolPreviewRenderer.buildGeometry(blocks, Axis.Z);

		assertEquals(6, geometry.cells().size());
		assertEquals(40, geometry.outlinePoints().size());
		for(PortalToolPreviewRenderer.PreviewPoint point : geometry.outlinePoints())
		{
			assertEquals(8.5D, point.z(), EPSILON);
			boolean horizontalBoundary = Math.abs(point.y() - 64.0D) < EPSILON || Math.abs(point.y() - 67.0D) < EPSILON;
			boolean verticalBoundary = Math.abs(point.x()) < EPSILON || Math.abs(point.x() - 2.0D) < EPSILON;
			assertTrue(horizontalBoundary || verticalBoundary);
		}
	}

	@Test
	public void lShapePreservesItsMissingCellAndInnerEdges()
	{
		PortalToolPreviewRenderer.Geometry geometry = PortalToolPreviewRenderer.buildGeometry(List.of(
			new Vector(0, 64, 8),
			new Vector(1, 64, 8),
			new Vector(0, 65, 8)), Axis.Z);

		assertEquals(3, geometry.cells().size());
		assertFalse(geometry.cells().contains(new PortalToolPreviewRenderer.Cell(1, 65, 8)));
		assertEquals(32, geometry.outlinePoints().size());
		assertTrue(geometry.outlinePoints().contains(new PortalToolPreviewRenderer.PreviewPoint(1.125D, 65.0D, 8.5D)));
		assertTrue(geometry.outlinePoints().contains(new PortalToolPreviewRenderer.PreviewPoint(1.0D, 65.125D, 8.5D)));
	}

	@Test
	public void ringPreservesItsCenterHoleAndInnerOutline()
	{
		ArrayList<Vector> blocks = new ArrayList<Vector>();
		for(int x = 0; x < 3; x++)
		{
			for(int y = 0; y < 3; y++)
			{
				if(x != 1 || y != 1)
				{
					blocks.add(new Vector(x, y, 0));
				}
			}
		}

		PortalToolPreviewRenderer.Geometry geometry = PortalToolPreviewRenderer.buildGeometry(blocks, Axis.Z);

		assertEquals(8, geometry.cells().size());
		assertFalse(geometry.cells().contains(new PortalToolPreviewRenderer.Cell(1, 1, 0)));
		assertEquals(64, geometry.outlinePoints().size());
		assertTrue(geometry.outlinePoints().contains(new PortalToolPreviewRenderer.PreviewPoint(1.125D, 1.0D, 0.5D)));
	}

	@Test
	public void everyNormalAxisKeepsTheOutlineOnThePortalPlane()
	{
		for(Axis axis : Axis.values())
		{
			PortalToolPreviewRenderer.Geometry geometry = PortalToolPreviewRenderer.buildGeometry(List.of(new Vector(4, 5, 6)), axis);
			assertEquals(1, geometry.cells().size());
			assertEquals(16, geometry.outlinePoints().size());
			for(PortalToolPreviewRenderer.PreviewPoint point : geometry.outlinePoints())
			{
				double normalCoordinate = switch(axis)
				{
					case X -> point.x();
					case Y -> point.y();
					case Z -> point.z();
				};
				double expected = switch(axis)
				{
					case X -> 4.5D;
					case Y -> 5.5D;
					case Z -> 6.5D;
				};
				assertEquals(expected, normalCoordinate, EPSILON);
			}
		}
	}

	@Test
	public void geometryCacheUsesPortalIdentityRevisionAndNormalAxis()
	{
		PortalToolPreviewRenderer renderer = new PortalToolPreviewRenderer();
		MutableStructure structure = new MutableStructure(List.of(new Vector(0, 0, 0)));
		UUID portalId = UUID.randomUUID();

		PortalToolPreviewRenderer.Geometry first = renderer.geometryFor(portalId, structure, Axis.Z);
		PortalToolPreviewRenderer.Geometry repeated = renderer.geometryFor(portalId, structure, Axis.Z);
		assertSame(first, repeated);
		assertEquals(1, renderer.cachedPortalCount());

		structure.replace(List.of(new Vector(0, 0, 0), new Vector(1, 0, 0)));
		PortalToolPreviewRenderer.Geometry revised = renderer.geometryFor(portalId, structure, Axis.Z);
		assertNotSame(first, revised);
		assertEquals(2, revised.cells().size());

		PortalToolPreviewRenderer.Geometry reoriented = renderer.geometryFor(portalId, structure, Axis.Y);
		assertNotSame(revised, reoriented);

		renderer.invalidate(portalId);
		assertEquals(0, renderer.cachedPortalCount());
	}

	@Test
	public void rangeUsesTheExactPortalBounds()
	{
		PortalToolPreviewRenderer.Geometry geometry = PortalToolPreviewRenderer.buildGeometry(List.of(
			new Vector(-2, 64, 8),
			new Vector(-1, 64, 8)), Axis.Z);

		assertEquals(0.0D, geometry.distanceSquared(-1.0D, 64.5D, 8.5D), EPSILON);
		assertEquals(1024.0D, geometry.distanceSquared(32.0D, 64.5D, 8.5D), EPSILON);
	}

	@Test
	public void renderingIsViewerOnlyRangeFilteredAndBudgeted()
	{
		boolean previousParticles = Settings.ENABLE_PARTICLES;
		Settings.ENABLE_PARTICLES = true;
		try
		{
			World viewerWorld = world(UUID.randomUUID());
			World otherWorld = world(UUID.randomUUID());
			AtomicInteger outlineParticles = new AtomicInteger();
			AtomicInteger fillParticles = new AtomicInteger();
			Player viewer = player(viewerWorld, new Location(viewerWorld, 0.5D, 64.5D, 4.0D), outlineParticles, fillParticles);
			PortalToolPreviewRenderer renderer = new PortalToolPreviewRenderer();

			renderer.render(viewer, List.of(portal(viewerWorld, structureGrid(100, 20, 0, 64, 0), Direction.N)));
			assertEquals(PortalToolPreviewRenderer.MAX_OUTLINE_PARTICLES, outlineParticles.get());
			assertEquals(PortalToolPreviewRenderer.MAX_FILL_PARTICLES, fillParticles.get());

			outlineParticles.set(0);
			fillParticles.set(0);
			renderer.render(viewer, List.of(portal(viewerWorld, structureGrid(1, 1, 100, 64, 0), Direction.N)));
			assertEquals(0, outlineParticles.get());
			assertEquals(0, fillParticles.get());

			renderer.render(viewer, List.of(portal(otherWorld, structureGrid(1, 1, 0, 64, 0), Direction.N)));
			assertEquals(0, outlineParticles.get());
			assertEquals(0, fillParticles.get());
		}
		finally
		{
			Settings.ENABLE_PARTICLES = previousParticles;
		}
	}

	private static MutableStructure structureGrid(int width, int height, int minX, int minY, int z)
	{
		ArrayList<Vector> blocks = new ArrayList<Vector>(width * height);
		for(int x = 0; x < width; x++)
		{
			for(int y = 0; y < height; y++)
			{
				blocks.add(new Vector(minX + x, minY + y, z));
			}
		}
		return new MutableStructure(blocks);
	}

	private static ILocalPortal portal(World world, PortalStructure structure, Direction normal)
	{
		UUID portalId = UUID.randomUUID();
		PortalFrame frame = PortalFrame.canonical(normal);
		return (ILocalPortal) Proxy.newProxyInstance(ILocalPortal.class.getClassLoader(), new Class<?>[] {ILocalPortal.class},
			(proxy, method, arguments) -> switch(method.getName())
			{
				case "getId" -> portalId;
				case "getWorld" -> world;
				case "getStructure" -> structure;
				case "getFrame" -> frame;
				case "isDestroyed" -> false;
				case "toString" -> "PortalToolPreviewRendererTestPortal";
				case "hashCode" -> System.identityHashCode(proxy);
				case "equals" -> proxy == arguments[0];
				default -> throw new AssertionError("Unexpected portal method " + method.getName());
			});
	}

	private static Player player(World world, Location location, AtomicInteger outlineParticles, AtomicInteger fillParticles)
	{
		return (Player) Proxy.newProxyInstance(Player.class.getClassLoader(), new Class<?>[] {Player.class},
			(proxy, method, arguments) -> switch(method.getName())
			{
				case "getWorld" -> world;
				case "getLocation" -> location.clone();
				case "spawnParticle" ->
				{
					Particle particle = (Particle) arguments[0];
					if(particle == Particle.DUST)
					{
						outlineParticles.incrementAndGet();
					}
					else if(particle == Particle.DUST_COLOR_TRANSITION)
					{
						fillParticles.incrementAndGet();
					}
					else
					{
						throw new AssertionError("Unexpected particle " + particle);
					}
					yield null;
				}
				case "toString" -> "PortalToolPreviewRendererTestPlayer";
				case "hashCode" -> System.identityHashCode(proxy);
				case "equals" -> proxy == arguments[0];
				default -> throw new AssertionError("Unexpected player method " + method.getName());
			});
	}

	private static World world(UUID worldId)
	{
		return (World) Proxy.newProxyInstance(World.class.getClassLoader(), new Class<?>[] {World.class},
			(proxy, method, arguments) -> switch(method.getName())
			{
				case "getUID" -> worldId;
				case "toString" -> "PortalToolPreviewRendererTestWorld";
				case "hashCode" -> worldId.hashCode();
				case "equals" -> proxy == arguments[0];
				default -> throw new AssertionError("Unexpected world method " + method.getName());
			});
	}

	private static final class MutableStructure extends PortalStructure
	{
		private KList<Vector> positions;
		private long revision;

		private MutableStructure(List<Vector> positions)
		{
			replaceWithoutRevision(positions);
		}

		private void replace(List<Vector> replacement)
		{
			replaceWithoutRevision(replacement);
			revision++;
		}

		private void replaceWithoutRevision(List<Vector> replacement)
		{
			positions = new KList<Vector>();
			for(Vector position : replacement)
			{
				positions.add(position.clone());
			}
		}

		@Override
		public long getRevision()
		{
			return revision;
		}

		@Override
		public KList<Vector> getBlockPositions()
		{
			KList<Vector> copy = new KList<Vector>();
			for(Vector position : positions)
			{
				copy.add(position.clone());
			}
			return copy;
		}
	}
}
