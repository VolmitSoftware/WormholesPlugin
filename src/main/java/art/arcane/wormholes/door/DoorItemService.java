package art.arcane.wormholes.door;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Keyed;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.event.inventory.CraftItemEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.RecipeChoice;
import org.bukkit.inventory.ShapedRecipe;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.Plugin;

/**
 * Creates, identifies, crafts, and unpacks survival dimensional-door items.
 *
 * <p>This class deliberately is not a Bukkit listener. The owning manager must
 * call {@link #handleCraft(CraftItemEvent)} from its craft listener and invoke
 * {@link #unpackPairKit(ItemStack)} only while atomically consuming that kit.</p>
 */
public final class DoorItemService
{
	public static final Material PAIR_KIT_MATERIAL = Material.BUNDLE;
	public static final Material PAIRED_DOOR_MATERIAL = Material.WARPED_DOOR;
	public static final Material PERSONAL_DOOR_MATERIAL = Material.DARK_OAK_DOOR;
	public static final Material IRON_DOOR_MATERIAL = Material.IRON_DOOR;

	private final ItemStack wormholeRune;
	private final DoorItemPdcCodec codec;
	private final NamespacedKey pairKitRecipeKey;
	private final NamespacedKey personalDoorRecipeKey;
	private final NamespacedKey ironDoorRecipeKey;

	public DoorItemService(Plugin plugin, ItemStack exactWormholeRune)
	{
		Objects.requireNonNull(plugin, "plugin");
		Objects.requireNonNull(exactWormholeRune, "exactWormholeRune");
		if(exactWormholeRune.getType().isAir())
		{
			throw new IllegalArgumentException("Wormhole Rune cannot be air");
		}

		wormholeRune = exactWormholeRune.clone();
		wormholeRune.setAmount(1);
		codec = new DoorItemPdcCodec(plugin.namespace());
		pairKitRecipeKey = new NamespacedKey(plugin, "dimensional_door_pair_kit");
		personalDoorRecipeKey = new NamespacedKey(plugin, "personal_dimensional_door");
		ironDoorRecipeKey = new NamespacedKey(plugin, "iron_dimensional_door");
	}

	public ItemStack createPairKit()
	{
		return createPairKit(UUID.randomUUID());
	}

	public ItemStack createPairKit(UUID kitId)
	{
		ItemStack item = styledItem(
			PAIR_KIT_MATERIAL,
			"Entangled Door Pair",
			ChatColor.LIGHT_PURPLE,
			List.of(
				"Contains two automatically linked Wormhole Doors.",
				"Use it to unpack endpoints A and B."));
		ItemMeta meta = item.getItemMeta();
		codec.encodePairKit(meta.getPersistentDataContainer(), Objects.requireNonNull(kitId, "kitId"));
		item.setItemMeta(meta);
		return item;
	}

	public ItemStack createPersonalDoor()
	{
		return createDoor(DoorItemIdentity.newPersonal());
	}

	public ItemStack createIronDoor()
	{
		return createDoor(DoorItemIdentity.newIron());
	}

	public ItemStack createReturnDoor(UUID spaceId)
	{
		return createDoor(DoorItemIdentity.newReturn(Objects.requireNonNull(spaceId, "spaceId")));
	}

	public ItemStack createDoor(DoorItemIdentity identity)
	{
		Objects.requireNonNull(identity, "identity");
		ItemStack item = switch(identity.kind())
		{
			case PAIRED -> styledItem(
				PAIRED_DOOR_MATERIAL,
				"Wormhole Door " + identity.pairEndpoint().name(),
				ChatColor.LIGHT_PURPLE,
				List.of(
					"Automatically linked to endpoint " + identity.pairEndpoint().other().name() + ".",
					"Open the door and physically cross its threshold."));
			case PERSONAL -> styledItem(
				PERSONAL_DOOR_MATERIAL,
				"Personal Dimension Door",
				ChatColor.AQUA,
				List.of(
					"Each traveler enters their own persistent dimension.",
					"The same traveler always reaches the same place."));
			case IRON -> styledItem(
				IRON_DOOR_MATERIAL,
				"Iron Dimension Door",
				ChatColor.GOLD,
				List.of(
					"Permanently bound to this door's dimension.",
					"Breaking and moving it preserves its destination."));
			case RETURN -> styledItem(
				PocketStructureService.RETURN_DOOR_MATERIAL,
				"Dimensional Exit Door",
				ChatColor.GREEN,
				List.of(
					"Returns travelers from this pocket dimension.",
					"This door is bound to its pocket."));
		};

		ItemMeta meta = item.getItemMeta();
		codec.encodeIdentity(meta.getPersistentDataContainer(), identity);
		item.setItemMeta(meta);
		return item;
	}

