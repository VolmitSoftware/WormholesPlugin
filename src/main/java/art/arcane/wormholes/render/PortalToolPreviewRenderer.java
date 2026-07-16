package art.arcane.wormholes.render;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;

import art.arcane.volmlib.util.collection.KList;
import art.arcane.wormholes.Settings;
import art.arcane.wormholes.portal.ILocalPortal;
import art.arcane.wormholes.portal.PortalFrame;
import art.arcane.wormholes.portal.PortalStructure;
import art.arcane.wormholes.util.Axis;

public final class PortalToolPreviewRenderer
{
	static final double PREVIEW_RANGE = 32.0D;
	static final int MAX_OUTLINE_PARTICLES = 96;
	static final int MAX_FILL_PARTICLES = 32;
	private static final int OUTLINE_SAMPLES_PER_EDGE = 4;
	private static final double SURFACE_OFFSET = 0.04D;
	private static final Particle.DustOptions OUTLINE_DUST = new Particle.DustOptions(Color.fromRGB(255, 190, 45), 1.1F);
	private static final Particle.DustTransition FILL_BLACK_TO_GOLD = new Particle.DustTransition(Color.fromRGB(8, 6, 2), Color.fromRGB(255, 176, 30), 0.9F);
	private static final Particle.DustTransition FILL_GOLD_TO_BLACK = new Particle.DustTransition(Color.fromRGB(255, 176, 30), Color.fromRGB(8, 6, 2), 0.9F);

	private final ConcurrentHashMap<UUID, CachedGeometry> geometryCache = new ConcurrentHashMap<UUID, CachedGeometry>();
	private final AtomicLong animationFrame = new AtomicLong();

	public void render(Player viewer, List<? extends ILocalPortal> portals)
	{
		Objects.requireNonNull(viewer, "viewer");
		Objects.requireNonNull(portals, "portals");
		if(!Settings.ENABLE_PARTICLES || portals.isEmpty())
		{
			return;
		}

		World viewerWorld = viewer.getWorld();
		Location viewerLocation = viewer.getLocation();
		UUID viewerWorldId = viewerWorld.getUID();
		ArrayList<RenderTarget> targets = new ArrayList<RenderTarget>(Math.min(portals.size(), 16));
		for(ILocalPortal portal : portals)
		{
			if(portal == null || portal.isDestroyed() || portal.getId() == null)
			{
				continue;
			}
			World portalWorld = portal.getWorld();
			if(portalWorld == null || !viewerWorldId.equals(portalWorld.getUID()))
			{
				continue;
			}
			PortalStructure structure = portal.getStructure();
			PortalFrame frame = portal.getFrame();
			if(structure == null || frame == null)
			{
				continue;
			}
			Geometry geometry = geometryFor(portal.getId(), structure, frame.getNormal().getAxis());
			if(geometry.isEmpty())
			{
				continue;
			}
			double distanceSquared = geometry.distanceSquared(viewerLocation.getX(), viewerLocation.getY(), viewerLocation.getZ());
			if(distanceSquared > PREVIEW_RANGE * PREVIEW_RANGE)
			{
				continue;
			}
			targets.add(new RenderTarget(portal.getId(), geometry, distanceSquared));
		}
		if(targets.isEmpty())
		{
			return;
		}

		targets.sort(Comparator.comparingDouble(RenderTarget::distanceSquared));
		long frame = animationFrame.getAndIncrement();
		int outlineRemaining = MAX_OUTLINE_PARTICLES;
		int fillRemaining = MAX_FILL_PARTICLES;
		for(int i = 0; i < targets.size() && (outlineRemaining > 0 || fillRemaining > 0); i++)
		{
			RenderTarget target = targets.get(i);
			int remainingTargets = targets.size() - i;
			int outlineShare = fairShare(outlineRemaining, remainingTargets);
			int fillShare = fairShare(fillRemaining, remainingTargets);
			outlineRemaining -= emitOutline(viewer, viewerLocation, target, outlineShare, frame);
			fillRemaining -= emitFill(viewer, viewerLocation, target, fillShare, frame);
		}
	}

	public void invalidate(UUID portalId)
	{
		geometryCache.remove(Objects.requireNonNull(portalId, "portalId"));
	}

	public void clear()
	{
		geometryCache.clear();
	}

