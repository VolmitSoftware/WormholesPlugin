package art.arcane.wormholes.portal;

import java.util.UUID;

public class WormholePortal extends LocalPortal implements IWormholePortal {
    public WormholePortal(UUID id, PortalType type, PortalStructure structure) {
        super(id, type, structure);
    }
}
