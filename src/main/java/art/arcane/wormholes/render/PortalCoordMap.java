package art.arcane.wormholes.render;

import org.bukkit.util.Vector;

import art.arcane.wormholes.portal.ILocalPortal;
import art.arcane.wormholes.portal.IPortal;
import art.arcane.wormholes.util.Direction;

public final class PortalCoordMap {
    private PortalCoordMap() {
    }

    public static Vector localToRemote(Vector localCellAbsolute, ILocalPortal localPortal, IPortal remotePortal) {
        Direction localFacing = localPortal.getDirection();
        Direction remoteFacing = remotePortal.getDirection();
        Vector localOrigin = localPortal.getOrigin();
        Vector remoteOrigin = remotePortal.getOrigin();

        Vector localOffset = localCellAbsolute.clone().subtract(localOrigin);

        if (localFacing.equals(remoteFacing)) {
            return remoteOrigin.clone().add(localOffset);
        }

        Vector rotated = localFacing.angle(localOffset, remoteFacing);
        return remoteOrigin.clone().add(rotated);
    }
}
