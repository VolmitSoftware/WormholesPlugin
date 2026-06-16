package art.arcane.wormholes.network.view;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;

public record ViewBox(int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
    public boolean contains(int x, int y, int z) {
        return x >= minX && x <= maxX && y >= minY && y <= maxY && z >= minZ && z <= maxZ;
    }

    public double centerX() {
        return (minX + maxX + 1) * 0.5D;
    }

    public double centerY() {
        return (minY + maxY + 1) * 0.5D;
    }

    public double centerZ() {
        return (minZ + maxZ + 1) * 0.5D;
    }

    public void write(DataOutputStream out) throws IOException {
        out.writeInt(minX);
        out.writeInt(minY);
        out.writeInt(minZ);
        out.writeInt(maxX);
        out.writeInt(maxY);
        out.writeInt(maxZ);
    }

    public static ViewBox read(DataInputStream in) throws IOException {
        return new ViewBox(in.readInt(), in.readInt(), in.readInt(), in.readInt(), in.readInt(), in.readInt());
    }

    public static double conePriorityFactor(double targetX, double targetY, double targetZ,
                                            double subscriberX, double subscriberY, double subscriberZ,
                                            double forwardX, double forwardY, double forwardZ,
                                            double coneDegrees, double behindFactor) {
        double dx = targetX - subscriberX;
        double dy = targetY - subscriberY;
        double dz = targetZ - subscriberZ;
        double distanceSquared = (dx * dx) + (dy * dy) + (dz * dz);
        if (distanceSquared <= 1.0E-6D) {
            return 1.0D;
        }
        double forwardLengthSquared = (forwardX * forwardX) + (forwardY * forwardY) + (forwardZ * forwardZ);
        if (forwardLengthSquared <= 1.0E-6D) {
            return 1.0D;
        }
        double inverseDistance = 1.0D / Math.sqrt(distanceSquared);
        double inverseForward = 1.0D / Math.sqrt(forwardLengthSquared);
        double dot = ((dx * forwardX) + (dy * forwardY) + (dz * forwardZ)) * inverseDistance * inverseForward;
        double cosHalfCone = Math.cos(Math.toRadians(Math.max(0.0D, Math.min(180.0D, coneDegrees)) * 0.5D));
        if (dot >= cosHalfCone) {
            return 1.0D;
        }
        return Math.max(0.0D, Math.min(1.0D, behindFactor));
    }

    public static double yBiasFactor(double subscriberY, double sliceMidY,
                                     int caveYMax, int skyYMin, double factor) {
        double clampedFactor = Math.max(0.0D, Math.min(1.0D, factor));
        if (subscriberY < (double) caveYMax && sliceMidY > 120.0D) {
            return clampedFactor;
        }
        if (subscriberY > (double) skyYMin && sliceMidY < 50.0D) {
            return clampedFactor;
        }
        return 1.0D;
    }
}
