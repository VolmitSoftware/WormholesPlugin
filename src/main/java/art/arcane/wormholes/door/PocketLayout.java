package art.arcane.wormholes.door;

import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.UUID;

/**
 * World-independent, deterministic layout of one pocket's protected starter
 * core. The 9x9 floor is centered on the allocator's pocket center.
 */
public record PocketLayout(PocketSpace space) {
    public static final int CORE_RADIUS = 4;
    public static final int CORE_DIAMETER = CORE_RADIUS * 2 + 1;
    public static final int CLEAR_HEIGHT = 3;
    public static final int RETURN_DOOR_X_OFFSET = 0;
    public static final int RETURN_DOOR_Z_OFFSET = 3;
    public static final float ENTRY_YAW = 0.0F;
    public static final float ENTRY_PITCH = 0.0F;

    public PocketLayout {
        Objects.requireNonNull(space, "space");
    }

    public int minX() {
        return Math.subtractExact(space.centerX(), CORE_RADIUS);
    }

    public int maxX() {
        return Math.addExact(space.centerX(), CORE_RADIUS);
    }

    public int minZ() {
        return Math.subtractExact(space.centerZ(), CORE_RADIUS);
    }

    public int maxZ() {
        return Math.addExact(space.centerZ(), CORE_RADIUS);
    }

    public int platformY() {
        return Math.subtractExact(space.centerY(), 1);
    }

    public int clearMinY() {
        return space.centerY();
    }

    public int clearMaxY() {
        return Math.addExact(space.centerY(), CLEAR_HEIGHT - 1);
    }

    public PocketBlockPosition returnDoorLower() {
        return new PocketBlockPosition(
            Math.addExact(space.centerX(), RETURN_DOOR_X_OFFSET),
            space.centerY(),
            Math.addExact(space.centerZ(), RETURN_DOOR_Z_OFFSET)
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
        return new PocketEntryCoordinates(
            space.centerX() + 0.5,
            space.centerY(),
            space.centerZ() + 0.5,
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

    public boolean isPlatformBlock(int x, int y, int z) {
        return y == platformY()
            && x >= minX() && x <= maxX()
            && z >= minZ() && z <= maxZ();
    }

    public boolean isInitialInteriorBlock(int x, int y, int z) {
        return x >= minX() && x <= maxX()
            && z >= minZ() && z <= maxZ()
            && y >= clearMinY() && y <= clearMaxY();
    }

    /** Protect only the starter floor and the two exit-door blocks. */
    public boolean isProtected(int x, int y, int z) {
        if (isPlatformBlock(x, y, z)) {
            return true;
        }
        PocketBlockPosition lower = returnDoorLower();
        return x == lower.x() && z == lower.z() && (y == lower.y() || y == lower.y() + 1);
    }
}
