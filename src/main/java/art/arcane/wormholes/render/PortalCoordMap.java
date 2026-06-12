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

    public static void reflectPointAcrossPlaneInto(double x, double y, double z,
                                                   double originX, double originY, double originZ,
                                                   PortalFrame frame,
                                                   double[] out3) {
        double nx = frame.getNormal().x();
        double ny = frame.getNormal().y();
        double nz = frame.getNormal().z();
        double offsetX = x - originX;
        double offsetY = y - originY;
        double offsetZ = z - originZ;
        double dot = offsetX * nx + offsetY * ny + offsetZ * nz;
        out3[0] = originX + offsetX - 2.0D * dot * nx;
        out3[1] = originY + offsetY - 2.0D * dot * ny;
        out3[2] = originZ + offsetZ - 2.0D * dot * nz;
    }

    public static void reflectVectorAcrossPlaneInto(double x, double y, double z,
                                                    PortalFrame frame,
                                                    double[] out3) {
        double nx = frame.getNormal().x();
        double ny = frame.getNormal().y();
        double nz = frame.getNormal().z();
        double dot = x * nx + y * ny + z * nz;
        out3[0] = x - 2.0D * dot * nx;
        out3[1] = y - 2.0D * dot * ny;
        out3[2] = z - 2.0D * dot * nz;
    }

    public static boolean reflectionFlipsWorldUp(PortalFrame planeFrame) {
        double ny = planeFrame.getNormal().y();
        return 1.0D - (2.0D * ny * ny) < -0.5D;
    }

    public static boolean transformFlipsWorldUp(PortalFrame fromFrame, PortalFrame toFrame) {
        double y = ((double) fromFrame.getRight().y() * toFrame.getRight().y())
            + ((double) fromFrame.getUp().y() * toFrame.getUp().y())
            + ((double) fromFrame.getNormal().y() * toFrame.getNormal().y());
        return y < -0.5D;
    }
}
