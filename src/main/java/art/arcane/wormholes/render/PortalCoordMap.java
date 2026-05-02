package art.arcane.wormholes.render;

import org.bukkit.util.Vector;

import art.arcane.wormholes.portal.ILocalPortal;
import art.arcane.wormholes.portal.IPortal;
import art.arcane.wormholes.portal.PortalFrame;

public final class PortalCoordMap {
    private PortalCoordMap() {
    }

    public static Vector localToRemote(Vector localCellAbsolute, ILocalPortal localPortal, IPortal remotePortal) {
        Vector localOrigin = localPortal.getOrigin();
        Vector remoteOrigin = remotePortal.getOrigin();
        return localPortal.getFrame().transformPoint(localCellAbsolute, localOrigin, remoteOrigin, remotePortal.getFrame());
    }

    public static void localToRemoteInto(int localX, int localY, int localZ,
                                         double localOriginX, double localOriginY, double localOriginZ,
                                         double remoteOriginX, double remoteOriginY, double remoteOriginZ,
                                         PortalFrame localFrame, PortalFrame remoteFrame,
                                         double[] scratch3, int[] outRemote3) {
        localFrame.transformPointInto(localX, localY, localZ,
            localOriginX, localOriginY, localOriginZ,
            remoteOriginX, remoteOriginY, remoteOriginZ,
            remoteFrame, scratch3);
        outRemote3[0] = (int) Math.floor(scratch3[0]);
        outRemote3[1] = (int) Math.floor(scratch3[1]);
        outRemote3[2] = (int) Math.floor(scratch3[2]);
    }

    public static void transformPointInto(double x, double y, double z,
                                          double fromOriginX, double fromOriginY, double fromOriginZ,
                                          double toOriginX, double toOriginY, double toOriginZ,
                                          PortalFrame fromFrame, PortalFrame toFrame,
                                          double[] out3) {
        fromFrame.transformPointInto(x, y, z,
            fromOriginX, fromOriginY, fromOriginZ,
            toOriginX, toOriginY, toOriginZ,
            toFrame, out3);
    }
}
