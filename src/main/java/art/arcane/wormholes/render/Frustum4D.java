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
    private final KList<Frustum> frustums;
    private final AxisAlignedBB region;
    private final Location iris;
    private final Direction direction;

    public Frustum4D(Location iris, PortalStructure structure, double range) {
        this.iris = iris;
        this.frustums = new KList<Frustum>();

        double distanceToPortal = iris.distance(structure.getCenter());
        double effectiveRange = range + (range / (distanceToPortal + 1.0D));
        Vector eyeToPortal = VectorMath.reverse(VectorMath.direction(iris, structure.getCenter()));
        this.direction = Direction.closest(eyeToPortal);

        double cullRatio = Settings.FRUSTUM_CULLING_RATIO;
        for (Direction face : Direction.values()) {
            if (face.x() == 1 && eyeToPortal.getX() > cullRatio) {
                frustums.add(new Frustum(iris, structure, face, effectiveRange));
                continue;
            }
            if (face.x() == -1 && eyeToPortal.getX() < cullRatio) {
                frustums.add(new Frustum(iris, structure, face, effectiveRange));
                continue;
            }
            if (face.y() == 1 && eyeToPortal.getY() > cullRatio) {
                frustums.add(new Frustum(iris, structure, face, effectiveRange));
                continue;
            }
            if (face.y() == -1 && eyeToPortal.getY() < cullRatio) {
                frustums.add(new Frustum(iris, structure, face, effectiveRange));
                continue;
            }
            if (face.z() == 1 && eyeToPortal.getZ() > cullRatio) {
                frustums.add(new Frustum(iris, structure, face, effectiveRange));
                continue;
            }
            if (face.z() == -1 && eyeToPortal.getZ() < cullRatio) {
                frustums.add(new Frustum(iris, structure, face, effectiveRange));
            }
        }

        if (frustums.isEmpty()) {
            Direction fallback = Direction.closest(VectorMath.reverse(eyeToPortal.clone()));
            frustums.add(new Frustum(iris, structure, fallback, effectiveRange));
        }

        AxisAlignedBB acc = new AxisAlignedBB(frustums.get(0).getRegion());
        int count = frustums.size();
        for (int i = 1; i < count; i++) {
            acc.encapsulate(frustums.get(i).getRegion());
        }
        this.region = acc;
    }

    public boolean contains(Vector p) {
        if (!region.contains(p)) {
            return false;
        }
        int count = frustums.size();
        for (int i = 0; i < count; i++) {
            if (frustums.get(i).contains(p)) {
                return true;
            }
        }
        return false;
    }

    public boolean contains(Location p) {
        if (!region.contains(p)) {
            return false;
        }
        int count = frustums.size();
        for (int i = 0; i < count; i++) {
            if (frustums.get(i).contains(p)) {
                return true;
            }
        }
        return false;
    }

    public boolean sameIrisBlock(Frustum4D other) {
        if (other == null) {
            return false;
        }
        return iris.getBlockX() == other.iris.getBlockX()
            && iris.getBlockY() == other.iris.getBlockY()
            && iris.getBlockZ() == other.iris.getBlockZ();
    }

    public AxisAlignedBB getRegion() {
        return region;
    }

    public Direction getDirection() {
        return direction;
    }

    public Location getIris() {
        return iris;
    }

    public int getFaceCount() {
        return frustums.size();
    }
}
