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
    private final GeoPolygonProc poly;
    private final AxisAlignedBB region;

    public Frustum(Location iris, PortalStructure structure, Direction cubeFace, double range) {
        this.origin = iris;
        AxisAlignedBB face = structure.getArea().getFace(cubeFace);
        KList<Location> nearPoints = new KList<Location>();
        KList<Location> farPoints = new KList<Location>();

        switch (face.getThinAxis()) {
            case X:
                nearPoints.add(face.getCornerVector(cubeFace, Direction.U, Direction.S).toLocation(iris.getWorld()));
                nearPoints.add(face.getCornerVector(cubeFace, Direction.U, Direction.N).toLocation(iris.getWorld()));
                nearPoints.add(face.getCornerVector(cubeFace, Direction.D, Direction.S).toLocation(iris.getWorld()));
                nearPoints.add(face.getCornerVector(cubeFace, Direction.D, Direction.N).toLocation(iris.getWorld()));
                break;
            case Y:
                nearPoints.add(face.getCornerVector(Direction.E, cubeFace, Direction.S).toLocation(iris.getWorld()));
                nearPoints.add(face.getCornerVector(Direction.E, cubeFace, Direction.N).toLocation(iris.getWorld()));
                nearPoints.add(face.getCornerVector(Direction.W, cubeFace, Direction.S).toLocation(iris.getWorld()));
                nearPoints.add(face.getCornerVector(Direction.W, cubeFace, Direction.N).toLocation(iris.getWorld()));
                break;
            case Z:
                nearPoints.add(face.getCornerVector(Direction.E, Direction.U, cubeFace).toLocation(iris.getWorld()));
                nearPoints.add(face.getCornerVector(Direction.E, Direction.D, cubeFace).toLocation(iris.getWorld()));
                nearPoints.add(face.getCornerVector(Direction.W, Direction.U, cubeFace).toLocation(iris.getWorld()));
                nearPoints.add(face.getCornerVector(Direction.W, Direction.D, cubeFace).toLocation(iris.getWorld()));
                break;
            default:
                break;
        }

        for (Location near : nearPoints) {
            Vector dir = VectorMath.direction(iris, near);
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
        if (!region.contains(l)) {
            return false;
        }
        GeoPoint p = toLocalGeoPoint(l);
        return poly.PointInside3DPolygon(p.getX(), p.getY(), p.getZ());
    }

    public boolean contains(Vector v) {
        if (!region.contains(v)) {
            return false;
        }
        GeoPoint p = toLocalGeoPoint(v);
        return poly.PointInside3DPolygon(p.getX(), p.getY(), p.getZ());
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

    private GeoPoint toLocalGeoPoint(Vector v) {
        return new GeoPoint(v.getX() - origin.getX(), v.getY() - origin.getY(), v.getZ() - origin.getZ());
    }
}
