package art.arcane.wormholes.render;

import org.bukkit.Location;
import org.bukkit.util.Vector;

import art.arcane.volmlib.util.collection.KList;
import art.arcane.wormholes.Settings;
import art.arcane.wormholes.portal.PortalStructure;
import art.arcane.wormholes.util.AxisAlignedBB;
import art.arcane.wormholes.util.Direction;
import art.arcane.wormholes.util.VectorMath;

public final class Frustum4D {
    private final Frustum[] frustums;
    private final AxisAlignedBB region;

    public Frustum4D(Location iris, PortalStructure structure, double range) {
        double distanceToPortal = iris.distance(structure.getCenter());
        double effectiveRange = range;
        Vector portalToEye = VectorMath.reverse(VectorMath.direction(iris, structure.getCenter()));

        double padding = Settings.NEAR_PLANE_PADDING;
        Location apex;
        if (padding > 0.0001D && distanceToPortal > 0.0001D) {
            Vector backOffset = portalToEye.clone().normalize().multiply(padding);
            apex = iris.clone().add(backOffset);
        } else {
            apex = iris;
        }

        double cullRatio = Settings.FRUSTUM_CULLING_RATIO;
        KList<Frustum> built = new KList<Frustum>();
        for (Direction face : Direction.values()) {
            if (face.x() == 1 && portalToEye.getX() > cullRatio) {
                built.add(new Frustum(apex, structure, face, effectiveRange));
                continue;
            }
            if (face.x() == -1 && portalToEye.getX() < cullRatio) {
                built.add(new Frustum(apex, structure, face, effectiveRange));
                continue;
            }
            if (face.y() == 1 && portalToEye.getY() > cullRatio) {
                built.add(new Frustum(apex, structure, face, effectiveRange));
                continue;
            }
            if (face.y() == -1 && portalToEye.getY() < cullRatio) {
                built.add(new Frustum(apex, structure, face, effectiveRange));
                continue;
            }
            if (face.z() == 1 && portalToEye.getZ() > cullRatio) {
                built.add(new Frustum(apex, structure, face, effectiveRange));
                continue;
            }
            if (face.z() == -1 && portalToEye.getZ() < cullRatio) {
                built.add(new Frustum(apex, structure, face, effectiveRange));
            }
        }

        if (built.isEmpty()) {
            Direction fallback = Direction.closest(VectorMath.reverse(portalToEye.clone()));
            built.add(new Frustum(apex, structure, fallback, effectiveRange));
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
}
