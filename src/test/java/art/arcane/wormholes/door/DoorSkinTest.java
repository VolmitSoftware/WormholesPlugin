package art.arcane.wormholes.door;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;

import org.bukkit.Material;
import org.junit.jupiter.api.Test;

final class DoorSkinTest
{
	@Test
	void doorKindsUseTheirRequestedPlayerOperableDefaults()
	{
		assertEquals(Material.OAK_DOOR, DoorItemService.defaultMaterial(DoorKind.PAIR));
		assertEquals(Material.DARK_OAK_DOOR, DoorItemService.defaultMaterial(DoorKind.PERSONAL));
		assertEquals(Material.PALE_OAK_DOOR, DoorItemService.defaultMaterial(DoorKind.PUBLIC));
	}

	@Test
	void broadDoorRecognitionExistsOnlyForIdentityAndLegacySourceDetection()
	{
		assertTrue(DoorSkin.isDoor(Material.OAK_DOOR));
		assertTrue(DoorSkin.isDoor(Material.IRON_DOOR));
		assertFalse(DoorSkin.isDoor(Material.OAK_TRAPDOOR));
	}

	@Test
	void creationRecipesAcceptEveryVanillaDoorMaterial()
	{
		Set<Material> actual = Set.copyOf(DoorSkin.doorMaterials());

		assertTrue(actual.contains(Material.OAK_DOOR));
		assertTrue(actual.contains(Material.IRON_DOOR));
		assertTrue(actual.contains(Material.COPPER_DOOR));
		assertTrue(actual.contains(Material.DARK_OAK_DOOR));
		assertTrue(actual.contains(Material.PALE_OAK_DOOR));
		assertFalse(actual.stream().anyMatch(Material::isLegacy));
	}
}
