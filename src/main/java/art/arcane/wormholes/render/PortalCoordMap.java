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
        double offsetX = x - originX;
        double offsetY = y - originY;
        double offsetZ = z - originZ;
        mirrorSourceToDisplayVectorInto(offsetX, offsetY, offsetZ, frame, 0, out3);
        out3[0] += originX;
        out3[1] += originY;
        out3[2] += originZ;
    }

    public static void reflectVectorAcrossPlaneInto(double x, double y, double z,
                                                    PortalFrame frame,
                                                    double[] out3) {
        mirrorSourceToDisplayVectorInto(x, y, z, frame, 0, out3);
    }

    public static void mirrorSourceToDisplayPointInto(double x, double y, double z,
                                                       double originX, double originY, double originZ,
                                                       PortalFrame frame, int quarterTurns,
                                                       double[] out3) {
        mirrorSourceToDisplayVectorInto(x - originX, y - originY, z - originZ, frame, quarterTurns, out3);
        out3[0] += originX;
        out3[1] += originY;
        out3[2] += originZ;
    }

    public static void mirrorDisplayToSourcePointInto(double x, double y, double z,
                                                       double originX, double originY, double originZ,
                                                       PortalFrame frame, int quarterTurns,
                                                       double[] out3) {
        mirrorDisplayToSourceVectorInto(x - originX, y - originY, z - originZ, frame, quarterTurns, out3);
        out3[0] += originX;
        out3[1] += originY;
        out3[2] += originZ;
    }

    public static void mirrorSourceToDisplayVectorInto(double x, double y, double z,
                                                        PortalFrame frame, int quarterTurns,
                                                        double[] out3) {
        mirrorVectorInto(x, y, z, frame, quarterTurns, out3);
    }

    public static void mirrorDisplayToSourceVectorInto(double x, double y, double z,
                                                        PortalFrame frame, int quarterTurns,
                                                        double[] out3) {
        mirrorVectorInto(x, y, z, frame, -quarterTurns, out3);
    }

    public static boolean reflectionFlipsWorldUp(PortalFrame planeFrame) {
        return mirrorTransformFlipsWorldUp(planeFrame, 0);
    }

    public static boolean mirrorTransformFlipsWorldUp(PortalFrame planeFrame, int quarterTurns) {
        double right = planeFrame.getRight().y();
        double up = planeFrame.getUp().y();
        double normal = planeFrame.getNormal().y();
        double rotatedRight;
        double rotatedUp;
        switch(Math.floorMod(quarterTurns, 4)) {
            case 1 -> {
                rotatedRight = up;
                rotatedUp = -right;
            }
            case 2 -> {
                rotatedRight = -right;
                rotatedUp = -up;
            }
            case 3 -> {
                rotatedRight = -up;
                rotatedUp = right;
            }
            default -> {
                rotatedRight = right;
                rotatedUp = up;
            }
        }
        return (rotatedRight * planeFrame.getRight().y())
            + (rotatedUp * planeFrame.getUp().y())
            - (normal * planeFrame.getNormal().y()) < -0.5D;
    }

    public static boolean transformFlipsWorldUp(PortalFrame fromFrame, PortalFrame toFrame) {
        double y = ((double) fromFrame.getRight().y() * toFrame.getRight().y())
            + ((double) fromFrame.getUp().y() * toFrame.getUp().y())
            + ((double) fromFrame.getNormal().y() * toFrame.getNormal().y());
        return y < -0.5D;
    }

    private static void mirrorVectorInto(double x, double y, double z,
                                         PortalFrame frame, int quarterTurns,
                                         double[] out3) {
        double right = (x * frame.getRight().x()) + (y * frame.getRight().y()) + (z * frame.getRight().z());
        double up = (x * frame.getUp().x()) + (y * frame.getUp().y()) + (z * frame.getUp().z());
        double normal = (x * frame.getNormal().x()) + (y * frame.getNormal().y()) + (z * frame.getNormal().z());
        double rotatedRight;
        double rotatedUp;
        switch(Math.floorMod(quarterTurns, 4)) {
            case 1 -> {
                rotatedRight = up;
                rotatedUp = -right;
            }
            case 2 -> {
                rotatedRight = -right;
                rotatedUp = -up;
            }
            case 3 -> {
                rotatedRight = -up;
                rotatedUp = right;
            }
            default -> {
                rotatedRight = right;
                rotatedUp = up;
            }
        }
        out3[0] = (rotatedRight * frame.getRight().x()) + (rotatedUp * frame.getUp().x()) - (normal * frame.getNormal().x());
        out3[1] = (rotatedRight * frame.getRight().y()) + (rotatedUp * frame.getUp().y()) - (normal * frame.getNormal().y());
        out3[2] = (rotatedRight * frame.getRight().z()) + (rotatedUp * frame.getUp().z()) - (normal * frame.getNormal().z());
    }
}
