package art.arcane.wormholes.render;

import java.util.ArrayList;

import org.bukkit.Location;
import org.bukkit.util.Vector;

import art.arcane.volmlib.util.collection.KList;
import art.arcane.wormholes.geometry.GeoPoint;
import art.arcane.wormholes.geometry.GeoPolygon;
import art.arcane.wormholes.geometry.GeoPolygonProc;
import art.arcane.wormholes.portal.PortalStructure;
import art.arcane.wormholes.util.AxisAlignedBB;
import art.arcane.wormholes.util.Direction;
import art.arcane.wormholes.util.VectorMath;

public final class Frustum {
    private final Location origin;
    private final double originX;
    private final double originY;
    private final double originZ;
    private final GeoPolygonProc poly;
    private final AxisAlignedBB region;

    public Frustum(Location apex, PortalStructure structure, Direction cubeFace, double range) {
        this.origin = apex;
        this.originX = apex.getX();
        this.originY = apex.getY();
        this.originZ = apex.getZ();
        AxisAlignedBB face = structure.getArea().getFace(cubeFace);
        KList<Location> nearPoints = new KList<Location>();
        KList<Location> farPoints = new KList<Location>();

        switch (face.getThinAxis()) {
            case X:
                nearPoints.add(face.getCornerVector(cubeFace, Direction.U, Direction.S).toLocation(apex.getWorld()));
                nearPoints.add(face.getCornerVector(cubeFace, Direction.U, Direction.N).toLocation(apex.getWorld()));
                nearPoints.add(face.getCornerVector(cubeFace, Direction.D, Direction.S).toLocation(apex.getWorld()));
                nearPoints.add(face.getCornerVector(cubeFace, Direction.D, Direction.N).toLocation(apex.getWorld()));
                break;
            case Y:
                nearPoints.add(face.getCornerVector(Direction.E, cubeFace, Direction.S).toLocation(apex.getWorld()));
                nearPoints.add(face.getCornerVector(Direction.E, cubeFace, Direction.N).toLocation(apex.getWorld()));
                nearPoints.add(face.getCornerVector(Direction.W, cubeFace, Direction.S).toLocation(apex.getWorld()));
                nearPoints.add(face.getCornerVector(Direction.W, cubeFace, Direction.N).toLocation(apex.getWorld()));
                break;
            case Z:
                nearPoints.add(face.getCornerVector(Direction.E, Direction.U, cubeFace).toLocation(apex.getWorld()));
                nearPoints.add(face.getCornerVector(Direction.E, Direction.D, cubeFace).toLocation(apex.getWorld()));
                nearPoints.add(face.getCornerVector(Direction.W, Direction.U, cubeFace).toLocation(apex.getWorld()));
                nearPoints.add(face.getCornerVector(Direction.W, Direction.D, cubeFace).toLocation(apex.getWorld()));
                break;
            default:
                break;
        }

        for (Location near : nearPoints) {
            Vector dir = VectorMath.direction(apex, near);
            farPoints.add(near.clone().add(dir.multiply(range)));
        }

        KList<Location> all = new KList<Location>();
        all.addAll(nearPoints);
        all.addAll(farPoints);

        this.region = new AxisAlignedBB(all);

        ArrayList<GeoPoint> geoPoints = new ArrayList<GeoPoint>(all.size());
        for (Location l : all) {
            geoPoints.add(toLocalGeoPoint(l));
        }
        this.poly = new GeoPolygonProc(new GeoPolygon(geoPoints));
    }

    public boolean contains(Location l) {
        return containsPrimitive(l.getX(), l.getY(), l.getZ());
    }

    public boolean contains(Vector v) {
        return containsPrimitive(v.getX(), v.getY(), v.getZ());
    }

    public boolean containsPrimitive(double x, double y, double z) {
        if (!region.containsPrimitive(x, y, z)) {
            return false;
        }
        return poly.PointInside3DPolygon(x - originX, y - originY, z - originZ);
    }

    public AxisAlignedBB getRegion() {
        return region;
    }

    public Location getOrigin() {
        return origin;
    }

    private GeoPoint toLocalGeoPoint(Location l) {
        return new GeoPoint(l.getX() - origin.getX(), l.getY() - origin.getY(), l.getZ() - origin.getZ());
    }
}
