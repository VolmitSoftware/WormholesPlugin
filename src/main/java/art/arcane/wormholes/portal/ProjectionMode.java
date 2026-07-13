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
		"Project blocks from the destination",
		"portal through this portal.",
		Material.REDSTONE_TORCH,
		true),
	ONE_WAY(
		ChatColor.GOLD + "" + ChatColor.BOLD + "Projection One-Way",
		"Project from this side only.",
		"The destination portal is disabled.",
		Material.SOUL_TORCH,
		true),
	MIRROR(
		ChatColor.AQUA + "" + ChatColor.BOLD + "Projection Mirror",
		"Reflect the local world back.",
		"See yourself looking through the frame.",
		Material.COPPER_TORCH,
		true);

	private static final ProjectionMode[] CYCLE = values();

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
		return CYCLE[(ordinal() + 1) % CYCLE.length];
	}

	public ProjectionMode nextFor(PortalType type)
	{
		if(type == PortalType.GATEWAY)
		{
			return next();
		}
		return switch(this)
		{
			case OFF -> ON;
			case ON -> MIRROR;
			case ONE_WAY, MIRROR -> OFF;
		};
	}

	public boolean isAllowedFor(PortalType type)
	{
		if(type == PortalType.GATEWAY)
		{
			return true;
		}
		return this != ONE_WAY;
	}

	public boolean allowsTraversal()
	{
		return this != MIRROR;
	}

	public static ProjectionMode fromName(String name)
	{
		if(name == null)
		{
			return ON;
		}
		try
		{
			return valueOf(name);
		}
		catch(IllegalArgumentException ignored)
		{
			return ON;
		}
	}
}
