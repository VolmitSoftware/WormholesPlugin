package art.arcane.wormholes.network;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.UUID;

public record PortalInfo(
    UUID id,
    String name,
    String worldName,
    String typeName,
    boolean open,
    String frameNormal,
    String frameRight,
    String frameUp,
    double originX,
    double originY,
    double originZ,
    double minX,
    double minY,
    double minZ,
    double maxX,
    double maxY,
    double maxZ
) {
    public void write(DataOutputStream out) throws IOException {
        out.writeLong(id.getMostSignificantBits());
        out.writeLong(id.getLeastSignificantBits());
        out.writeUTF(name);
        out.writeUTF(worldName);
        out.writeUTF(typeName);
        out.writeBoolean(open);
        out.writeUTF(frameNormal);
        out.writeUTF(frameRight);
        out.writeUTF(frameUp);
        out.writeDouble(originX);
        out.writeDouble(originY);
        out.writeDouble(originZ);
        out.writeDouble(minX);
        out.writeDouble(minY);
        out.writeDouble(minZ);
        out.writeDouble(maxX);
        out.writeDouble(maxY);
        out.writeDouble(maxZ);
    }

    public static PortalInfo read(DataInputStream in) throws IOException {
        UUID id = new UUID(in.readLong(), in.readLong());
        String name = in.readUTF();
        String worldName = in.readUTF();
        String typeName = in.readUTF();
        boolean open = in.readBoolean();
        String frameNormal = in.readUTF();
        String frameRight = in.readUTF();
        String frameUp = in.readUTF();
        double originX = in.readDouble();
        double originY = in.readDouble();
        double originZ = in.readDouble();
        double minX = in.readDouble();
        double minY = in.readDouble();
        double minZ = in.readDouble();
        double maxX = in.readDouble();
        double maxY = in.readDouble();
        double maxZ = in.readDouble();
        return new PortalInfo(id, name, worldName, typeName, open, frameNormal, frameRight, frameUp, originX, originY, originZ, minX, minY, minZ, maxX, maxY, maxZ);
    }
}
