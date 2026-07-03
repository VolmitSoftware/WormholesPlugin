package art.arcane.wormholes.render;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import art.arcane.wormholes.portal.PortalFrame;
import art.arcane.wormholes.util.AxisAlignedBB;
import art.arcane.wormholes.util.Direction;

public final class PortalPlaneWindowSlabTest {
    private static final double ORIGIN_X = 0.5D;
    private static final double ORIGIN_Y = 64.5D;
    private static final double ORIGIN_Z = 0.5D;
    private static final int LATERAL_RADIUS = 12;
    private static final int SLAB_RADIUS = 70;

    @Test
    public void slabWindowIsConservativeSupersetOfPerCellWindow() {
        double[][] apertures = new double[][] { { 2.0D, 3.0D }, { 10.0D, 10.0D } };
        double[] paddings = new double[] { 0.0D, 0.5D };
        double[][] lateralEyeOffsets = new double[][] { { 0.0D, 0.0D }, { 1.7D, -2.3D } };
        double[] eyeDistances = new double[] { 0.2D, 0.7D, 3.0D, 10.0D };

        for (Direction normal : Direction.values()) {
            PortalFrame frame = PortalFrame.canonical(normal);
            for (double[] aperture : apertures) {
                AxisAlignedBB area = apertureArea(frame, aperture[0], aperture[1]);
                for (double padding : paddings) {
                    for (double[] lateralOffset : lateralEyeOffsets) {
                        for (double distance : eyeDistances) {
                            sweepEye(frame, area, padding, lateralOffset[0], lateralOffset[1], distance);
                            sweepEye(frame, area, padding, lateralOffset[0], lateralOffset[1], -distance);
                        }
                    }
                }
            }
        }
    }

    @Test
    public void degenerateEyeOnPlaneFallsBackToFullWindow() {
        PortalFrame frame = PortalFrame.canonical(Direction.N);
        AxisAlignedBB area = apertureArea(frame, 3.0D, 3.0D);
        PortalProjector.PortalPlaneWindow window = PortalProjector.PortalPlaneWindow.create(null, area, frame,
            ORIGIN_X, ORIGIN_Y, ORIGIN_Z, 0.0D, 0.0D);

        double[] bounds = new double[4];
        assertTrue(window.slabWindow(ORIGIN_X, ORIGIN_Y, ORIGIN_Z, -5.0D, bounds));
        assertEquals(Double.NEGATIVE_INFINITY, bounds[0]);
        assertEquals(Double.POSITIVE_INFINITY, bounds[1]);
        assertEquals(Double.NEGATIVE_INFINITY, bounds[2]);
        assertEquals(Double.POSITIVE_INFINITY, bounds[3]);
    }