	Geometry geometryFor(UUID portalId, PortalStructure structure, Axis normalAxis)
	{
		Objects.requireNonNull(portalId, "portalId");
		Objects.requireNonNull(structure, "structure");
		Objects.requireNonNull(normalAxis, "normalAxis");
		while(true)
		{
			long revision = structure.getRevision();
			CachedGeometry current = geometryCache.get(portalId);
			if(current != null && current.revision() == revision && current.normalAxis() == normalAxis)
			{
				return current.geometry();
			}
			KList<Vector> positions = structure.getBlockPositions();
			if(structure.getRevision() != revision)
			{
				continue;
			}
			Geometry built = buildGeometry(positions, normalAxis);
			CachedGeometry resolved = geometryCache.compute(portalId, (ignored, cached) ->
			{
				if(cached != null && cached.revision() == revision && cached.normalAxis() == normalAxis)
				{
					return cached;
				}
				return new CachedGeometry(revision, normalAxis, built);
			});
			if(structure.getRevision() != revision)
			{
				geometryCache.remove(portalId, resolved);
				continue;
			}
			return resolved.geometry();
		}
	}

	int cachedPortalCount()
	{
		return geometryCache.size();
	}

	static Geometry buildGeometry(List<Vector> blockPositions, Axis normalAxis)
	{
		Objects.requireNonNull(blockPositions, "blockPositions");
		Objects.requireNonNull(normalAxis, "normalAxis");
		LongOpenHashSet occupied = new LongOpenHashSet(Math.max(16, blockPositions.size() * 2));
		ArrayList<Cell> cells = new ArrayList<Cell>(blockPositions.size());
		int minX = Integer.MAX_VALUE;
		int minY = Integer.MAX_VALUE;
		int minZ = Integer.MAX_VALUE;
		int maxX = Integer.MIN_VALUE;
		int maxY = Integer.MIN_VALUE;
		int maxZ = Integer.MIN_VALUE;
		for(Vector position : blockPositions)
		{
			if(position == null)
			{
				continue;
			}
			int x = position.getBlockX();
			int y = position.getBlockY();
			int z = position.getBlockZ();
			if(!occupied.add(packCell(x, y, z)))
			{
				continue;
			}
			cells.add(new Cell(x, y, z));
			minX = Math.min(minX, x);
			minY = Math.min(minY, y);
			minZ = Math.min(minZ, z);
			maxX = Math.max(maxX, x);
			maxY = Math.max(maxY, y);
			maxZ = Math.max(maxZ, z);
		}
		if(cells.isEmpty())
		{
			return Geometry.empty(normalAxis);
		}

		ArrayList<PreviewPoint> outline = new ArrayList<PreviewPoint>(Math.max(16, cells.size() * 8));
		for(Cell cell : cells)
		{
			addCellBoundary(outline, occupied, cell, normalAxis);
		}
		return new Geometry(normalAxis, List.copyOf(outline), List.copyOf(cells),
			minX, minY, minZ, maxX + 1.0D, maxY + 1.0D, maxZ + 1.0D);
	}

