package art.arcane.wormholes.door;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import org.bukkit.NamespacedKey;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;

/**
 * Versioned Persistent Data Container encoding for dimensional-door items.
 */
public final class DoorItemPdcCodec
{
	private static final int CURRENT_SCHEMA = 2;
	private static final int LEGACY_SCHEMA = 1;

	private final NamespacedKey schemaKey;
	private final NamespacedKey itemIdKey;
	private final NamespacedKey kindKey;
	private final NamespacedKey pairIdKey;
	private final NamespacedKey pairEndpointKey;
	private final NamespacedKey spaceIdKey;
	private final NamespacedKey pairKitIdKey;
	private final NamespacedKey craftProductKey;

	public DoorItemPdcCodec(String namespace)
	{
		Objects.requireNonNull(namespace, "namespace");
		schemaKey = new NamespacedKey(namespace, "door_schema");
		itemIdKey = new NamespacedKey(namespace, "door_item_id");
		kindKey = new NamespacedKey(namespace, "door_kind");
		pairIdKey = new NamespacedKey(namespace, "door_pair_id");
		pairEndpointKey = new NamespacedKey(namespace, "door_pair_endpoint");
		spaceIdKey = new NamespacedKey(namespace, "door_space_id");
		pairKitIdKey = new NamespacedKey(namespace, "door_pair_kit_id");
		craftProductKey = new NamespacedKey(namespace, "door_craft_product");
	}

	public void encodeIdentity(PersistentDataContainer data, DoorItemIdentity identity)
	{
		Objects.requireNonNull(data, "data");
		Objects.requireNonNull(identity, "identity");
		clearIdentity(data);
		data.remove(pairKitIdKey);
		data.remove(craftProductKey);
		data.set(schemaKey, PersistentDataType.INTEGER, CURRENT_SCHEMA);
		data.set(itemIdKey, PersistentDataType.STRING, identity.itemId().toString());
		data.set(kindKey, PersistentDataType.STRING, identity.kind().name());
		if(identity.pairId() != null)
		{
			data.set(pairIdKey, PersistentDataType.STRING, identity.pairId().toString());
		}
		if(identity.pairEndpoint() != null)
		{
			data.set(pairEndpointKey, PersistentDataType.STRING, identity.pairEndpoint().name());
		}
		if(identity.spaceId() != null)
		{
			data.set(spaceIdKey, PersistentDataType.STRING, identity.spaceId().toString());
		}
	}

	/** Malformed or unsupported data is inert rather than crashing an event. */
	public Optional<DoorItemIdentity> decodeIdentity(PersistentDataContainer data)
	{
		Objects.requireNonNull(data, "data");
		try
		{
			Integer schema = data.get(schemaKey, PersistentDataType.INTEGER);
			String itemId = data.get(itemIdKey, PersistentDataType.STRING);
			String kind = data.get(kindKey, PersistentDataType.STRING);
			if(schema == null
				|| (schema != CURRENT_SCHEMA && schema != LEGACY_SCHEMA)
				|| itemId == null
				|| kind == null)
			{
				return Optional.empty();
			}

			String pairId = data.get(pairIdKey, PersistentDataType.STRING);
			String pairEndpoint = data.get(pairEndpointKey, PersistentDataType.STRING);
			String spaceId = data.get(spaceIdKey, PersistentDataType.STRING);
			return Optional.of(new DoorItemIdentity(
				UUID.fromString(itemId),
				decodeKind(schema, kind),
				optionalUuid(pairId),
				pairEndpoint == null ? null : PairEndpoint.valueOf(pairEndpoint),
				optionalUuid(spaceId)));
		}
		catch(RuntimeException ignored)
		{
			return Optional.empty();
		}
	}

	public void clearIdentity(PersistentDataContainer data)
	{
		Objects.requireNonNull(data, "data");
		data.remove(schemaKey);
		data.remove(itemIdKey);
		data.remove(kindKey);
		data.remove(pairIdKey);
		data.remove(pairEndpointKey);
		data.remove(spaceIdKey);
	}

	public void encodePairKit(PersistentDataContainer data, UUID kitId)
	{
		Objects.requireNonNull(data, "data");
		Objects.requireNonNull(kitId, "kitId");
		clearIdentity(data);
		data.remove(craftProductKey);
		data.set(pairKitIdKey, PersistentDataType.STRING, kitId.toString());
	}

	public Optional<UUID> decodePairKitId(PersistentDataContainer data)
	{
		Objects.requireNonNull(data, "data");
		try
		{
			String value = data.get(pairKitIdKey, PersistentDataType.STRING);
			return value == null ? Optional.empty() : Optional.of(UUID.fromString(value));
		}
		catch(IllegalArgumentException ignored)
		{
			return Optional.empty();
		}
	}

	public void encodeCraftProduct(PersistentDataContainer data, DoorCraftProduct product)
	{
		Objects.requireNonNull(data, "data");
		Objects.requireNonNull(product, "product");
		data.remove(pairKitIdKey);
		clearIdentity(data);
		data.set(craftProductKey, PersistentDataType.STRING, product.name());
	}

	public Optional<DoorCraftProduct> decodeCraftProduct(PersistentDataContainer data)
	{
		Objects.requireNonNull(data, "data");
		try
		{
			String value = data.get(craftProductKey, PersistentDataType.STRING);
			return value == null ? Optional.empty() : Optional.of(DoorCraftProduct.valueOf(value));
		}
		catch(IllegalArgumentException ignored)
		{
			return Optional.empty();
		}
	}

	private static DoorKind decodeKind(int schema, String value)
	{
		if(schema == CURRENT_SCHEMA)
		{
			return DoorKind.valueOf(value);
		}
		return switch(value)
		{
			case "PAIRED" -> DoorKind.PAIR;
			case "IRON" -> DoorKind.PUBLIC;
			case "PERSONAL" -> DoorKind.PERSONAL;
			case "RETURN" -> DoorKind.RETURN;
			default -> throw new IllegalArgumentException("Unknown legacy door kind " + value);
		};
	}

	private static UUID optionalUuid(String value)
	{
		return value == null ? null : UUID.fromString(value);
	}
}
