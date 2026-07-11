package art.arcane.wormholes.portal;

import java.util.UUID;

import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
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
    private volatile ProjectionMode mirroredProjectionMode;
    private volatile MirrorRotation mirroredProjectionRotation;
    private volatile PortalPermissionMode mirroredPermissionMode;
    private volatile boolean mirroredOutgoingTraversalsEnabled;
    private volatile boolean mirroredIncomingTraversalsEnabled;
    private volatile int mirroredNetworkViewDepth;
    private volatile int mirroredNetworkViewLateralPad;
    private volatile int mirroredNetworkViewHeartbeatTicks;
    private volatile int mirroredNetworkViewEntityIntervalTicks;
    private volatile int mirroredNetworkViewUnsubscribeGraceSeconds;
    private volatile String mirroredNetworkViewFallbackBlock;

    public RemotePortal(UUID id, RemoteWorld server, Vector origin, PortalType type, boolean open, AxisAlignedBB area) {
        super(id, origin);
        this.server = server;
        this.type = type;
        this.open = open;
        this.area = area;
        this.mirroredProjectionMode = ProjectionMode.ON;
        this.mirroredProjectionRotation = MirrorRotation.DEGREES_0;
        this.mirroredPermissionMode = PortalPermissionMode.BLACKLIST;
        this.mirroredOutgoingTraversalsEnabled = true;
        this.mirroredIncomingTraversalsEnabled = true;
        this.mirroredNetworkViewDepth = 32;
        this.mirroredNetworkViewLateralPad = 8;
        this.mirroredNetworkViewHeartbeatTicks = 60;
        this.mirroredNetworkViewEntityIntervalTicks = 10;
        this.mirroredNetworkViewUnsubscribeGraceSeconds = 30;
        this.mirroredNetworkViewFallbackBlock = "minecraft:air";
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

    public ProjectionMode getMirroredProjectionMode() {
        return mirroredProjectionMode;
    }

    public void setMirroredProjectionMode(ProjectionMode mode) {
        this.mirroredProjectionMode = mode == null ? ProjectionMode.ON : mode;
    }

    public MirrorRotation getMirroredProjectionRotation() {
        return mirroredProjectionRotation;
    }

    public void setMirroredProjectionRotation(MirrorRotation rotation) {
        this.mirroredProjectionRotation = (rotation == null ? MirrorRotation.DEGREES_0 : rotation).coherentFor(getFrame());
    }

    public PortalPermissionMode getMirroredPermissionMode() {
        return mirroredPermissionMode;
    }

    public void setMirroredPermissionMode(PortalPermissionMode mode) {
        this.mirroredPermissionMode = mode == null ? PortalPermissionMode.BLACKLIST : mode;
    }

    public boolean isMirroredOutgoingTraversalsEnabled() {
        return mirroredOutgoingTraversalsEnabled;
    }

    public void setMirroredOutgoingTraversalsEnabled(boolean enabled) {
        this.mirroredOutgoingTraversalsEnabled = enabled;
    }

    public boolean isMirroredIncomingTraversalsEnabled() {
        return mirroredIncomingTraversalsEnabled;
    }

    public boolean acceptsInboundTraversal() {
        return acceptsInboundTraversal(null);
    }

    public boolean acceptsInboundTraversal(Entity entity) {
        if (!open || !mirroredProjectionMode.allowsTraversal() || !mirroredIncomingTraversalsEnabled) {
            return false;
        }
        if (!(entity instanceof Player player) || player.isOp()) {
            return true;
        }
        String node = "wormholes.portal." + LocalPortal.sanitizePermissionName(getName());
        return mirroredPermissionMode.allows(player, node);
    }

    public void setMirroredIncomingTraversalsEnabled(boolean enabled) {
        this.mirroredIncomingTraversalsEnabled = enabled;
    }

    public int getMirroredNetworkViewDepth() {
        return mirroredNetworkViewDepth;
    }

    public void setMirroredNetworkViewDepth(int depth) {
        this.mirroredNetworkViewDepth = depth;
    }

    public int getMirroredNetworkViewLateralPad() {
        return mirroredNetworkViewLateralPad;
    }

    public void setMirroredNetworkViewLateralPad(int lateralPad) {
        this.mirroredNetworkViewLateralPad = lateralPad;
    }

    public int getMirroredNetworkViewHeartbeatTicks() {
        return mirroredNetworkViewHeartbeatTicks;
    }

    public void setMirroredNetworkViewHeartbeatTicks(int ticks) {
        this.mirroredNetworkViewHeartbeatTicks = ticks;
    }

    public int getMirroredNetworkViewEntityIntervalTicks() {
        return mirroredNetworkViewEntityIntervalTicks;
    }

    public void setMirroredNetworkViewEntityIntervalTicks(int ticks) {
        this.mirroredNetworkViewEntityIntervalTicks = ticks;
    }

    public int getMirroredNetworkViewUnsubscribeGraceSeconds() {
        return mirroredNetworkViewUnsubscribeGraceSeconds;
    }

    public void setMirroredNetworkViewUnsubscribeGraceSeconds(int seconds) {
        this.mirroredNetworkViewUnsubscribeGraceSeconds = seconds;
    }

    public String getMirroredNetworkViewFallbackBlock() {
        return mirroredNetworkViewFallbackBlock;
    }

    public void setMirroredNetworkViewFallbackBlock(String blockState) {
        this.mirroredNetworkViewFallbackBlock = blockState == null ? "minecraft:air" : blockState;
    }
}
