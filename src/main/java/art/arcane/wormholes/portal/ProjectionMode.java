package art.arcane.wormholes.portal;

import org.bukkit.Material;

import art.arcane.volmlib.util.localization.LinesKey;
import art.arcane.wormholes.Wormholes;
import art.arcane.wormholes.localization.WormholesMessages;

import java.util.List;

public enum ProjectionMode
{
	OFF(Material.TORCH, false),
	ON(Material.REDSTONE_TORCH, true);

	private final Material icon;
	private final boolean enchanted;

	ProjectionMode(Material icon, boolean enchanted)
	{
		this.icon = icon;
		this.enchanted = enchanted;
	}

	public String getDisplayName()
	{
		return localizedLines().getFirst();
	}

	public String getLoreLine1()
	{
		return localizedLines().get(1);
	}

	public String getLoreLine2()
	{
		return localizedLines().get(2);
	}

	public Material getIcon()
	{
		return icon;
	}

	public boolean isEnchanted()
	{
		return enchanted;
	}

	public ProjectionMode next()
	{
		return this == OFF ? ON : OFF;
	}

	private List<String> localizedLines()
	{
		LinesKey key = this == OFF
				? WormholesMessages.PORTAL_MENU_PROJECTION_OFF
				: WormholesMessages.PORTAL_MENU_PROJECTION_ON;
		return Wormholes.text().legacyLines(key);
	}
}
