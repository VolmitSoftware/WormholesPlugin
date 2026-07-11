package art.arcane.wormholes.door;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Allocates far-separated pockets along a deterministic square spiral. Slots
 * are monotonic and intentionally never reused.
 */
public final class PocketAllocator {
    public static final int DEFAULT_STRIDE = 8_192;
    public static final int DEFAULT_CENTER_Y = 128;
    /** Keeps the complete 9x9 starter core inside one chunk for Folia ownership. */
    public static final int CHUNK_CENTER_OFFSET = 8;

    private final int stride;
    private final int centerY;
    private final Map<PocketBinding, PocketSpace> byBinding = new HashMap<>();
    private final Set<UUID> spaceIds = new HashSet<>();
    private final Set<Long> occupiedSlots = new HashSet<>();
    private long nextSlot;

    public PocketAllocator() {
        this(DEFAULT_STRIDE, DEFAULT_CENTER_Y, 0, List.of());
    }

    public PocketAllocator(int stride, int centerY) {
        this(stride, centerY, 0, List.of());
    }

    public PocketAllocator(int stride, int centerY, long nextSlot, Collection<PocketSpace> existing) {
        if (stride <= 0) {
            throw new IllegalArgumentException("stride must be positive");
        }
        if (nextSlot < 0) {
            throw new IllegalArgumentException("nextSlot cannot be negative");
        }
        this.stride = stride;
        this.centerY = centerY;
        this.nextSlot = nextSlot;

        long highestSlot = -1;
        for (PocketSpace space : List.copyOf(Objects.requireNonNull(existing, "existing"))) {
            PocketCell expectedCell = cellForSlot(space.slot());
            int expectedX = coordinate(expectedCell.x(), stride);
            int expectedZ = coordinate(expectedCell.z(), stride);
            int legacyX = legacyCoordinate(expectedCell.x(), stride);
            int legacyZ = legacyCoordinate(expectedCell.z(), stride);
            boolean currentCoordinates = space.centerX() == expectedX && space.centerZ() == expectedZ;
            boolean legacyCoordinates = space.centerX() == legacyX && space.centerZ() == legacyZ;
            if ((!currentCoordinates && !legacyCoordinates) || space.centerY() != centerY) {
                throw new IllegalArgumentException("pocket coordinates do not match slot " + space.slot());
            }
            if (byBinding.putIfAbsent(space.binding(), space) != null) {
                throw new IllegalArgumentException("duplicate pocket binding " + space.binding());
            }
            if (!spaceIds.add(space.spaceId())) {
                throw new IllegalArgumentException("duplicate pocket space ID " + space.spaceId());
            }
            if (!occupiedSlots.add(space.slot())) {
                throw new IllegalArgumentException("duplicate pocket slot " + space.slot());
            }
            highestSlot = Math.max(highestSlot, space.slot());
        }
        if (nextSlot <= highestSlot) {
            throw new IllegalArgumentException("nextSlot must be greater than all allocated slots");
        }
    }

    public static PocketAllocator restore(DoorStoreSnapshot snapshot) {
        Objects.requireNonNull(snapshot, "snapshot");
        return new PocketAllocator(DEFAULT_STRIDE, DEFAULT_CENTER_Y, snapshot.nextPocketSlot(), snapshot.spaces());
    }

    public synchronized PocketSpace getOrAllocate(PocketBinding binding) {
        Objects.requireNonNull(binding, "binding");
        PocketSpace existing = byBinding.get(binding);
        if (existing != null) {
            return existing;
        }

        long slot = nextSlot;
        PocketCell cell = cellForSlot(slot);
        int centerX = coordinate(cell.x(), stride);
        int centerZ = coordinate(cell.z(), stride);
        UUID spaceId = spaceIdFor(binding);
        if (spaceIds.contains(spaceId)) {
            throw new IllegalStateException("deterministic pocket space ID collision for " + binding);
        }
        long followingSlot = Math.incrementExact(slot);

        PocketSpace allocated = new PocketSpace(spaceId, binding, slot, centerX, centerY, centerZ);
        byBinding.put(binding, allocated);
        spaceIds.add(spaceId);
        occupiedSlots.add(slot);
        nextSlot = followingSlot;
        return allocated;
    }

    public synchronized Optional<PocketSpace> find(PocketBinding binding) {
        return Optional.ofNullable(byBinding.get(Objects.requireNonNull(binding, "binding")));
    }

    public synchronized List<PocketSpace> spaces() {
        ArrayList<PocketSpace> spaces = new ArrayList<>(byBinding.values());
        spaces.sort(Comparator.comparingLong(PocketSpace::slot));
        return List.copyOf(spaces);
    }

    public synchronized long nextSlot() {
        return nextSlot;
    }

    public int stride() {
        return stride;
    }

    public int centerY() {
        return centerY;
    }

    public static UUID spaceIdFor(PocketBinding binding) {
        Objects.requireNonNull(binding, "binding");
        String key = "wormholes:pocket:" + binding.kind().name() + ':' + binding.bindingId();
        return UUID.nameUUIDFromBytes(key.getBytes(StandardCharsets.UTF_8));
    }

    public static PocketCell cellForSlot(long slot) {
        if (slot < 0) {
            throw new IllegalArgumentException("slot cannot be negative");
        }
        if (slot == 0) {
            return new PocketCell(0, 0);
        }

        long ring = (long) Math.ceil((Math.sqrt(slot + 1.0) - 1.0) / 2.0);
        long side = Math.multiplyExact(2, ring);
        long innerWidth = Math.subtractExact(side, 1);
        long first = Math.multiplyExact(innerWidth, innerWidth);
        long offset = slot - first;

        if (offset < side) {
            return new PocketCell(ring, Math.addExact(-(ring - 1), offset));
        }
        offset -= side;
        if (offset < side) {
            return new PocketCell(Math.subtractExact(ring - 1, offset), ring);
        }
        offset -= side;
        if (offset < side) {
            return new PocketCell(-ring, Math.subtractExact(ring - 1, offset));
        }
        offset -= side;
        return new PocketCell(Math.addExact(-ring + 1, offset), -ring);
    }

    private static int coordinate(long cell, int stride) {
        try {
            return Math.toIntExact(Math.addExact(
                Math.multiplyExact(cell, stride),
                CHUNK_CENTER_OFFSET
            ));
        } catch (ArithmeticException e) {
            throw new IllegalStateException("pocket allocation exceeded integer world coordinates", e);
        }
    }

    /** Schema-1 compatibility for pockets allocated before chunk-centering. */
    private static int legacyCoordinate(long cell, int stride) {
        try {
            return Math.toIntExact(Math.multiplyExact(cell, stride));
        } catch (ArithmeticException e) {
            throw new IllegalStateException("pocket allocation exceeded integer world coordinates", e);
        }
    }
}
