package art.arcane.wormholes.portal;

import org.bukkit.Material;

import net.md_5.bungee.api.ChatColor;

public enum ProjectionMode
{
	OFF(
		ChatColor.DARK_GRAY + "" + ChatColor.BOLD + "Projection Off",
		"The frame stays empty.",
		"No destination view, no mirror.",
		Material.TORCH,
		false),
	ON(
		ChatColor.GOLD + "" + ChatColor.BOLD + "Projection On",
		"Show this portal's live view.",
		"Destination or mirror imagery is visible.",
		Material.REDSTONE_TORCH,
		true);

	private final String displayName;
	private final String loreLine1;
	private final String loreLine2;
	private final Material icon;
	private final boolean enchanted;

	ProjectionMode(String displayName, String loreLine1, String loreLine2, Material icon, boolean enchanted)
	{
		this.displayName = displayName;
		this.loreLine1 = loreLine1;
		this.loreLine2 = loreLine2;
		this.icon = icon;
		this.enchanted = enchanted;
	}

	public String getDisplayName()
	{
		return displayName;
	}

	public String getLoreLine1()
	{
		return loreLine1;
	}

	public String getLoreLine2()
	{
		return loreLine2;
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
}
