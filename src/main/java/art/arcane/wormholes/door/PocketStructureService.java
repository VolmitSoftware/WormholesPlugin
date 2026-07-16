package art.arcane.wormholes.door;

import art.arcane.volmlib.util.bukkit.WorldIdentity;
import art.arcane.wormholes.platform.WormholesPlatform;

import java.util.Objects;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.Bisected;
import org.bukkit.block.data.type.Door;

/** Region-thread world mutations for the protected pocket room. */
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

    /** Checks the durable shell invariant before a player is allowed to arrive. */
    public boolean isInitialized(World world, PocketSpace space) {
        Objects.requireNonNull(world, "world");
        PocketLayout layout = layout(space);
        requireWorldHeight(world, layout);
        requireRegionOwnership(world, layout);
        PocketBlockPosition lower = layout.returnDoorLower();
        PocketBlockPosition upper = layout.returnDoorUpper();
        for (int x = layout.minX(); x <= layout.maxX(); x++) {
            for (int y = layout.minY(); y <= layout.maxY(); y++) {
                for (int z = layout.minZ(); z <= layout.maxZ(); z++) {
                    if (!layout.isShellBlock(x, y, z)) {
                        continue;
                    }
                    Material expected = isAt(lower, x, y, z) || isAt(upper, x, y, z)
                        ? RETURN_DOOR_MATERIAL
                        : PLATFORM_MATERIAL;
                    if (world.getBlockAt(x, y, z).getType() != expected) {
                        return false;
                    }
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
            new DoorPosition(world.getUID(), WorldIdentity.serialize(world), lower.x(), lower.y(), lower.z()),
            layout.returnDoorIdentity()
        );
    }

    /**
     * Provisions a pocket on the owning region thread.
     *
     * <p>When {@code initializeRoom} is true, the 32-block shell is created.
     * Later calls leave player space untouched, but always repair the exit door
     * and its support.</p>
     *
     * @return the stable placed endpoint; callers decide when to persist/register it
     */
    public PlacedDoorEndpoint provision(World world, PocketSpace space, boolean initializeRoom) {
        Objects.requireNonNull(world, "world");
        PocketLayout layout = layout(space);
        requireWorldHeight(world, layout);
        requireRegionOwnership(world, layout);

        if (initializeRoom) {
            initializeShell(world, layout);
        }
        repairReturnDoor(world, layout);
        return returnEndpoint(world, space);
    }

    public void retireReturnDoor(World world, PlacedDoorEndpoint endpoint) {
        Objects.requireNonNull(world, "world");
        Objects.requireNonNull(endpoint, "endpoint");
        if (endpoint.identity().kind() != DoorKind.RETURN
            || !endpoint.position().worldId().equals(world.getUID())) {
            throw new IllegalArgumentException("return endpoint must belong to the pocket world");
        }
        DoorPosition position = endpoint.position();
        int chunkX = position.x() >> 4;
        int chunkZ = position.z() >> 4;
        if (!WormholesPlatform.isOwnedByCurrentRegion(world, chunkX, chunkZ, chunkX, chunkZ)) {
            throw new IllegalStateException("return-door retirement must run on the owning region thread");
        }
        clearDoorBlock(world.getBlockAt(position.x(), position.y() + 1, position.z()));
        clearDoorBlock(world.getBlockAt(position.x(), position.y(), position.z()));
    }

    private static void initializeShell(World world, PocketLayout layout) {
        for (int x = layout.minX(); x <= layout.maxX(); x++) {
            for (int y = layout.minY(); y <= layout.maxY(); y++) {
                for (int z = layout.minZ(); z <= layout.maxZ(); z++) {
                    if (layout.isShellBlock(x, y, z)) {
                        setType(world.getBlockAt(x, y, z), PLATFORM_MATERIAL);
                    }
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

    private static void clearDoorBlock(Block block) {
        if (block.getBlockData() instanceof Door) {
            block.setType(Material.AIR, false);
        }
    }

    private static boolean isAt(PocketBlockPosition position, int x, int y, int z) {
        return position.x() == x && position.y() == y && position.z() == z;
    }

    private static void requireWorldHeight(World world, PocketLayout layout) {
        if (layout.minY() < world.getMinHeight() || layout.maxY() >= world.getMaxHeight()) {
            throw new IllegalArgumentException("pocket room does not fit world height range");
        }
    }

    private static void requireRegionOwnership(World world, PocketLayout layout) {
        int minChunkX = layout.minX() >> 4;
        int minChunkZ = layout.minZ() >> 4;
        int maxChunkX = layout.maxX() >> 4;
        int maxChunkZ = layout.maxZ() >> 4;
        if (!WormholesPlatform.isOwnedByCurrentRegion(world, minChunkX, minChunkZ, maxChunkX, maxChunkZ)) {
            throw new IllegalStateException("pocket provisioning must run on the owning region thread");
        }
    }
}