    private static void sweepEye(PortalFrame frame,
                                 AxisAlignedBB area,
                                 double padding,
                                 double eyeRightOffset,
                                 double eyeUpOffset,
                                 double eyeSignedDistance) {
        Direction normal = frame.getNormal();
        Direction right = frame.getRight();
        Direction up = frame.getUp();
        int normalAxis = axisOf(normal);
        int rightAxis = axisOf(right);
        int upAxis = axisOf(up);
        int rightSign = signOf(right);
        int upSign = signOf(up);
        double[] origin = new double[] { ORIGIN_X, ORIGIN_Y, ORIGIN_Z };

        double eyeX = ORIGIN_X + (normal.x() * eyeSignedDistance) + (right.x() * eyeRightOffset) + (up.x() * eyeUpOffset);
        double eyeY = ORIGIN_Y + (normal.y() * eyeSignedDistance) + (right.y() * eyeRightOffset) + (up.y() * eyeUpOffset);
        double eyeZ = ORIGIN_Z + (normal.z() * eyeSignedDistance) + (right.z() * eyeRightOffset) + (up.z() * eyeUpOffset);
        double actualEyeDistance = ((eyeX - ORIGIN_X) * normal.x()) + ((eyeY - ORIGIN_Y) * normal.y()) + ((eyeZ - ORIGIN_Z) * normal.z());

        PortalProjector.PortalPlaneWindow window = PortalProjector.PortalPlaneWindow.create(null, area, frame,
            ORIGIN_X, ORIGIN_Y, ORIGIN_Z, padding, actualEyeDistance);

        int normalBase = (int) Math.floor(origin[normalAxis]);
        int rightBase = (int) Math.floor(origin[rightAxis]);
        int upBase = (int) Math.floor(origin[upAxis]);
        double[] bounds = new double[4];
        int[] coords = new int[3];

        for (int slab = -SLAB_RADIUS; slab <= SLAB_RADIUS; slab += 3) {
            int n = normalBase + slab;
            double slabSignedDistance = (normal.x() + normal.y() + normal.z()) * ((n + 0.5D) - origin[normalAxis]);
            boolean slabAccepted = window.slabWindow(eyeX, eyeY, eyeZ, slabSignedDistance, bounds);
            int rightBlockMin = 0;
            int rightBlockMax = 0;
            int upBlockMin = 0;
            int upBlockMax = 0;
            if (slabAccepted) {
                rightBlockMin = PortalProjector.PortalPlaneWindow.slabBlockMin(bounds[0], bounds[1], rightSign, origin[rightAxis], rightBase - 1000);
                rightBlockMax = PortalProjector.PortalPlaneWindow.slabBlockMax(bounds[0], bounds[1], rightSign, origin[rightAxis], rightBase + 1000);
                upBlockMin = PortalProjector.PortalPlaneWindow.slabBlockMin(bounds[2], bounds[3], upSign, origin[upAxis], upBase - 1000);
                upBlockMax = PortalProjector.PortalPlaneWindow.slabBlockMax(bounds[2], bounds[3], upSign, origin[upAxis], upBase + 1000);
            }
            coords[normalAxis] = n;
            for (int r = rightBase - LATERAL_RADIUS; r <= rightBase + LATERAL_RADIUS; r++) {
                coords[rightAxis] = r;
                for (int u = upBase - LATERAL_RADIUS; u <= upBase + LATERAL_RADIUS; u++) {
                    coords[upAxis] = u;
                    double cx = coords[0] + 0.5D;
                    double cy = coords[1] + 0.5D;
                    double cz = coords[2] + 0.5D;
                    double cellSignedDistance = ((cx - ORIGIN_X) * normal.x()) + ((cy - ORIGIN_Y) * normal.y()) + ((cz - ORIGIN_Z) * normal.z());
                    boolean contained = window.containsRayIntersection(eyeX, eyeY, eyeZ, cx, cy, cz, cellSignedDistance);
                    if (!slabAccepted) {
                        assertFalse(contained,
                            "slab rejected but cell accepted at n=" + n + " r=" + r + " u=" + u + " frame=" + frame.getNormal().name());
                        continue;
                    }
                    if (contained) {
                        assertTrue(r >= rightBlockMin && r <= rightBlockMax,
                            "right range missed accepted cell at n=" + n + " r=" + r + " range=[" + rightBlockMin + "," + rightBlockMax + "] frame=" + frame.getNormal().name());
                        assertTrue(u >= upBlockMin && u <= upBlockMax,
                            "up range missed accepted cell at n=" + n + " u=" + u + " range=[" + upBlockMin + "," + upBlockMax + "] frame=" + frame.getNormal().name());
                    }
                }
            }
        }
    }

    private static AxisAlignedBB apertureArea(PortalFrame frame, double width, double height) {
        Direction right = frame.getRight();
        Direction up = frame.getUp();
        double halfWidth = width * 0.5D;
        double halfHeight = height * 0.5D;
        double minX = Double.POSITIVE_INFINITY;
        double minY = Double.POSITIVE_INFINITY;
        double minZ = Double.POSITIVE_INFINITY;
        double maxX = Double.NEGATIVE_INFINITY;
        double maxY = Double.NEGATIVE_INFINITY;
        double maxZ = Double.NEGATIVE_INFINITY;
        for (int rightStep = -1; rightStep <= 1; rightStep += 2) {
            for (int upStep = -1; upStep <= 1; upStep += 2) {
                double x = ORIGIN_X + (right.x() * halfWidth * rightStep) + (up.x() * halfHeight * upStep);
                double y = ORIGIN_Y + (right.y() * halfWidth * rightStep) + (up.y() * halfHeight * upStep);
                double z = ORIGIN_Z + (right.z() * halfWidth * rightStep) + (up.z() * halfHeight * upStep);
                minX = Math.min(minX, x);
                minY = Math.min(minY, y);
                minZ = Math.min(minZ, z);
                maxX = Math.max(maxX, x);
                maxY = Math.max(maxY, y);
                maxZ = Math.max(maxZ, z);
            }
        }
        return new AxisAlignedBB(minX, maxX, minY, maxY, minZ, maxZ);
    }

    private static int axisOf(Direction direction) {
        if (direction.x() != 0) {
            return 0;
        }
        if (direction.y() != 0) {
            return 1;
        }
        return 2;
    }

    private static int signOf(Direction direction) {
        return direction.x() + direction.y() + direction.z();
    }
}