	private static void addCellBoundary(List<PreviewPoint> outline, LongOpenHashSet occupied, Cell cell, Axis normalAxis)
	{
		switch(normalAxis)
		{
			case X ->
			{
				if(!occupied.contains(packCell(cell.x(), cell.y() - 1, cell.z())))
				{
					addLine(outline, cell.x() + 0.5D, cell.y(), cell.z(), cell.x() + 0.5D, cell.y(), cell.z() + 1.0D);
				}
				if(!occupied.contains(packCell(cell.x(), cell.y() + 1, cell.z())))
				{
					addLine(outline, cell.x() + 0.5D, cell.y() + 1.0D, cell.z(), cell.x() + 0.5D, cell.y() + 1.0D, cell.z() + 1.0D);
				}
				if(!occupied.contains(packCell(cell.x(), cell.y(), cell.z() - 1)))
				{
					addLine(outline, cell.x() + 0.5D, cell.y(), cell.z(), cell.x() + 0.5D, cell.y() + 1.0D, cell.z());
				}
				if(!occupied.contains(packCell(cell.x(), cell.y(), cell.z() + 1)))
				{
					addLine(outline, cell.x() + 0.5D, cell.y(), cell.z() + 1.0D, cell.x() + 0.5D, cell.y() + 1.0D, cell.z() + 1.0D);
				}
			}
			case Y ->
			{
				if(!occupied.contains(packCell(cell.x() - 1, cell.y(), cell.z())))
				{
					addLine(outline, cell.x(), cell.y() + 0.5D, cell.z(), cell.x(), cell.y() + 0.5D, cell.z() + 1.0D);
				}
				if(!occupied.contains(packCell(cell.x() + 1, cell.y(), cell.z())))
				{
					addLine(outline, cell.x() + 1.0D, cell.y() + 0.5D, cell.z(), cell.x() + 1.0D, cell.y() + 0.5D, cell.z() + 1.0D);
				}
				if(!occupied.contains(packCell(cell.x(), cell.y(), cell.z() - 1)))
				{
					addLine(outline, cell.x(), cell.y() + 0.5D, cell.z(), cell.x() + 1.0D, cell.y() + 0.5D, cell.z());
				}
				if(!occupied.contains(packCell(cell.x(), cell.y(), cell.z() + 1)))
				{
					addLine(outline, cell.x(), cell.y() + 0.5D, cell.z() + 1.0D, cell.x() + 1.0D, cell.y() + 0.5D, cell.z() + 1.0D);
				}
			}
			case Z ->
			{
				if(!occupied.contains(packCell(cell.x() - 1, cell.y(), cell.z())))
				{
					addLine(outline, cell.x(), cell.y(), cell.z() + 0.5D, cell.x(), cell.y() + 1.0D, cell.z() + 0.5D);
				}
				if(!occupied.contains(packCell(cell.x() + 1, cell.y(), cell.z())))
				{
					addLine(outline, cell.x() + 1.0D, cell.y(), cell.z() + 0.5D, cell.x() + 1.0D, cell.y() + 1.0D, cell.z() + 0.5D);
				}
				if(!occupied.contains(packCell(cell.x(), cell.y() - 1, cell.z())))
				{
					addLine(outline, cell.x(), cell.y(), cell.z() + 0.5D, cell.x() + 1.0D, cell.y(), cell.z() + 0.5D);
				}
				if(!occupied.contains(packCell(cell.x(), cell.y() + 1, cell.z())))
				{
					addLine(outline, cell.x(), cell.y() + 1.0D, cell.z() + 0.5D, cell.x() + 1.0D, cell.y() + 1.0D, cell.z() + 0.5D);
				}
			}
		}
	}

	private static void addLine(List<PreviewPoint> points, double x0, double y0, double z0, double x1, double y1, double z1)
	{
		for(int sample = 0; sample < OUTLINE_SAMPLES_PER_EDGE; sample++)
		{
			double t = (sample + 0.5D) / OUTLINE_SAMPLES_PER_EDGE;
			points.add(new PreviewPoint(x0 + ((x1 - x0) * t), y0 + ((y1 - y0) * t), z0 + ((z1 - z0) * t)));
		}
	}

	private static int emitOutline(Player viewer, Location viewerLocation, RenderTarget target, int budget, long frame)
	{
		List<PreviewPoint> points = target.geometry().outlinePoints();
		int count = Math.min(Math.max(0, budget), points.size());
		if(count == 0)
		{
			return 0;
		}
		int start = sampleStart(target.portalId(), frame, points.size());
		for(int i = 0; i < count; i++)
		{
			int index = (start + (int) (((long) i * points.size()) / count)) % points.size();
			PreviewPoint point = points.get(index);
			double offset = viewerSideOffset(viewerLocation, target.geometry().normalAxis(), point.x(), point.y(), point.z());
			double x = point.x() + (target.geometry().normalAxis() == Axis.X ? offset : 0.0D);
			double y = point.y() + (target.geometry().normalAxis() == Axis.Y ? offset : 0.0D);
			double z = point.z() + (target.geometry().normalAxis() == Axis.Z ? offset : 0.0D);
			viewer.spawnParticle(Particle.DUST, x, y, z, 1, 0.0D, 0.0D, 0.0D, 0.0D, OUTLINE_DUST);
		}
		return count;
	}

