package art.arcane.wormholes.render;

import io.papermc.paper.math.Position;

import java.util.HashMap;
import java.util.Map;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;

import art.arcane.wormholes.Settings;
import art.arcane.wormholes.Wormholes;
import art.arcane.wormholes.portal.ILocalPortal;
import art.arcane.wormholes.portal.IPortal;
import art.arcane.wormholes.util.AxisAlignedBB;

public final class PortalProjector {
    private final ILocalPortal portal;
    private final Player observer;
    private final Map<Long, BlockData> projected;

    private Frustum4D current;
    private Frustum4D last;
    private boolean firstProjectionDone;
    private boolean closed;
    private long projectCallCount;
    private long lastDiagLogCall;

    public PortalProjector(ILocalPortal portal, Player observer) {
        this.portal = portal;
        this.observer = observer;
        this.projected = new HashMap<Long, BlockData>(256);
        this.firstProjectionDone = false;
        this.closed = false;
        this.projectCallCount = 0L;
        this.lastDiagLogCall = 0L;
    }

    public ILocalPortal getPortal() {
        return portal;
    }

    public Player getObserver() {
        return observer;
    }

    public boolean isClosed() {
        return closed;
    }

    public int getProjectedCount() {
        return projected.size();
    }

    public void project() {
        if (closed) {
            return;
        }

        projectCallCount++;

        if (!portal.isOpen() || !portal.hasTunnel()) {
            Wormholes.v("[Projector] portal " + portal.getName() + " no longer open/linked, closing projector");
            close();
            return;
        }

        IPortal destPortal = portal.getTunnel().getDestination();
        if (!(destPortal instanceof ILocalPortal)) {
            Wormholes.v("[Projector] portal " + portal.getName() + " destination is non-local, closing projector");
            close();
            return;
        }

        ILocalPortal dest = (ILocalPortal) destPortal;
        World localWorld = portal.getWorld();
        World destWorld = dest.getWorld();

        if (localWorld == null || destWorld == null) {
            Wormholes.w("[Projector] portal " + portal.getName() + " has null world (local=" + localWorld + " dest=" + destWorld + "), closing");
            close();
            return;
        }

        if (observer == null || !observer.isOnline()) {
            close();
            return;
        }

        if (!localWorld.equals(observer.getWorld())) {
            close();
            return;
        }

        Location eye = observer.getEyeLocation();
        double range = Settings.PROJECTION_RANGE;
        Frustum4D next;
        try {
            next = new Frustum4D(eye, portal.getStructure(), range);
        } catch (RuntimeException ex) {
            Wormholes.w("[Projector] failed to build frustum for portal " + portal.getName() + " observer " + observer.getName() + ": " + ex);
            ex.printStackTrace();
            return;
        }

        if (firstProjectionDone && current != null && next.sameIrisBlock(current)) {
            return;
        }

        if (!firstProjectionDone) {
            Wormholes.v("[Projector] portal=" + portal.getName() + " observer=" + observer.getName()
                + " first frustum: faceCount=" + next.getFaceCount() + " region=" + formatBox(next.getRegion()));
        }

        AxisAlignedBB area = new AxisAlignedBB(next.getRegion());
        if (last != null) {
            area.encapsulate(last.getRegion());
        }

        Map<Position, BlockData> sendBuffer = new HashMap<Position, BlockData>(64);
        Map<Position, BlockData> revertBuffer = new HashMap<Position, BlockData>(64);
        Map<Long, BlockData> nextProjected = new HashMap<Long, BlockData>(projected.size() + 32);

        int enterCount = 0;
        int exitCount = 0;
        int keptCount = 0;

        int xa = (int) Math.floor(area.min().getX());
        int ya = (int) Math.floor(area.min().getY());
        int za = (int) Math.floor(area.min().getZ());
        int xb = (int) Math.floor(area.max().getX());
        int yb = (int) Math.floor(area.max().getY());
        int zb = (int) Math.floor(area.max().getZ());

        int destMinY = destWorld.getMinHeight();
        int destMaxY = destWorld.getMaxHeight() - 1;

        for (int x = xa; x <= xb; x++) {
            for (int y = ya; y <= yb; y++) {
                for (int z = za; z <= zb; z++) {
                    double cx = x + 0.5D;
                    double cy = y + 0.5D;
                    double cz = z + 0.5D;
                    Vector cell = new Vector(cx, cy, cz);

                    boolean inNext = next.contains(cell);
                    boolean inLast = last != null && last.contains(cell);

                    if (inNext && inLast) {
                        long key = packKey(x, y, z);
                        BlockData prev = projected.get(key);
                        if (prev != null) {
                            nextProjected.put(key, prev);
                            keptCount++;
                        }
                        continue;
                    }

                    if (inNext) {
                        Vector localCellAbsolute = new Vector(x, y, z);
                        Vector remote = PortalCoordMap.localToRemote(localCellAbsolute, portal, dest);
                        int rx = remote.getBlockX();
                        int ry = remote.getBlockY();
                        int rz = remote.getBlockZ();
                        if (ry < destMinY || ry > destMaxY) {
                            continue;
                        }
                        Block remoteBlock = destWorld.getBlockAt(rx, ry, rz);
                        BlockData remoteData = remoteBlock.getBlockData();
                        Material material = remoteData.getMaterial();
                        if (material.isAir()) {
                            continue;
                        }
                        long key = packKey(x, y, z);
                        sendBuffer.put(Position.block(x, y, z), remoteData);
                        nextProjected.put(key, remoteData);
                        enterCount++;
                        continue;
                    }

                    if (inLast) {
                        long key = packKey(x, y, z);
                        if (!projected.containsKey(key)) {
                            continue;
                        }
                        Block localBlock = localWorld.getBlockAt(x, y, z);
                        BlockData realData = localBlock.getBlockData();
                        revertBuffer.put(Position.block(x, y, z), realData);
                        exitCount++;
                    }
                }
            }
        }

        if (!revertBuffer.isEmpty()) {
            observer.sendMultiBlockChange(revertBuffer);
        }
        if (!sendBuffer.isEmpty()) {
            observer.sendMultiBlockChange(sendBuffer);
        }

        boolean shouldLog = projectCallCount <= 3L || (projectCallCount - lastDiagLogCall) >= 50L;
        if (shouldLog && (enterCount > 0 || exitCount > 0)) {
            Wormholes.v("[Projector] portal=" + portal.getName() + " observer=" + observer.getName()
                + " diff: enter=" + enterCount + " exit=" + exitCount + " kept=" + keptCount
                + " rendered=" + nextProjected.size() + " call#" + projectCallCount);
            lastDiagLogCall = projectCallCount;
        }

        projected.clear();
        projected.putAll(nextProjected);

        last = current;
        current = next;
        firstProjectionDone = true;
    }

