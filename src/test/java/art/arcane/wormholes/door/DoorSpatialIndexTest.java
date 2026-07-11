package art.arcane.wormholes.door;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;

public final class DoorSpatialIndexTest
{
	@Test
	public void nearbyQueryReadsOnlyRequestedWorldAndChunkRadius()
	{
		DoorSpatialIndex<String> index = new DoorSpatialIndex<>();
		UUID world = UUID.randomUUID();
		UUID otherWorld = UUID.randomUUID();
		index.put(UUID.randomUUID(), world, 0, 64, 0, "center");
		index.put(UUID.randomUUID(), world, 16, 64, 0, "east");
		index.put(UUID.randomUUID(), world, 32, 64, 0, "far");
		index.put(UUID.randomUUID(), otherWorld, 0, 64, 0, "other-world");

		Set<String> nearby = index.nearby(world, 0, 0, 1).stream()
			.map(DoorSpatialIndex.Entry::value)
			.collect(Collectors.toSet());

		assertEquals(Set.of("center", "east"), nearby);
	}

	@Test
	public void negativeBlockCoordinatesUseFloorBasedChunkKeys()
	{
		UUID world = UUID.randomUUID();

		assertEquals(new DoorSpatialIndex.ChunkKey(world, -1, -1),
			DoorSpatialIndex.ChunkKey.fromBlock(world, -1, -16));
		assertEquals(new DoorSpatialIndex.ChunkKey(world, -2, -2),
			DoorSpatialIndex.ChunkKey.fromBlock(world, -17, -32));
	}

	@Test
	public void replacingDoorPositionRemovesOldBucketEntry()
	{
		DoorSpatialIndex<String> index = new DoorSpatialIndex<>();
		UUID world = UUID.randomUUID();
		UUID doorId = UUID.randomUUID();
		index.put(doorId, world, 0, 64, 0, "old");

		index.put(doorId, world, 160, 70, 160, "moved");

		assertTrue(index.nearby(world, 0, 0, 0).isEmpty());
		assertEquals("moved", index.get(doorId).orElseThrow().value());
		assertEquals(1, index.size());
	}

	@Test
	public void removalCleansIdentityAndSpatialBucket()
	{
		DoorSpatialIndex<String> index = new DoorSpatialIndex<>();
		UUID world = UUID.randomUUID();
		UUID doorId = UUID.randomUUID();
		index.put(doorId, world, 5, 64, 5, "door");

		assertEquals("door", index.remove(doorId).orElseThrow().value());
		assertTrue(index.get(doorId).isEmpty());
		assertTrue(index.nearby(world, 5, 5, 0).isEmpty());
	}

	@Test
	public void negativeQueryRadiusIsRejected()
	{
		DoorSpatialIndex<String> index = new DoorSpatialIndex<>();

		assertThrows(IllegalArgumentException.class,
			() -> index.nearby(UUID.randomUUID(), 0, 0, -1));
	}
}
