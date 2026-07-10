package art.arcane.wormholes.portal;

import java.util.UUID;

final class PortalAccessPolicy
{
	private PortalAccessPolicy()
	{
	}

	static boolean canManage(UUID portalId, UUID ownerId, UUID playerId, boolean administrator)
	{
		if(administrator)
		{
			return true;
		}
		return portalId != null && ownerId != null && playerId != null && !portalId.equals(ownerId) && ownerId.equals(playerId);
	}
}
