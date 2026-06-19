package art.arcane.wormholes.portal.vanilla;

import java.util.HashSet;
import java.util.Set;

import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;

public final class PortalSiteBuilder
{
	private static final int MIN_INTERIOR_WIDTH = 1;
	private static final int MIN_INTERIOR_HEIGHT = 2;
	private static final int MAX_INTERIOR = 21;

	private PortalSiteBuilder()
	{
	}

	public static Set<Block> buildNetherFrame(World world, int centerX, int startY, int centerZ, boolean alongX, int interiorWidth, int interiorHeight)
	{
		int width = Math.max(MIN_INTERIOR_WIDTH, Math.min(MAX_INTERIOR, interiorWidth));
		int height = Math.max(MIN_INTERIOR_HEIGHT, Math.min(MAX_INTERIOR, interiorHeight));
		int by = findSafeY(world, centerX, startY, centerZ, height);
		int ax = alongX ? 1 : 0;
		int az = alongX ? 0 : 1;
		int nx = alongX ? 0 : 1;
		int nz = alongX ? 1 : 0;
		int bx = centerX - (alongX ? width / 2 : 0);
		int bz = centerZ - (alongX ? 0 : width / 2);

		for(int u = -1; u <= width; u++)
		{
			for(int n = -1; n <= 1; n++)
			{
				world.getBlockAt(bx + ax * u + nx * n, by - 1, bz + az * u + nz * n).setType(Material.AIR, false);
			}
		}

		for(int u = -1; u <= width; u++)
		{
			for(int v = -1; v <= height; v++)
			{
				boolean edge = u == -1 || u == width || v == -1 || v == height;
				if(edge)
				{
					world.getBlockAt(bx + ax * u, by + v, bz + az * u).setType(Material.AIR, false);
				}
			}
		}

		for(int u = 0; u < width; u++)
		{
			for(int v = 0; v < height; v++)
			{
				for(int n = -1; n <= 1; n++)
				{
					if(n == 0)
					{
						continue;
					}
					Block side = world.getBlockAt(bx + ax * u + nx * n, by + v, bz + az * u + nz * n);
					if(side.getType() != Material.OBSIDIAN)
					{
						side.setType(Material.AIR, false);
					}
				}
			}
		}

		Set<Block> interior = new HashSet<Block>();
		for(int u = 0; u < width; u++)
		{
			for(int v = 0; v < height; v++)
			{
				Block cell = world.getBlockAt(bx + ax * u, by + v, bz + az * u);
				cell.setType(Material.AIR, false);
				interior.add(cell);
			}
		}
		return interior;
	}

	public static Set<Block> buildHorizontalWindow(World world, int centerX, int y, int centerZ, int half)
	{
		Set<Block> cells = new HashSet<Block>();
		for(int dx = -half; dx <= half; dx++)
		{
			for(int dz = -half; dz <= half; dz++)
			{
				Block cell = world.getBlockAt(centerX + dx, y, centerZ + dz);
				cell.setType(Material.AIR, false);
				cells.add(cell);
			}
		}
		return cells;
	}

	public static Set<Block> buildEndCounterpart(World world, int centerX, int centerZ, int platformY, int half, int windowHeight)
	{
		int pad = half + 1;
		for(int dx = -pad; dx <= pad; dx++)
		{
			for(int dz = -pad; dz <= pad; dz++)
			{
				world.getBlockAt(centerX + dx, platformY, centerZ + dz).setType(Material.OBSIDIAN, false);
			}
		}
		for(int v = 1; v <= windowHeight + 2; v++)
		{
			for(int dx = -pad; dx <= pad; dx++)
			{
				for(int dz = -pad; dz <= pad; dz++)
				{
					Block above = world.getBlockAt(centerX + dx, platformY + v, centerZ + dz);
					if(above.getType() != Material.OBSIDIAN)
					{
						above.setType(Material.AIR, false);
					}
				}
			}
		}
		return buildHorizontalWindow(world, centerX, platformY + 1 + windowHeight, centerZ, half);
	}

	private static int findSafeY(World world, int x, int startY, int z, int height)
	{
		int min = world.getMinHeight() + 5;
		int max = Math.min(world.getMaxHeight() - (height + 3), world.getEnvironment() == World.Environment.NETHER ? 122 : world.getMaxHeight());
		int y = Math.max(min, Math.min(max, startY));
		for(int cy = y; cy >= min; cy--)
		{
			if(isFloor(world, x, cy, z, height))
			{
				return cy + 1;
			}
		}
		for(int cy = y + 1; cy <= max; cy++)
		{
			if(isFloor(world, x, cy, z, height))
			{
				return cy + 1;
			}
		}
		return y;
	}

	private static boolean isFloor(World world, int x, int y, int z, int height)
	{
		Block floor = world.getBlockAt(x, y, z);
		if(!floor.getType().isSolid() || isLiquid(floor.getType()))
		{
			return false;
		}
		for(int i = 1; i <= height + 1; i++)
		{
			Block above = world.getBlockAt(x, y + i, z);
			if(above.getType().isSolid() || isLiquid(above.getType()))
			{
				return false;
			}
		}
		return true;
	}

	private static boolean isLiquid(Material material)
	{
		return material == Material.LAVA || material == Material.WATER;
	}
}