	private static int emitFill(Player viewer, Location viewerLocation, RenderTarget target, int budget, long frame)
	{
		List<Cell> cells = target.geometry().cells();
		int count = Math.min(Math.max(0, budget), cells.size());
		if(count == 0)
		{
			return 0;
		}
		int start = sampleStart(target.portalId(), frame * 3L, cells.size());
		for(int i = 0; i < count; i++)
		{
			int index = (start + (int) (((long) i * cells.size()) / count)) % cells.size();
			Cell cell = cells.get(index);
			double angle = (frame * 0.47D) + (index * 2.399963229728653D) + (i * 0.71D);
			double first = 0.5D + (Math.cos(angle) * 0.32D);
			double second = 0.5D + (Math.sin(angle * 1.618033988749895D) * 0.32D);
			double x = cell.x() + (target.geometry().normalAxis() == Axis.X ? 0.5D : first);
			double y = cell.y() + (target.geometry().normalAxis() == Axis.Y ? 0.5D : target.geometry().normalAxis() == Axis.X ? first : second);
			double z = cell.z() + (target.geometry().normalAxis() == Axis.Z ? 0.5D : second);
			double offset = viewerSideOffset(viewerLocation, target.geometry().normalAxis(), x, y, z);
			x += target.geometry().normalAxis() == Axis.X ? offset : 0.0D;
			y += target.geometry().normalAxis() == Axis.Y ? offset : 0.0D;
			z += target.geometry().normalAxis() == Axis.Z ? offset : 0.0D;
			Particle.DustTransition transition = ((frame + i) & 1L) == 0L ? FILL_BLACK_TO_GOLD : FILL_GOLD_TO_BLACK;
			viewer.spawnParticle(Particle.DUST_COLOR_TRANSITION, x, y, z, 1, 0.0D, 0.0D, 0.0D, 0.0D, transition);
		}
		return count;
	}

	private static int fairShare(int remaining, int remainingTargets)
	{
		if(remaining <= 0 || remainingTargets <= 0)
		{
			return 0;
		}
		return Math.max(1, remaining / remainingTargets);
	}

	private static int sampleStart(UUID portalId, long frame, int size)
	{
		long mixed = frame * 0x9E3779B97F4A7C15L;
		mixed ^= portalId.getMostSignificantBits();
		mixed = Long.rotateLeft(mixed, 21) ^ portalId.getLeastSignificantBits();
		return Math.floorMod(mixed, size);
	}

	private static double viewerSideOffset(Location viewer, Axis normalAxis, double x, double y, double z)
	{
		double viewerCoordinate = switch(normalAxis)
		{
			case X -> viewer.getX();
			case Y -> viewer.getY();
			case Z -> viewer.getZ();
		};
		double planeCoordinate = switch(normalAxis)
		{
			case X -> x;
			case Y -> y;
			case Z -> z;
		};
		return viewerCoordinate >= planeCoordinate ? SURFACE_OFFSET : -SURFACE_OFFSET;
	}

	private static long packCell(int x, int y, int z)
	{
		return (((long) x & 0x3FFFFFFL) << 38) | ((((long) y) & 0xFFFL) << 26) | (((long) z) & 0x3FFFFFFL);
	}

	private record CachedGeometry(long revision, Axis normalAxis, Geometry geometry)
	{
	}

	private record RenderTarget(UUID portalId, Geometry geometry, double distanceSquared)
	{
	}

	static record PreviewPoint(double x, double y, double z)
	{
	}

	static record Cell(int x, int y, int z)
	{
	}

	static record Geometry(
		Axis normalAxis,
		List<PreviewPoint> outlinePoints,
		List<Cell> cells,
		double minX,
		double minY,
		double minZ,
		double maxX,
		double maxY,
		double maxZ)
	{
		private static Geometry empty(Axis normalAxis)
		{
			return new Geometry(normalAxis, List.of(), List.of(), 0.0D, 0.0D, 0.0D, 0.0D, 0.0D, 0.0D);
		}

		boolean isEmpty()
		{
			return cells.isEmpty();
		}

		double distanceSquared(double x, double y, double z)
		{
			double dx = axisDistance(x, minX, maxX);
			double dy = axisDistance(y, minY, maxY);
			double dz = axisDistance(z, minZ, maxZ);
			return (dx * dx) + (dy * dy) + (dz * dz);
		}

		private static double axisDistance(double coordinate, double min, double max)
		{
			if(coordinate < min)
			{
				return min - coordinate;
			}
			if(coordinate > max)
			{
				return coordinate - max;
			}
			return 0.0D;
		}
	}
}
