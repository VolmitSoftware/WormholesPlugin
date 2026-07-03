package art.arcane.wormholes.render;

import org.bukkit.Location;
import org.bukkit.util.Vector;

import art.arcane.wormholes.portal.PortalStructure;
import art.arcane.wormholes.util.Axis;
import art.arcane.wormholes.util.AxisAlignedBB;
import art.arcane.wormholes.util.Direction;

public final class Frustum {
    private static final double EPSILON = 1.0E-7D;

    private final double originX;
    private final double originY;
    private final double originZ;
    private final AxisAlignedBB region;
    private final double regionXa;
    private final double regionXb;
    private final double regionYa;
    private final double regionYb;
    private final double regionZa;
    private final double regionZb;
    private final Axis normalAxis;
    private final double planeCoordinate;
    private final double planeDelta;
    private final double faceXa;
    private final double faceXb;
    private final double faceYa;
    private final double faceYb;
    private final double faceZa;
    private final double faceZb;
    private final double rangeSquared;

    public Frustum(Location apex, PortalStructure structure, Direction cubeFace, double range, double aperturePadding) {
        this(apex, structure.getArea().getFace(cubeFace), cubeFace, range, aperturePadding);
    }

    public Frustum(Location apex, AxisAlignedBB apertureFace, Direction cubeFace, double range, double aperturePadding) {
        this.originX = apex.getX();
        this.originY = apex.getY();
        this.originZ = apex.getZ();
        AxisAlignedBB face = padAperture(apertureFace, cubeFace, aperturePadding);
        this.normalAxis = cubeFace.getAxis();
        this.planeCoordinate = axisValue(face.center(), normalAxis);
        this.faceXa = face.getXa();
        this.faceXb = face.getXb();
        this.faceYa = face.getYa();
        this.faceYb = face.getYb();
        this.faceZa = face.getZa();
        this.faceZb = face.getZb();
        this.rangeSquared = range * range;

        Axis thinAxis = face.getThinAxis();
        double minX = Double.POSITIVE_INFINITY;
        double minY = Double.POSITIVE_INFINITY;
        double minZ = Double.POSITIVE_INFINITY;
        double maxX = Double.NEGATIVE_INFINITY;
        double maxY = Double.NEGATIVE_INFINITY;
        double maxZ = Double.NEGATIVE_INFINITY;
        for (int corner = 0; corner < 4; corner++) {
            boolean firstHigh = corner < 2;
            boolean secondHigh = (corner & 1) == 0;
            double nearX = switch (thinAxis) {
                case X -> planeCoordinate;
                case Y, Z -> firstHigh ? faceXb : faceXa;
            };
            double nearY = switch (thinAxis) {
                case X -> firstHigh ? faceYb : faceYa;
                case Y -> planeCoordinate;
                case Z -> secondHigh ? faceYb : faceYa;
            };
            double nearZ = switch (thinAxis) {
                case X, Y -> secondHigh ? faceZb : faceZa;
                case Z -> planeCoordinate;
            };
            double dx = nearX - originX;
            double dy = nearY - originY;
            double dz = nearZ - originZ;
            double len = Math.sqrt((dx * dx) + (dy * dy) + (dz * dz));
            double farX = nearX + ((dx / len) * range);
            double farY = nearY + ((dy / len) * range);
            double farZ = nearZ + ((dz / len) * range);
            minX = Math.min(minX, Math.min(nearX, farX));
            minY = Math.min(minY, Math.min(nearY, farY));
            minZ = Math.min(minZ, Math.min(nearZ, farZ));
            maxX = Math.max(maxX, Math.max(nearX, farX));
            maxY = Math.max(maxY, Math.max(nearY, farY));
            maxZ = Math.max(maxZ, Math.max(nearZ, farZ));
        }

        this.region = new AxisAlignedBB(minX, maxX, minY, maxY, minZ, maxZ);
        this.regionXa = region.getXa();
        this.regionXb = region.getXb();
        this.regionYa = region.getYa();
        this.regionYb = region.getYb();
        this.regionZa = region.getZa();
        this.regionZb = region.getZb();
        this.planeDelta = planeCoordinate - axisValue(originX, originY, originZ, normalAxis);
    }

    public boolean contains(Location l) {
        return containsPrimitive(l.getX(), l.getY(), l.getZ());
    }

    public boolean contains(Vector v) {
        return containsPrimitive(v.getX(), v.getY(), v.getZ());
    }

    public boolean containsPrimitive(double x, double y, double z) {
        if (x < regionXa || x > regionXb || y < regionYa || y > regionYb || z < regionZa || z > regionZb) {
            return false;
        }

        double axisDelta = switch (normalAxis) {
            case X -> x - originX;
            case Y -> y - originY;
            case Z -> z - originZ;
        };
        if (Math.abs(axisDelta) <= EPSILON) {
            return false;
        }

        double t = planeDelta / axisDelta;
        if (t < -EPSILON || t > 1.0D + EPSILON) {
            return false;
        }

        double hitX = originX + ((x - originX) * t);
        double hitY = originY + ((y - originY) * t);
        double hitZ = originZ + ((z - originZ) * t);
        if (!containsFacePoint(hitX, hitY, hitZ)) {
            return false;
        }

        double dx = x - hitX;
        double dy = y - hitY;
        double dz = z - hitZ;
        return ((dx * dx) + (dy * dy) + (dz * dz)) <= rangeSquared + EPSILON;
    }

    public AxisAlignedBB getRegion() {
        return region;
    }

    private boolean containsFacePoint(double x, double y, double z) {
        return x >= faceXa - EPSILON && x <= faceXb + EPSILON
            && y >= faceYa - EPSILON && y <= faceYb + EPSILON
            && z >= faceZa - EPSILON && z <= faceZb + EPSILON;
    }

    private static double axisValue(Vector vector, Axis axis) {
        return axisValue(vector.getX(), vector.getY(), vector.getZ(), axis);
    }

    private static double axisValue(double x, double y, double z, Axis axis) {
        switch (axis) {
            case X:
                return x;
            case Y:
                return y;
            case Z:
                return z;
            default:
                return 0.0D;
        }
    }

    static AxisAlignedBB padAperture(AxisAlignedBB face, Direction cubeFace, double aperturePadding) {
        if (aperturePadding <= 0.0D) {
            return face;
        }

        double xa = face.getXa();
        double xb = face.getXb();
        double ya = face.getYa();
        double yb = face.getYb();
        double za = face.getZa();
        double zb = face.getZb();

        switch (cubeFace.getAxis()) {
            case X:
                ya -= aperturePadding;
                yb += aperturePadding;
                za -= aperturePadding;
                zb += aperturePadding;
                break;
            case Y:
                xa -= aperturePadding;
                xb += aperturePadding;
                za -= aperturePadding;
                zb += aperturePadding;
                break;
            case Z:
                xa -= aperturePadding;
                xb += aperturePadding;
                ya -= aperturePadding;
                yb += aperturePadding;
                break;
            default:
                break;
        }

        return new AxisAlignedBB(xa, xb, ya, yb, za, zb);
    }
}
