package art.arcane.wormholes.door;

import org.bukkit.Material;

import java.util.Objects;

final class DimensionalDoorSounds
{
	private DimensionalDoorSounds()
	{
	}

	static String teleportSound()
	{
		return teleportCue().key();
	}

	static String closeSound(Material doorMaterial)
	{
		return closeCue(doorMaterial).key();
	}

	static SoundCue teleportCue()
	{
		return SoundCue.PLAYER_TELEPORT;
	}

	static SoundCue closeCue(Material doorMaterial)
	{
		Objects.requireNonNull(doorMaterial, "doorMaterial");
		return switch(doorMaterial)
		{
			case IRON_DOOR -> SoundCue.IRON_DOOR_CLOSE;
			case CRIMSON_DOOR, WARPED_DOOR -> SoundCue.NETHER_WOOD_DOOR_CLOSE;
			default -> SoundCue.WOODEN_DOOR_CLOSE;
		};
	}

	enum SoundCue
	{
		PLAYER_TELEPORT("entity.player.teleport"),
		IRON_DOOR_CLOSE("block.iron_door.close"),
		NETHER_WOOD_DOOR_CLOSE("block.nether_wood_door.close"),
		WOODEN_DOOR_CLOSE("block.wooden_door.close");

		private final String key;

		SoundCue(String key)
		{
			this.key = key;
		}

		String key()
		{
			return key;
		}
	}
}