	public Optional<DoorItemIdentity> decodeDoor(ItemStack item)
	{
		if(item == null || item.getType().isAir() || item.getAmount() != 1 || !item.hasItemMeta())
		{
			return Optional.empty();
		}

		Optional<DoorItemIdentity> decoded = codec.decodeIdentity(item.getItemMeta().getPersistentDataContainer());
		return decoded.filter(identity -> expectedMaterial(identity.kind()) == item.getType());
	}

	public Optional<UUID> pairKitId(ItemStack item)
	{
		if(item == null || item.getType() != PAIR_KIT_MATERIAL || item.getAmount() != 1 || !item.hasItemMeta())
		{
			return Optional.empty();
		}
		return codec.decodePairKitId(item.getItemMeta().getPersistentDataContainer());
	}

	/**
	 * Produces deterministic endpoint identities for the kit. Replaying or
	 * creative-copying one kit therefore produces duplicate identities rather
	 * than minting additional independent pairs.
	 */
	public Optional<PairKitContents> unpackPairKit(ItemStack kit)
	{
		return pairKitId(kit).map(kitId ->
		{
			DoorPairIdentity pair = pairIdentityForKit(kitId);
			return new PairKitContents(
				kitId,
				pair,
				createDoor(pair.endpoint(PairEndpoint.A)),
				createDoor(pair.endpoint(PairEndpoint.B)));
		});
	}

	public static DoorPairIdentity pairIdentityForKit(UUID kitId)
	{
		Objects.requireNonNull(kitId, "kitId");
		return new DoorPairIdentity(
			derivedId(kitId, "pair"),
			derivedId(kitId, "endpoint-a"),
			derivedId(kitId, "endpoint-b"));
	}

	/** Registers the three hardcoded survival recipes. */
	public boolean registerRecipes()
	{
		unregisterRecipes();
		boolean pairAdded = Bukkit.addRecipe(pairKitRecipe(), false);
		boolean personalAdded = Bukkit.addRecipe(personalDoorRecipe(), false);
		boolean ironAdded = Bukkit.addRecipe(ironDoorRecipe(), true);
		return pairAdded && personalAdded && ironAdded;
	}

	public void unregisterRecipes()
	{
		Bukkit.removeRecipe(pairKitRecipeKey, false);
		Bukkit.removeRecipe(personalDoorRecipeKey, false);
		Bukkit.removeRecipe(ironDoorRecipeKey, true);
	}

	public ShapedRecipe pairKitRecipe()
	{
		return new ShapedRecipe(pairKitRecipeKey, craftTemplate(DoorCraftProduct.PAIR_KIT))
			.shape("EDE", "ORO", " D ")
			.setIngredient('E', Material.ENDER_EYE)
			.setIngredient('D', Material.OAK_DOOR)
			.setIngredient('O', Material.OBSIDIAN)
			.setIngredient('R', new RecipeChoice.ExactChoice(wormholeRune));
	}

	public ShapedRecipe personalDoorRecipe()
	{
		return new ShapedRecipe(personalDoorRecipeKey, craftTemplate(DoorCraftProduct.PERSONAL_DOOR))
			.shape(" R ", "CDE")
			.setIngredient('R', new RecipeChoice.ExactChoice(wormholeRune))
			.setIngredient('C', Material.RECOVERY_COMPASS)
			.setIngredient('D', Material.OAK_DOOR)
			.setIngredient('E', Material.ENDER_CHEST);
	}

	public ShapedRecipe ironDoorRecipe()
	{
		return new ShapedRecipe(ironDoorRecipeKey, craftTemplate(DoorCraftProduct.IRON_DOOR))
			.shape("RDR", " E ", " L ")
			.setIngredient('R', new RecipeChoice.ExactChoice(wormholeRune))
			.setIngredient('D', Material.IRON_DOOR)
			.setIngredient('E', Material.ENDER_CHEST)
			.setIngredient('L', Material.LODESTONE);
	}

	/**
	 * Mints one unique result at the actual click. Shift crafting is cancelled
	 * so Bukkit never duplicates a single identity across a bulk result.
	 */
	public CraftHookResult handleCraft(CraftItemEvent event)
	{
		Objects.requireNonNull(event, "event");
		Optional<DoorCraftProduct> product = productFor(event.getRecipe());
		if(product.isEmpty())
		{
			return CraftHookResult.NOT_A_DOOR_RECIPE;
		}
		if(event.isCancelled())
		{
			return CraftHookResult.ALREADY_CANCELLED;
		}
		if(event.isShiftClick())
		{
			event.setCancelled(true);
			return CraftHookResult.SHIFT_CRAFT_BLOCKED;
		}

		event.setCurrentItem(mint(product.get()));
		return CraftHookResult.IDENTITY_MINTED;
	}

