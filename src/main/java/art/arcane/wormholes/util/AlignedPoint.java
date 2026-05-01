package art.arcane.wormholes.util;

import org.bukkit.Location;
import org.bukkit.util.Vector;

public final class AlignedPoint {
    private final double x;
    private final double y;
    private final double z;

    public AlignedPoint(double x, double y, double z) {
        this.x = x;
        this.y = y;
        this.z = z;
    }

    public AlignedPoint(Vector vector) {
        this.x = vector.getX();
        this.y = vector.getY();
        this.z = vector.getZ();
    }

    public AlignedPoint(Location location) {
        this.x = location.getX();
        this.y = location.getY();
        this.z = location.getZ();
    }

    public double getX() {
        return x;
    }

    public double getY() {
        return y;
    }

    public double getZ() {
        return z;
    }

    public Vector toVector() {
        return new Vector(x, y, z);
    }
}