    public void close() {
        if (closed) {
            return;
        }
        closed = true;

        if (projected.isEmpty()) {
            return;
        }

        if (observer == null || !observer.isOnline()) {
            projected.clear();
            return;
        }

        World world = observer.getWorld();
        if (world == null) {
            projected.clear();
            return;
        }

        Map<Position, BlockData> revertBuffer = new HashMap<Position, BlockData>(projected.size());
        for (Map.Entry<Long, BlockData> entry : projected.entrySet()) {
            int x = unpackX(entry.getKey());
            int y = unpackY(entry.getKey());
            int z = unpackZ(entry.getKey());
            Block localBlock = world.getBlockAt(x, y, z);
            BlockData realData = localBlock.getBlockData();
            revertBuffer.put(Position.block(x, y, z), realData);
        }

        if (!revertBuffer.isEmpty()) {
            observer.sendMultiBlockChange(revertBuffer);
            Wormholes.v("[Projector] portal=" + portal.getName() + " observer=" + observer.getName()
                + " close: reverted=" + revertBuffer.size());
        }

        projected.clear();
    }

    private static long packKey(int x, int y, int z) {
        return (((long) x & 0x3FFFFFFL) << 38) | ((((long) y) & 0xFFFL) << 26) | (((long) z) & 0x3FFFFFFL);
    }

    private static int unpackX(long key) {
        long raw = (key >> 38) & 0x3FFFFFFL;
        return (int) ((raw << 38) >> 38);
    }

    private static int unpackY(long key) {
        long raw = (key >> 26) & 0xFFFL;
        return (int) ((raw << 52) >> 52);
    }

    private static int unpackZ(long key) {
        long raw = key & 0x3FFFFFFL;
        return (int) ((raw << 38) >> 38);
    }

    private static String formatBox(AxisAlignedBB box) {
        if (box == null) {
            return "null";
        }
        return "[" + box.min().getX() + "," + box.min().getY() + "," + box.min().getZ()
            + " -> " + box.max().getX() + "," + box.max().getY() + "," + box.max().getZ() + "]";
    }
}