	public Optional<DoorCraftProduct> productFor(org.bukkit.inventory.Recipe recipe)
	{
		if(!(recipe instanceof Keyed keyed))
		{
			return Optional.empty();
		}
		NamespacedKey key = keyed.getKey();
		if(pairKitRecipeKey.equals(key))
		{
			return Optional.of(DoorCraftProduct.PAIR_KIT);
		}
		if(personalDoorRecipeKey.equals(key))
		{
			return Optional.of(DoorCraftProduct.PERSONAL_DOOR);
		}
		if(ironDoorRecipeKey.equals(key))
		{
			return Optional.of(DoorCraftProduct.IRON_DOOR);
		}
		return Optional.empty();
	}

	public DoorItemPdcCodec codec()
	{
		return codec;
	}

	private ItemStack mint(DoorCraftProduct product)
	{
		return switch(product)
		{
			case PAIR_KIT -> createPairKit();
			case PERSONAL_DOOR -> createPersonalDoor();
			case IRON_DOOR -> createIronDoor();
		};
	}

	private ItemStack craftTemplate(DoorCraftProduct product)
	{
		ItemStack template = switch(product)
		{
			case PAIR_KIT -> styledItem(
				PAIR_KIT_MATERIAL,
				"Entangled Door Pair",
				ChatColor.LIGHT_PURPLE,
				List.of("Contains two automatically linked Wormhole Doors."));
			case PERSONAL_DOOR -> styledItem(
				PERSONAL_DOOR_MATERIAL,
				"Personal Dimension Door",
				ChatColor.AQUA,
				List.of("Each traveler enters their own persistent dimension."));
			case IRON_DOOR -> styledItem(
				IRON_DOOR_MATERIAL,
				"Iron Dimension Door",
				ChatColor.GOLD,
				List.of("Permanently bound to this door's dimension."));
		};
		ItemMeta meta = template.getItemMeta();
		codec.encodeCraftProduct(meta.getPersistentDataContainer(), product);
		template.setItemMeta(meta);
		return template;
	}

	private static ItemStack styledItem(
		Material material,
		String name,
		ChatColor color,
		List<String> loreLines)
	{
		ItemStack item = ItemStack.of(material);
		ItemMeta meta = item.getItemMeta();
		// This project relocates Adventure; legacy string metadata keeps the
		// server-owned ItemMeta ABI unrelocated while still rendering cleanly.
		meta.setDisplayName(color + name + ChatColor.RESET);
		meta.setLore(loreLines.stream().map(line -> ChatColor.GRAY + line).toList());
		meta.setMaxStackSize(1);
		meta.setEnchantmentGlintOverride(true);
		item.setItemMeta(meta);
		return item;
	}

	private static Material expectedMaterial(DoorKind kind)
	{
		return switch(kind)
		{
			case PAIRED -> PAIRED_DOOR_MATERIAL;
			case PERSONAL -> PERSONAL_DOOR_MATERIAL;
			case IRON -> IRON_DOOR_MATERIAL;
			case RETURN -> PocketStructureService.RETURN_DOOR_MATERIAL;
		};
	}

	private static UUID derivedId(UUID kitId, String role)
	{
		String seed = "wormholes:door-pair:v1:" + kitId + ':' + role;
		return UUID.nameUUIDFromBytes(seed.getBytes(StandardCharsets.UTF_8));
	}

	public NamespacedKey pairKitRecipeKey()
	{
		return pairKitRecipeKey;
	}

	public NamespacedKey personalDoorRecipeKey()
	{
		return personalDoorRecipeKey;
	}

	public NamespacedKey ironDoorRecipeKey()
	{
		return ironDoorRecipeKey;
	}

	public enum CraftHookResult
	{
		NOT_A_DOOR_RECIPE,
		ALREADY_CANCELLED,
		SHIFT_CRAFT_BLOCKED,
		IDENTITY_MINTED
	}

	public record PairKitContents(
		UUID kitId,
		DoorPairIdentity pairIdentity,
		ItemStack endpointA,
		ItemStack endpointB)
	{
		public PairKitContents
		{
			Objects.requireNonNull(kitId, "kitId");
			Objects.requireNonNull(pairIdentity, "pairIdentity");
			endpointA = Objects.requireNonNull(endpointA, "endpointA").clone();
			endpointB = Objects.requireNonNull(endpointB, "endpointB").clone();
		}

		@Override
		public ItemStack endpointA()
		{
			return endpointA.clone();
		}

		@Override
		public ItemStack endpointB()
		{
			return endpointB.clone();
		}
	}
}
