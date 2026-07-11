package art.arcane.wormholes.door;

import org.bukkit.Material;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DimensionalDoorSoundsTest
{
	@Test
	void teleportUsesThePlayerTeleportCue()
	{
		assertEquals(DimensionalDoorSounds.SoundCue.PLAYER_TELEPORT, DimensionalDoorSounds.teleportCue());
		assertEquals("entity.player.teleport", DimensionalDoorSounds.teleportCue().key());
		assertEquals("entity.player.teleport", DimensionalDoorSounds.teleportSound());
	}

	@Test
	void ironDoorsUseTheIronCloseCue()
	{
		assertEquals(
			DimensionalDoorSounds.SoundCue.IRON_DOOR_CLOSE,
			DimensionalDoorSounds.closeCue(Material.IRON_DOOR));
		assertEquals("block.iron_door.close", DimensionalDoorSounds.closeCue(Material.IRON_DOOR).key());
		assertEquals("block.iron_door.close", DimensionalDoorSounds.closeSound(Material.IRON_DOOR));
	}

	@Test
	void netherWoodDoorsUseTheNetherWoodCloseCue()
	{
		assertEquals(
			DimensionalDoorSounds.SoundCue.NETHER_WOOD_DOOR_CLOSE,
			DimensionalDoorSounds.closeCue(Material.CRIMSON_DOOR));
		assertEquals(
			DimensionalDoorSounds.SoundCue.NETHER_WOOD_DOOR_CLOSE,
			DimensionalDoorSounds.closeCue(Material.WARPED_DOOR));
		assertEquals("block.nether_wood_door.close", DimensionalDoorSounds.closeCue(Material.WARPED_DOOR).key());
	}

	@Test
	void otherDoorsUseTheWoodenCloseCue()
	{
		assertEquals(
			DimensionalDoorSounds.SoundCue.WOODEN_DOOR_CLOSE,
			DimensionalDoorSounds.closeCue(Material.OAK_DOOR));
		assertEquals(
			DimensionalDoorSounds.SoundCue.WOODEN_DOOR_CLOSE,
			DimensionalDoorSounds.closeCue(Material.BAMBOO_DOOR));
		assertEquals(
			DimensionalDoorSounds.SoundCue.WOODEN_DOOR_CLOSE,
			DimensionalDoorSounds.closeCue(Material.PALE_OAK_DOOR));
		assertEquals("block.wooden_door.close", DimensionalDoorSounds.closeCue(Material.OAK_DOOR).key());
	}

	@Test
	void closeCueRejectsNullMaterial()
	{
		assertThrows(NullPointerException.class, () -> DimensionalDoorSounds.closeCue(null));
	}
}
