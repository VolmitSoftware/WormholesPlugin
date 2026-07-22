package art.arcane.wormholes.portal;

import org.bukkit.entity.Player;

import art.arcane.wormholes.Wormholes;
import art.arcane.wormholes.localization.WormholesMessages;

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
		return Wormholes.text().plain(this == WHITELIST
				? WormholesMessages.PORTAL_LABEL_WHITELIST
				: WormholesMessages.PORTAL_LABEL_BLACKLIST);
	}

	public String getLoreLine()
	{
		return Wormholes.text().plain(this == WHITELIST
				? WormholesMessages.PORTAL_PERMISSION_DESCRIPTION_WHITELIST
				: WormholesMessages.PORTAL_PERMISSION_DESCRIPTION_BLACKLIST);
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
