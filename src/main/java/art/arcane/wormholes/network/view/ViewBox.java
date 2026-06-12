package art.arcane.wormholes.network.view;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;

public record ViewBox(int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
    public boolean contains(int x, int y, int z) {
        return x >= minX && x <= maxX && y >= minY && y <= maxY && z >= minZ && z <= maxZ;
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
}
