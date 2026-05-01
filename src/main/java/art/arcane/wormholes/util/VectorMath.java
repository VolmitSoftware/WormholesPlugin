package art.arcane.wormholes.util;

import org.bukkit.Location;
import org.bukkit.util.Vector;

public final class VectorMath {
    private VectorMath() {
    }

    public static Vector direction(Location from, Location to) {
        return to.clone().subtract(from).toVector().normalize();
    }

    public static Vector directionNoNormal(Location from, Location to) {
        return to.clone().subtract(from).toVector();
    }

    public static Vector reverse(Vector v) {
        if (v.getX() != 0.0D) {
            v.setX(-v.getX());
        }
        if (v.getY() != 0.0D) {
            v.setY(-v.getY());
        }
        if (v.getZ() != 0.0D) {
            v.setZ(-v.getZ());
        }
        return v;
    }

    public static Vector triNormalize(Vector v) {
        Vector n = v.clone();
        n.setX(Math.signum(n.getX()));
        n.setY(Math.signum(n.getY()));
        n.setZ(Math.signum(n.getZ()));
        return n;
    }

    public static Vector rotate90CX(Vector v) {
        return new Vector(v.getX(), -v.getZ(), v.getY());
    }

    public static Vector rotate90CCX(Vector v) {
        return new Vector(v.getX(), v.getZ(), -v.getY());
    }

    public static Vector rotate90CY(Vector v) {
        return new Vector(v.getZ(), v.getY(), -v.getX());
    }

    public static Vector rotate90CCY(Vector v) {
        return new Vector(-v.getZ(), v.getY(), v.getX());
    }

    public static Vector rotate90CZ(Vector v) {
        return new Vector(-v.getY(), v.getX(), v.getZ());
    }

    public static Vector rotate90CCZ(Vector v) {
        return new Vector(v.getY(), -v.getX(), v.getZ());
    }
}
