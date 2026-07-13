package art.arcane.wormholes.door;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;

import org.bukkit.Material;
import org.junit.jupiter.api.Test;

final class DoorSkinRecipeTest
{
	private static final Predicate<Material> WOODEN_SKIN = Set.of(
		Material.OAK_DOOR,
		Material.SPRUCE_DOOR,
		Material.BIRCH_DOOR,
		Material.CHERRY_DOOR)::contains;

	@Test
	void changesOnlyTheMaterialAndPreservesIdentity()
	{
		DoorItemIdentity identity = DoorItemIdentity.publicDoor(UUID.randomUUID());

		DoorSkinRecipe.Result result = DoorSkinRecipe.resolve(List.of(
			new DoorSkinRecipe.Ingredient(Material.SPRUCE_DOOR, identity),
			new DoorSkinRecipe.Ingredient(Material.CHERRY_DOOR, null)), WOODEN_SKIN).orElseThrow();

		assertEquals(identity, result.identity());
		assertEquals(Material.CHERRY_DOOR, result.material());
	}

	@Test
	void legacyIronIdentityCanBeConvertedToAPlayerOperableSkin()
	{
		DoorItemIdentity identity = DoorItemIdentity.publicDoor(UUID.randomUUID());

		DoorSkinRecipe.Result result = DoorSkinRecipe.resolve(List.of(
			new DoorSkinRecipe.Ingredient(Material.OAK_DOOR, null),
			new DoorSkinRecipe.Ingredient(Material.IRON_DOOR, identity)), WOODEN_SKIN).orElseThrow();

		assertEquals(identity, result.identity());
		assertEquals(Material.OAK_DOOR, result.material());
	}

	@Test
	void poweredTargetDuplicateIdentitiesAndExtraItemsAreRejected()
	{
		DoorItemIdentity identity = DoorItemIdentity.newPersonal();
		DoorItemIdentity secondIdentity = DoorItemIdentity.newPersonal();

		assertTrue(DoorSkinRecipe.resolve(List.of(
			new DoorSkinRecipe.Ingredient(Material.OAK_DOOR, identity),
			new DoorSkinRecipe.Ingredient(Material.IRON_DOOR, null)), WOODEN_SKIN).isEmpty());
		assertTrue(DoorSkinRecipe.resolve(List.of(
			new DoorSkinRecipe.Ingredient(Material.OAK_DOOR, identity),
			new DoorSkinRecipe.Ingredient(Material.SPRUCE_DOOR, secondIdentity)), WOODEN_SKIN).isEmpty());
		assertTrue(DoorSkinRecipe.resolve(List.of(
			new DoorSkinRecipe.Ingredient(Material.OAK_DOOR, null),
			new DoorSkinRecipe.Ingredient(Material.SPRUCE_DOOR, null)), WOODEN_SKIN).isEmpty());
		assertTrue(DoorSkinRecipe.resolve(List.of(
			new DoorSkinRecipe.Ingredient(Material.OAK_DOOR, identity),
			new DoorSkinRecipe.Ingredient(Material.SPRUCE_DOOR, null),
			new DoorSkinRecipe.Ingredient(Material.BIRCH_DOOR, null)), WOODEN_SKIN).isEmpty());
		assertTrue(DoorSkinRecipe.resolve(List.of(
			new DoorSkinRecipe.Ingredient(Material.OAK_DOOR, identity),
			new DoorSkinRecipe.Ingredient(Material.OAK_DOOR, null)), WOODEN_SKIN).isEmpty());
	}
}
