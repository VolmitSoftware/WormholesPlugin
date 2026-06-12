package art.arcane.wormholes.portal;

import java.util.UUID;

import org.bukkit.util.Vector;

import art.arcane.wormholes.network.PortalInfo;
import art.arcane.wormholes.util.AxisAlignedBB;
import art.arcane.wormholes.util.Direction;
import art.arcane.wormholes.util.RemoteWorld;

public class RemotePortal extends Portal implements IRemotePortal {
    private final RemoteWorld server;
    private final PortalType type;
    private final boolean open;
    private final AxisAlignedBB area;

    public RemotePortal(UUID id, RemoteWorld server, Vector origin, PortalType type, boolean open, AxisAlignedBB area) {
        super(id, origin);
        this.server = server;
        this.type = type;
        this.open = open;
        this.area = area;
    }

    public static RemotePortal fromInfo(String serverName, PortalInfo info) {
        RemotePortal portal = new RemotePortal(
            info.id(),
            new RemoteWorld(serverName, info.worldName()),
            new Vector(info.originX(), info.originY(), info.originZ()),
            PortalType.valueOf(info.typeName()),
            info.open(),
            new AxisAlignedBB(new Vector(info.minX(), info.minY(), info.minZ()), new Vector(info.maxX(), info.maxY(), info.maxZ()))
        );
        portal.setName(info.name());
        portal.applyFrame(new PortalFrame(Direction.valueOf(info.frameNormal()), Direction.valueOf(info.frameRight()), Direction.valueOf(info.frameUp())));
        return portal;
    }

    @Override
    public boolean isRemote() {
        return true;
    }

    @Override
    public RemoteWorld getServer() {
        return server;
    }

    public PortalType getType() {
        return type;
    }

    public boolean isOpen() {
        return open;
    }

    public AxisAlignedBB getArea() {
        return area;
    }
}
