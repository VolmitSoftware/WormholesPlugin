package art.arcane.wormholes.portal;

import org.bukkit.entity.Player;

import java.util.Locale;

public enum PortalPermissionMode
{
	BLACKLIST,
	WHITELIST;

	public PortalPermissionMode next()
	{
		return this == BLACKLIST ? WHITELIST : BLACKLIST;
	}

	public boolean allows(Player player, String node)
	{
		boolean hasNode = player.hasPermission(node);
		return this == WHITELIST ? hasNode : !hasNode;
	}

	public String getDisplayName()
	{
		return this == WHITELIST ? "Whitelist" : "Blacklist";
	}

	public String getLoreLine()
	{
		return this == WHITELIST ? "Players need the node to use this portal." : "Players with the node are blocked.";
	}

	public static PortalPermissionMode fromName(String name)
	{
		if(name == null)
		{
			return BLACKLIST;
		}

		try
		{
			return PortalPermissionMode.valueOf(name.toUpperCase(Locale.ROOT));
		}
		catch(IllegalArgumentException e)
		{
			return BLACKLIST;
		}
	}
}
