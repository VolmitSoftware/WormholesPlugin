package art.arcane.wormholes.portal;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PortalAccessPolicyTest
{
	@Test
	void creatorAndAdministratorCanManagePlayerOwnedPortal()
	{
		UUID portalId = UUID.randomUUID();
		UUID ownerId = UUID.randomUUID();
		assertTrue(PortalAccessPolicy.canManage(portalId, ownerId, ownerId, false));
		assertTrue(PortalAccessPolicy.canManage(portalId, ownerId, UUID.randomUUID(), true));
	}

	@Test
	void otherPlayersCannotManagePlayerOwnedPortal()
	{
		assertFalse(PortalAccessPolicy.canManage(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), false));
	}

	@Test
	void systemOwnedPortalRequiresAdministrator()
	{
		UUID portalId = UUID.randomUUID();
		assertFalse(PortalAccessPolicy.canManage(portalId, portalId, UUID.randomUUID(), false));
		assertTrue(PortalAccessPolicy.canManage(portalId, portalId, UUID.randomUUID(), true));
	}
}
