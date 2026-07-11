package art.arcane.wormholes.door;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.bukkit.NamespacedKey;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.junit.jupiter.api.Test;

public final class DoorItemPdcCodecTest
{
	private static final String NAMESPACE = "wormholes_test";

	@Test
	public void everyDoorIdentityRoundTripsThroughPdc()
	{
		DoorItemPdcCodec codec = new DoorItemPdcCodec(NAMESPACE);
		DoorPairIdentity pair = DoorPairIdentity.create();
		List<DoorItemIdentity> identities = List.of(
			pair.endpoint(PairEndpoint.A),
			pair.endpoint(PairEndpoint.B),
			DoorItemIdentity.newPersonal(),
			DoorItemIdentity.newIron(),
			DoorItemIdentity.newReturn(UUID.randomUUID()));

		for(DoorItemIdentity identity : identities)
		{
			PersistentDataContainer data = dataContainer();
			codec.encodeIdentity(data, identity);

			assertEquals(identity, codec.decodeIdentity(data).orElseThrow());
		}
	}

	@Test
	public void encodingNewIdentityRemovesStaleOptionalFields()
	{
		DoorItemPdcCodec codec = new DoorItemPdcCodec(NAMESPACE);
		PersistentDataContainer data = dataContainer();
		DoorPairIdentity pair = DoorPairIdentity.create();
		codec.encodeIdentity(data, pair.endpoint(PairEndpoint.A));

		DoorItemIdentity personal = DoorItemIdentity.newPersonal();
		codec.encodeIdentity(data, personal);

		assertEquals(personal, codec.decodeIdentity(data).orElseThrow());
		assertFalse(data.has(key("door_pair_id")));
		assertFalse(data.has(key("door_pair_endpoint")));
		assertFalse(data.has(key("door_space_id")));
	}

	@Test
	public void malformedOrUnsupportedIdentityIsInert()
	{
		DoorItemPdcCodec codec = new DoorItemPdcCodec(NAMESPACE);
		PersistentDataContainer data = dataContainer();
		codec.encodeIdentity(data, DoorItemIdentity.newIron());

		data.set(key("door_schema"), PersistentDataType.INTEGER, 99);
		assertTrue(codec.decodeIdentity(data).isEmpty());

		data.set(key("door_schema"), PersistentDataType.INTEGER, 1);
		data.set(key("door_item_id"), PersistentDataType.STRING, "not-a-uuid");
		assertTrue(codec.decodeIdentity(data).isEmpty());

		data.remove(key("door_item_id"));
		assertTrue(codec.decodeIdentity(data).isEmpty());
	}

	@Test
	public void pairKitAndCraftTemplateMarkersCannotOverlap()
	{
		DoorItemPdcCodec codec = new DoorItemPdcCodec(NAMESPACE);
		PersistentDataContainer data = dataContainer();
		UUID kitId = UUID.randomUUID();
		codec.encodeIdentity(data, DoorItemIdentity.newIron());

		codec.encodePairKit(data, kitId);
		assertEquals(kitId, codec.decodePairKitId(data).orElseThrow());
		assertTrue(codec.decodeCraftProduct(data).isEmpty());
		assertTrue(codec.decodeIdentity(data).isEmpty());

		codec.encodeCraftProduct(data, DoorCraftProduct.PAIR_KIT);
		assertTrue(codec.decodePairKitId(data).isEmpty());
		assertEquals(DoorCraftProduct.PAIR_KIT, codec.decodeCraftProduct(data).orElseThrow());
	}

	@Test
	public void oneKitAlwaysDerivesTheSameUniquePair()
	{
		UUID kitId = UUID.randomUUID();
		DoorPairIdentity first = DoorItemService.pairIdentityForKit(kitId);
		DoorPairIdentity replay = DoorItemService.pairIdentityForKit(kitId);
		DoorPairIdentity otherKit = DoorItemService.pairIdentityForKit(UUID.randomUUID());

		assertEquals(first, replay);
		assertNotEquals(first, otherKit);
		assertNotEquals(first.endpointAItemId(), first.endpointBItemId());
		assertEquals(PairEndpoint.A, first.endpoint(PairEndpoint.A).pairEndpoint());
		assertEquals(PairEndpoint.B, first.endpoint(PairEndpoint.B).pairEndpoint());
	}

	private static NamespacedKey key(String value)
	{
		return new NamespacedKey(NAMESPACE, value);
	}

	private static PersistentDataContainer dataContainer()
	{
		Map<NamespacedKey, StoredValue> values = new HashMap<>();
		return (PersistentDataContainer) Proxy.newProxyInstance(
			PersistentDataContainer.class.getClassLoader(),
			new Class<?>[]{PersistentDataContainer.class},
			(proxy, method, arguments) -> switch(method.getName())
			{
				case "set" ->
				{
					values.put((NamespacedKey) arguments[0], new StoredValue((PersistentDataType<?, ?>) arguments[1], arguments[2]));
					yield null;
				}
				case "remove" ->
				{
					values.remove(arguments[0]);
					yield null;
				}
				case "get" ->
				{
					StoredValue value = values.get(arguments[0]);
					yield value != null && value.type() == arguments[1] ? value.value() : null;
				}
				case "has" ->
				{
					StoredValue value = values.get(arguments[0]);
					yield arguments.length == 1
						? value != null
						: value != null && value.type() == arguments[1];
				}
				case "getKeys" -> Set.copyOf(values.keySet());
				case "isEmpty" -> values.isEmpty();
				case "getSize" -> values.size();
				case "toString" -> values.toString();
				case "hashCode" -> System.identityHashCode(proxy);
				case "equals" -> proxy == arguments[0];
				default -> throw new UnsupportedOperationException(method.getName());
			});
	}

	private record StoredValue(PersistentDataType<?, ?> type, Object value)
	{
	}
}
