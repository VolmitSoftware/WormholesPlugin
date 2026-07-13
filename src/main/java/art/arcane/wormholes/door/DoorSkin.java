package art.arcane.wormholes.door;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import org.bukkit.Material;
import org.bukkit.Tag;

final class DoorSkin
{
	private DoorSkin()
	{
	}

	static boolean isDoor(Material material)
	{
		Objects.requireNonNull(material, "material");
		return material.name().endsWith("_DOOR");
	}

	static boolean isPlayerOperable(Material material)
	{
		Objects.requireNonNull(material, "material");
		return Tag.WOODEN_DOORS.isTagged(material);
	}

	static List<Material> doorMaterials()
	{
		ArrayList<Material> materials = new ArrayList<>();
		for(Material material : Material.values())
		{
			if(isDoor(material))
			{
				materials.add(material);
			}
		}
		return List.copyOf(materials);
	}

	static List<Material> playerOperableMaterials()
	{
		ArrayList<Material> materials = new ArrayList<>();
		for(Material material : Material.values())
		{
			if(isPlayerOperable(material))
			{
				materials.add(material);
			}
		}
		return List.copyOf(materials);
	}
}
