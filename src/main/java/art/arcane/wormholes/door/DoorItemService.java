package art.arcane.wormholes.door;

import art.arcane.wormholes.platform.WormholesPlatform;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import org.bukkit.ChatColor;
import org.bukkit.Keyed;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.event.inventory.CraftItemEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.Recipe;
import org.bukkit.inventory.RecipeChoice;
import org.bukkit.inventory.ShapedRecipe;
import org.bukkit.inventory.ShapelessRecipe;
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
	public static final Material PAIR_DOOR_MATERIAL = Material.OAK_DOOR;
	public static final Material PERSONAL_DOOR_MATERIAL = Material.WARPED_DOOR;
	public static final Material PUBLIC_DOOR_MATERIAL = Material.CRIMSON_DOOR;

	private final ItemStack wormholeRune;
	private final DoorItemPdcCodec codec;
	private final NamespacedKey pairKitRecipeKey;
	private final NamespacedKey personalDoorRecipeKey;
	private final NamespacedKey publicDoorRecipeKey;
	private final NamespacedKey doorSkinRecipeKey;

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
		codec = new DoorItemPdcCodec(WormholesPlatform.pluginNamespace(plugin));
		pairKitRecipeKey = new NamespacedKey(plugin, "dimensional_door_pair_kit");
		personalDoorRecipeKey = new NamespacedKey(plugin, "personal_dimensional_door");
		publicDoorRecipeKey = new NamespacedKey(plugin, "public_dimensional_door");
		doorSkinRecipeKey = new NamespacedKey(plugin, "dimensional_door_skin");
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
			ChatColor.GOLD,
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

	public ItemStack createPublicDoor()
	{
		return createDoor(DoorItemIdentity.newPublic());
	}

	public ItemStack createReturnDoor(UUID spaceId)
	{
		return createDoor(DoorItemIdentity.newReturn(Objects.requireNonNull(spaceId, "spaceId")));
	}

	public ItemStack createDoor(DoorItemIdentity identity)
	{
		Objects.requireNonNull(identity, "identity");
		return createDoor(identity, defaultMaterial(identity.kind()));
	}

	public ItemStack createDoor(DoorItemIdentity identity, Material material)
	{
		Objects.requireNonNull(identity, "identity");
		if(!DoorSkin.isPlayerOperable(Objects.requireNonNull(material, "material")))
		{
			throw new IllegalArgumentException("Dimensional-door skins must be player-operable doors");
		}
		ItemStack item = switch(identity.kind())
		{
			case PAIR -> styledItem(
				material,
				"Wormhole Door " + identity.pairEndpoint().name(),
				ChatColor.GOLD,
				List.of(
					"Automatically linked to endpoint " + identity.pairEndpoint().other().name() + ".",
					"Open the door and physically cross its threshold."));
			case PERSONAL -> styledItem(
				material,
				"Personal Dimension Door",
				ChatColor.AQUA,
				List.of(
					"Each traveler enters their own persistent dimension.",
					"The same traveler always reaches the same place."));
			case PUBLIC -> styledItem(
				material,
				"Public Dimension Door",
				ChatColor.GOLD,
				List.of(
					"Every traveler enters this door's shared dimension.",
					"Breaking and moving it preserves the shared destination."));
			case RETURN -> styledItem(
				material,
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
		return decodeDoorIdentity(item).filter(identity -> DoorSkin.isPlayerOperable(item.getType()));
	}

	public Optional<DoorItemIdentity> decodeDoorIdentity(ItemStack item)
	{
		if(item == null || item.getAmount() != 1)
		{
			return Optional.empty();
		}
		return decodeStoredIdentity(item);
	}

	private Optional<DoorItemIdentity> decodeStoredIdentity(ItemStack item)
	{
		if(item == null || !DoorSkin.isDoor(item.getType()) || !item.hasItemMeta())
		{
			return Optional.empty();
		}
		return codec.decodeIdentity(item.getItemMeta().getPersistentDataContainer());
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

	public boolean registerRecipes()
	{
		unregisterRecipes();
		boolean pairAdded = WormholesPlatform.addRecipe(pairKitRecipe(), false);
		boolean personalAdded = WormholesPlatform.addRecipe(personalDoorRecipe(), false);
		boolean publicAdded = WormholesPlatform.addRecipe(publicDoorRecipe(), true);
		boolean skinAdded = WormholesPlatform.addRecipe(doorSkinRecipe(), false);
		return pairAdded && personalAdded && publicAdded && skinAdded;
	}

	public void unregisterRecipes()
	{
		WormholesPlatform.removeRecipe(pairKitRecipeKey, false);
		WormholesPlatform.removeRecipe(personalDoorRecipeKey, false);
		WormholesPlatform.removeRecipe(publicDoorRecipeKey, true);
		WormholesPlatform.removeRecipe(doorSkinRecipeKey, false);
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

	public ShapedRecipe publicDoorRecipe()
	{
		return new ShapedRecipe(publicDoorRecipeKey, craftTemplate(DoorCraftProduct.PUBLIC_DOOR))
			.shape("RDR", " E ", " L ")
			.setIngredient('R', new RecipeChoice.ExactChoice(wormholeRune))
			.setIngredient('D', Material.CRIMSON_DOOR)
			.setIngredient('E', Material.ENDER_CHEST)
			.setIngredient('L', Material.LODESTONE);
	}

	public ShapelessRecipe doorSkinRecipe()
	{
		ItemStack template = styledItem(
			PAIR_DOOR_MATERIAL,
			"Dimensional Door Skin",
			ChatColor.GOLD,
			List.of("Combine a dimensional door with a player-operable door."));
		return new ShapelessRecipe(doorSkinRecipeKey, template)
			.addIngredient(new RecipeChoice.MaterialChoice(DoorSkin.doorMaterials()))
			.addIngredient(new RecipeChoice.MaterialChoice(DoorSkin.playerOperableMaterials()));
	}

	public boolean isDoorSkinRecipe(Recipe recipe)
	{
		return recipe instanceof Keyed keyed && doorSkinRecipeKey.equals(keyed.getKey());
	}

	public Optional<ItemStack> skinCraftResult(ItemStack[] matrix)
	{
		Objects.requireNonNull(matrix, "matrix");
		ArrayList<DoorSkinRecipe.Ingredient> ingredients = new ArrayList<>(2);
		for(ItemStack item : matrix)
		{
			if(item == null || item.getType().isAir())
			{
				continue;
			}
			Optional<DoorItemIdentity> identity = decodeStoredIdentity(item);
			if(identity.isPresent() && item.getAmount() != 1)
			{
				return Optional.empty();
			}
			ingredients.add(new DoorSkinRecipe.Ingredient(
				item.getType(),
				identity.orElse(null)));
		}
		return DoorSkinRecipe.resolve(ingredients)
			.map(result -> createDoor(result.identity(), result.material()));
	}

	/**
	 * Mints one unique result at the actual click. Shift crafting is cancelled
	 * so Bukkit never duplicates a single identity across a bulk result.
	 */
	public CraftHookResult handleCraft(CraftItemEvent event)
	{
		Objects.requireNonNull(event, "event");
		if(isDoorSkinRecipe(event.getRecipe()))
		{
			return handleSkinCraft(event);
		}
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

	private CraftHookResult handleSkinCraft(CraftItemEvent event)
	{
		if(event.isCancelled())
		{
			return CraftHookResult.ALREADY_CANCELLED;
		}
		Optional<ItemStack> result = skinCraftResult(event.getInventory().getMatrix());
		if(result.isEmpty())
		{
			event.setCancelled(true);
			event.setCurrentItem(null);
			return CraftHookResult.INVALID_SKIN_RECIPE;
		}
		if(event.isShiftClick())
		{
			event.setCancelled(true);
			return CraftHookResult.SHIFT_CRAFT_BLOCKED;
		}
		event.setCurrentItem(result.get());
		return CraftHookResult.SKIN_CHANGED;
	}

	public Optional<DoorCraftProduct> productFor(Recipe recipe)
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
		if(publicDoorRecipeKey.equals(key))
		{
			return Optional.of(DoorCraftProduct.PUBLIC_DOOR);
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
			case PUBLIC_DOOR -> createPublicDoor();
		};
	}

	private ItemStack craftTemplate(DoorCraftProduct product)
	{
		ItemStack template = switch(product)
		{
			case PAIR_KIT -> styledItem(
				PAIR_KIT_MATERIAL,
				"Entangled Door Pair",
				ChatColor.GOLD,
				List.of("Contains two automatically linked Wormhole Doors."));
			case PERSONAL_DOOR -> styledItem(
				PERSONAL_DOOR_MATERIAL,
				"Personal Dimension Door",
				ChatColor.AQUA,
				List.of("Each traveler enters their own persistent dimension."));
			case PUBLIC_DOOR -> styledItem(
				PUBLIC_DOOR_MATERIAL,
				"Public Dimension Door",
				ChatColor.GOLD,
				List.of("Every traveler enters this door's shared dimension."));
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
		ItemStack item = WormholesPlatform.itemStack(material);
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

	public static Material defaultMaterial(DoorKind kind)
	{
		return switch(Objects.requireNonNull(kind, "kind"))
		{
			case PAIR -> PAIR_DOOR_MATERIAL;
			case PERSONAL -> PERSONAL_DOOR_MATERIAL;
			case PUBLIC -> PUBLIC_DOOR_MATERIAL;
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

	public NamespacedKey publicDoorRecipeKey()
	{
		return publicDoorRecipeKey;
	}

	public NamespacedKey doorSkinRecipeKey()
	{
		return doorSkinRecipeKey;
	}

	public enum CraftHookResult
	{
		NOT_A_DOOR_RECIPE,
		ALREADY_CANCELLED,
		SHIFT_CRAFT_BLOCKED,
		IDENTITY_MINTED,
		INVALID_SKIN_RECIPE,
		SKIN_CHANGED
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
