package art.arcane.wormholes.door;

import java.util.Objects;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.Bisected;
import org.bukkit.block.data.type.Door;

/** Region-thread world mutations for the small protected pocket core. */
public final class PocketStructureService {
    public static final Material PLATFORM_MATERIAL = Material.SMOOTH_STONE;
    /** Manually operable: a pocket must never require redstone to escape. */
    public static final Material RETURN_DOOR_MATERIAL = Material.CRIMSON_DOOR;
    public static final BlockFace RETURN_DOOR_FACING = BlockFace.SOUTH;
    public static final Door.Hinge RETURN_DOOR_HINGE = Door.Hinge.LEFT;

    public PocketLayout layout(PocketSpace space) {
        return new PocketLayout(Objects.requireNonNull(space, "space"));
    }

    public PocketEntryCoordinates entryCoordinates(PocketSpace space) {
        return layout(space).entry();
    }

    public Location entryLocation(World world, PocketSpace space) {
        Objects.requireNonNull(world, "world");
        PocketEntryCoordinates entry = entryCoordinates(space);
        return new Location(world, entry.x(), entry.y(), entry.z(), entry.yaw(), entry.pitch());
    }

    public boolean isProtected(PocketSpace space, int x, int y, int z) {
        return layout(space).isProtected(x, y, z);
    }

    /** Checks the durable floor invariant before a player is allowed to arrive. */
    public boolean isInitialized(World world, PocketSpace space) {
        Objects.requireNonNull(world, "world");
        PocketLayout layout = layout(space);
        requireWorldHeight(world, layout);
        requireRegionOwnership(world, layout);
        for (int x = layout.minX(); x <= layout.maxX(); x++) {
            for (int z = layout.minZ(); z <= layout.maxZ(); z++) {
                if (world.getBlockAt(x, layout.platformY(), z).getType() != PLATFORM_MATERIAL) {
                    return false;
                }
            }
        }
        return true;
    }

    public PlacedDoorEndpoint returnEndpoint(World world, PocketSpace space) {
        Objects.requireNonNull(world, "world");
        PocketLayout layout = layout(space);
        PocketBlockPosition lower = layout.returnDoorLower();
        return new PlacedDoorEndpoint(
            new DoorPosition(world.getUID(), world.getName(), lower.x(), lower.y(), lower.z()),
            layout.returnDoorIdentity()
        );
    }

    /**
     * Provisions a pocket on the owning region thread.
     *
     * <p>When {@code initializePlatform} is true, the 9x9 floor is created and
     * its three-block-high interior is cleared. Later calls leave player space
     * untouched, but always repair the exit door and its support.</p>
     *
     * @return the stable placed endpoint; callers decide when to persist/register it
     */
    public PlacedDoorEndpoint provision(World world, PocketSpace space, boolean initializePlatform) {
        Objects.requireNonNull(world, "world");
        PocketLayout layout = layout(space);
        requireWorldHeight(world, layout);
        requireRegionOwnership(world, layout);

        if (initializePlatform) {
            initializePlatform(world, layout);
            clearInitialInterior(world, layout);
        }
        repairReturnDoor(world, layout);
        return returnEndpoint(world, space);
    }

    private static void initializePlatform(World world, PocketLayout layout) {
        for (int x = layout.minX(); x <= layout.maxX(); x++) {
            for (int z = layout.minZ(); z <= layout.maxZ(); z++) {
                setType(world.getBlockAt(x, layout.platformY(), z), PLATFORM_MATERIAL);
            }
        }
    }

    private static void clearInitialInterior(World world, PocketLayout layout) {
        for (int x = layout.minX(); x <= layout.maxX(); x++) {
            for (int y = layout.clearMinY(); y <= layout.clearMaxY(); y++) {
                for (int z = layout.minZ(); z <= layout.maxZ(); z++) {
                    setType(world.getBlockAt(x, y, z), Material.AIR);
                }
            }
        }
    }

    private static void repairReturnDoor(World world, PocketLayout layout) {
        PocketBlockPosition support = layout.returnDoorSupport();
        PocketBlockPosition lower = layout.returnDoorLower();
        PocketBlockPosition upper = layout.returnDoorUpper();
        setType(world.getBlockAt(support.x(), support.y(), support.z()), PLATFORM_MATERIAL);

        Door lowerData = returnDoorData(Bisected.Half.BOTTOM);
        Door upperData = returnDoorData(Bisected.Half.TOP);
        world.getBlockAt(lower.x(), lower.y(), lower.z()).setBlockData(lowerData, false);
        world.getBlockAt(upper.x(), upper.y(), upper.z()).setBlockData(upperData, false);
    }

    private static Door returnDoorData(Bisected.Half half) {
        Door door = (Door) RETURN_DOOR_MATERIAL.createBlockData();
        door.setHalf(half);
        door.setFacing(RETURN_DOOR_FACING);
        door.setHinge(RETURN_DOOR_HINGE);
        door.setOpen(false);
        door.setPowered(false);
        return door;
    }

    private static void setType(Block block, Material material) {
        if (block.getType() != material) {
            block.setType(material, false);
        }
    }

    private static void requireWorldHeight(World world, PocketLayout layout) {
        if (layout.platformY() < world.getMinHeight() || layout.clearMaxY() >= world.getMaxHeight()) {
            throw new IllegalArgumentException("pocket core does not fit world height range");
        }
    }

    private static void requireRegionOwnership(World world, PocketLayout layout) {
        int minChunkX = layout.minX() >> 4;
        int minChunkZ = layout.minZ() >> 4;
        int maxChunkX = layout.maxX() >> 4;
        int maxChunkZ = layout.maxZ() >> 4;
        if (!Bukkit.isOwnedByCurrentRegion(world, minChunkX, minChunkZ, maxChunkX, maxChunkZ)) {
            throw new IllegalStateException("pocket provisioning must run on the owning region thread");
        }
    }
}
