package art.arcane.wormholes.portal;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;

import org.junit.jupiter.api.Test;

public final class LocalPortalCooldownTest {
    @Test
    public void cooldownBlocksRepeatedTeleportForOneSecond() {
        UUID entityId = UUID.randomUUID();

        LocalPortal.markTeleportCooldown(entityId, 1000L);

        assertTrue(LocalPortal.isTeleportCoolingDown(entityId, 1500L));
        assertFalse(LocalPortal.isTeleportCoolingDown(entityId, 2000L));
    }

    @Test
    public void expiredCooldownIsRemoved() {
        UUID entityId = UUID.randomUUID();

        LocalPortal.markTeleportCooldown(entityId, 1000L);

        assertFalse(LocalPortal.isTeleportCoolingDown(entityId, 2001L));
        assertFalse(LocalPortal.isTeleportCoolingDown(entityId, 2002L));
    }
}
