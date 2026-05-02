package art.arcane.wormholes.render;

import org.bukkit.Location;
import org.bukkit.util.Vector;

import art.arcane.volmlib.util.collection.KList;
import art.arcane.wormholes.Settings;
import art.arcane.wormholes.portal.PortalStructure;
import art.arcane.wormholes.util.AxisAlignedBB;
import art.arcane.wormholes.util.Direction;

public final class Frustum4D {
    private static final double EPSILON = 1.0E-7D;

    private final Frustum[] frustums;
    private final AxisAlignedBB region;

    public Frustum4D(Location iris, PortalStructure structure, double range) {
        Location center = structure.getCenter();
        double portalToEyeRawX = iris.getX() - center.getX();
        double portalToEyeRawY = iris.getY() - center.getY();
        double portalToEyeRawZ = iris.getZ() - center.getZ();
        double distanceToPortal = Math.sqrt((portalToEyeRawX * portalToEyeRawX)
            + (portalToEyeRawY * portalToEyeRawY)
            + (portalToEyeRawZ * portalToEyeRawZ));
        double effectiveRange = range;
        double inverseDistance = distanceToPortal <= EPSILON ? 0.0D : 1.0D / distanceToPortal;
        double portalToEyeX = portalToEyeRawX * inverseDistance;
        double portalToEyeY = portalToEyeRawY * inverseDistance;
        double portalToEyeZ = portalToEyeRawZ * inverseDistance;

        double padding = Settings.NEAR_PLANE_PADDING;
        Location apex;
        if (padding > 0.0001D && distanceToPortal > 0.0001D) {
            Vector backOffset = new Vector(portalToEyeX * padding, portalToEyeY * padding, portalToEyeZ * padding);
            apex = iris.clone().add(backOffset);
        } else {
            apex = iris;
        }

        double cullRatio = Settings.FRUSTUM_CULLING_RATIO;
        double aperturePadding = Settings.PROJECTION_APERTURE_PADDING_BLOCKS;
        KList<Frustum> built = new KList<Frustum>();
        for (Direction face : Direction.values()) {
            if (face.x() == 1 && portalToEyeX > cullRatio) {
                addFrustums(built, apex, structure, face, effectiveRange, aperturePadding);
                continue;
            }
            if (face.x() == -1 && portalToEyeX < -cullRatio) {
                addFrustums(built, apex, structure, face, effectiveRange, aperturePadding);
                continue;
            }
            if (face.y() == 1 && portalToEyeY > cullRatio) {
                addFrustums(built, apex, structure, face, effectiveRange, aperturePadding);
                continue;
            }
            if (face.y() == -1 && portalToEyeY < -cullRatio) {
                addFrustums(built, apex, structure, face, effectiveRange, aperturePadding);
                continue;
            }
            if (face.z() == 1 && portalToEyeZ > cullRatio) {
                addFrustums(built, apex, structure, face, effectiveRange, aperturePadding);
                continue;
            }
            if (face.z() == -1 && portalToEyeZ < -cullRatio) {
                addFrustums(built, apex, structure, face, effectiveRange, aperturePadding);
            }
        }

        if (built.isEmpty()) {
            Direction fallback = Direction.closest(-portalToEyeX, -portalToEyeY, -portalToEyeZ);
            addFrustums(built, apex, structure, fallback, effectiveRange, aperturePadding);
        }

        this.frustums = built.toArray(new Frustum[built.size()]);

        AxisAlignedBB acc = new AxisAlignedBB(this.frustums[0].getRegion());
        for (int i = 1; i < this.frustums.length; i++) {
            acc.encapsulate(this.frustums[i].getRegion());
        }
        this.region = acc;
    }

    public boolean contains(Vector p) {
        return containsPrimitive(p.getX(), p.getY(), p.getZ());
    }

    public boolean contains(Location p) {
        return containsPrimitive(p.getX(), p.getY(), p.getZ());
    }

    public boolean containsPrimitive(double x, double y, double z) {
        if (!region.containsPrimitive(x, y, z)) {
            return false;
        }
        Frustum[] arr = frustums;
        int count = arr.length;
        for (int i = 0; i < count; i++) {
            if (arr[i].containsPrimitive(x, y, z)) {
                return true;
            }
        }
        return false;
    }

    public AxisAlignedBB getRegion() {
        return region;
    }

    public int getFaceCount() {
        return frustums.length;
    }

    private static void addFrustums(KList<Frustum> frustums, Location apex, PortalStructure structure, Direction face, double range, double aperturePadding) {
        KList<AxisAlignedBB> apertureFaces = structure.getApertureFaces(face);
        for (AxisAlignedBB apertureFace : apertureFaces) {
            frustums.add(new Frustum(apex, apertureFace, face, range, aperturePadding));
        }
    }
}
