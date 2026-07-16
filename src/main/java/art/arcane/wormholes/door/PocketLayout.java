package art.arcane.wormholes.door;

import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.UUID;

/**
 * World-independent, deterministic layout of one protected pocket room.
 */
public record PocketLayout(PocketSpace space) {
    public static final int CHUNK_SIZE = 16;
    public static final int ROOM_CHUNKS = 2;
    public static final int ROOM_SIZE = CHUNK_SIZE * ROOM_CHUNKS;
    public static final int RETURN_DOOR_CENTER_OFFSET = ROOM_SIZE / 2 - 1;
    public static final float ENTRY_YAW = 0.0F;
    public static final float ENTRY_PITCH = 0.0F;

    public PocketLayout {
        Objects.requireNonNull(space, "space");
    }

    public int minX() {
        return alignedMinimum(Math.subtractExact(space.centerX(), PocketAllocator.CHUNK_CENTER_OFFSET));
    }

    public int maxX() {
        return Math.addExact(minX(), ROOM_SIZE - 1);
    }

    public int minZ() {
        return alignedMinimum(Math.subtractExact(space.centerZ(), PocketAllocator.CHUNK_CENTER_OFFSET));
    }

    public int maxZ() {
        return Math.addExact(minZ(), ROOM_SIZE - 1);
    }

    public int minY() {
        return Math.subtractExact(space.centerY(), 1);
    }

    public int maxY() {
        return Math.addExact(minY(), ROOM_SIZE - 1);
    }

    public PocketBlockPosition returnDoorLower() {
        return new PocketBlockPosition(
            Math.addExact(minX(), RETURN_DOOR_CENTER_OFFSET),
            Math.addExact(minY(), 1),
            maxZ()
        );
    }

    public PocketBlockPosition returnDoorUpper() {
        PocketBlockPosition lower = returnDoorLower();
        return new PocketBlockPosition(lower.x(), Math.addExact(lower.y(), 1), lower.z());
    }

    public PocketBlockPosition returnDoorSupport() {
        PocketBlockPosition lower = returnDoorLower();
        return new PocketBlockPosition(lower.x(), Math.subtractExact(lower.y(), 1), lower.z());
    }

    public PocketEntryCoordinates entry() {
        PocketBlockPosition lower = returnDoorLower();
        return new PocketEntryCoordinates(
            lower.x() + 0.5D,
            lower.y(),
            lower.z() - 0.5D,
            ENTRY_YAW,
            ENTRY_PITCH
        );
    }

    /** Stable internal return-door identity derived solely from the pocket ID. */
    public DoorItemIdentity returnDoorIdentity() {
        String seed = "wormholes:pocket-return-door:v1:" + space.spaceId();
        UUID itemId = UUID.nameUUIDFromBytes(seed.getBytes(StandardCharsets.UTF_8));
        return DoorItemIdentity.returnDoor(itemId, space.spaceId());
    }

    public boolean contains(int x, int y, int z) {
        return x >= minX() && x <= maxX()
            && y >= minY() && y <= maxY()
            && z >= minZ() && z <= maxZ();
    }

    public boolean isShellBlock(int x, int y, int z) {
        return contains(x, y, z)
            && (x == minX() || x == maxX()
                || y == minY() || y == maxY()
                || z == minZ() || z == maxZ());
    }

    public boolean isInteriorBlock(int x, int y, int z) {
        return contains(x, y, z) && !isShellBlock(x, y, z);
    }

    public boolean isProtected(int x, int y, int z) {
        return isShellBlock(x, y, z);
    }

    private static int alignedMinimum(int coordinate) {
        return Math.multiplyExact(Math.floorDiv(coordinate, CHUNK_SIZE), CHUNK_SIZE);
    }
}
