package art.arcane.wormholes.door;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.bukkit.Material;
import org.junit.jupiter.api.Test;

final class DoorSkinTest
{
	@Test
	void doorKindsUseTheirRequestedPlayerOperableDefaults()
	{
		assertEquals(Material.OAK_DOOR, DoorItemService.defaultMaterial(DoorKind.PAIR));
		assertEquals(Material.WARPED_DOOR, DoorItemService.defaultMaterial(DoorKind.PERSONAL));
		assertEquals(Material.CRIMSON_DOOR, DoorItemService.defaultMaterial(DoorKind.PUBLIC));
	}

	@Test
	void broadDoorRecognitionExistsOnlyForIdentityAndLegacySourceDetection()
	{
		assertTrue(DoorSkin.isDoor(Material.OAK_DOOR));
		assertTrue(DoorSkin.isDoor(Material.IRON_DOOR));
		assertFalse(DoorSkin.isDoor(Material.OAK_TRAPDOOR));
	}

	@Test
	void broadRecipeSourceIncludesLegacyIronDoor()
	{
		assertTrue(DoorSkin.doorMaterials().contains(Material.IRON_DOOR));
	}
}
