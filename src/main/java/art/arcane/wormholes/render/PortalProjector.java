package art.arcane.wormholes.render;

import io.papermc.paper.math.Position;

import java.util.HashMap;
import java.util.Map;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Player;

import art.arcane.wormholes.Settings;
import art.arcane.wormholes.Wormholes;
import art.arcane.wormholes.portal.ILocalPortal;
import art.arcane.wormholes.portal.IPortal;
import art.arcane.wormholes.portal.PortalFrame;
import art.arcane.wormholes.util.AxisAlignedBB;

public final class PortalProjector {
    private final ILocalPortal portal;
    private final Player observer;
    private Map<Long, BlockData> projected;
    private Map<Long, BlockData> nextProjected;
    private Map<Long, Long> projectedRemote;
    private Map<Long, Long> nextProjectedRemote;
    private final Map<Position, BlockData> sendBuffer;
    private final Map<Position, BlockData> revertBuffer;
    private final double[] scratchRot = new double[3];
    private final double[] scratchRemotePoint = new double[3];

    private final ProjectorLighting lighting = new ProjectorLighting();
    private final ProjectedEntityRenderer entityRenderer = new ProjectedEntityRenderer();

    private Frustum4D previousFrustum;
    private boolean firstProjectionDone;
    private boolean closed;
    private long projectCallCount;
    private long lastDiagLogCall;

    public PortalProjector(ILocalPortal portal, Player observer) {
        this.portal = portal;
        this.observer = observer;
        this.projected = new HashMap<Long, BlockData>(256);
        this.nextProjected = new HashMap<Long, BlockData>(256);
        this.projectedRemote = new HashMap<Long, Long>(256);
        this.nextProjectedRemote = new HashMap<Long, Long>(256);
        this.sendBuffer = new HashMap<Position, BlockData>(64);
        this.revertBuffer = new HashMap<Position, BlockData>(64);
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
        double range = capProjectionDistance(observer, Settings.PROJECTION_RANGE);
        double depthBlocks = capProjectionDistance(observer, Settings.PROJECTION_DEPTH_BLOCKS);
        Frustum4D next;
        try {
            next = new Frustum4D(eye, portal.getStructure(), range);
        } catch (RuntimeException ex) {
            Wormholes.w("[Projector] failed to build frustum for portal " + portal.getName() + " observer " + observer.getName() + ": " + ex);
            ex.printStackTrace();
            return;
        }

        if (!firstProjectionDone) {
            Wormholes.v("[Projector] portal=" + portal.getName() + " observer=" + observer.getName()
                + " first frustum: faceCount=" + next.getFaceCount() + " region=" + formatBox(next.getRegion())
                + " range=" + range + " depth=" + depthBlocks);
        }

        AxisAlignedBB area = new AxisAlignedBB(next.getRegion());
        if (previousFrustum != null) {
            area.encapsulate(previousFrustum.getRegion());
        }

        sendBuffer.clear();
        revertBuffer.clear();
        nextProjected.clear();
        nextProjectedRemote.clear();

        int enterCount = 0;
        int exitCount = 0;
        int keptCount = 0;

        int xa = (int) Math.floor(area.getXa());
        int ya = (int) Math.floor(area.getYa());
        int za = (int) Math.floor(area.getZa());
        int xb = (int) Math.floor(area.getXb());
        int yb = (int) Math.floor(area.getYb());
        int zb = (int) Math.floor(area.getZb());

        PortalFrame localFrame = portal.getFrame();
        PortalFrame remoteFrame = dest.getFrame();
        double localOriginX = portal.getOrigin().getX();
        double localOriginY = portal.getOrigin().getY();
        double localOriginZ = portal.getOrigin().getZ();
        double remoteOriginX = dest.getOrigin().getX();
        double remoteOriginY = dest.getOrigin().getY();
        double remoteOriginZ = dest.getOrigin().getZ();

        double facingX = localFrame.getNormal().x();
        double facingY = localFrame.getNormal().y();
        double facingZ = localFrame.getNormal().z();
        double eyeX = eye.getX();
        double eyeY = eye.getY();
        double eyeZ = eye.getZ();
        double eyeRelX = eyeX - localOriginX;
        double eyeRelY = eyeY - localOriginY;
        double eyeRelZ = eyeZ - localOriginZ;
        boolean eyeFrontSide = (eyeRelX * facingX + eyeRelY * facingY + eyeRelZ * facingZ) >= 0.0D;
        double portalPlaneClearance = portalPlaneClearance(portal.getStructure().getArea(), localFrame);

        for (int x = xa; x <= xb; x++) {
            double cx = x + 0.5D;
            for (int y = ya; y <= yb; y++) {
                double cy = y + 0.5D;
                for (int z = za; z <= zb; z++) {
                    double cz = z + 0.5D;

                    boolean inNext = next.containsPrimitive(cx, cy, cz);
                    boolean inLast = previousFrustum != null && previousFrustum.containsPrimitive(cx, cy, cz);
                    long key = packKey(x, y, z);
                    BlockData previousData = projected.get(key);
                    boolean wasProjected = previousData != null;

                    if (inNext) {
                        double cellRelX = cx - localOriginX;
                        double cellRelY = cy - localOriginY;
                        double cellRelZ = cz - localOriginZ;
                        double cellDot = cellRelX * facingX + cellRelY * facingY + cellRelZ * facingZ;
                        if (!projectsBehindPortalPlane(cellDot, eyeFrontSide, portalPlaneClearance)) {
                            Block localBlock = localWorld.getBlockAt(x, y, z);
                            if (wasProjected) {
                                revertBuffer.put(Position.block(x, y, z), localBlock.getBlockData());
                                exitCount++;
                            }
                            continue;
                        }

                        if (Math.abs(cellDot) > depthBlocks + portalPlaneClearance) {
                            if (wasProjected) {
                                Block localBlock = localWorld.getBlockAt(x, y, z);
                                revertBuffer.put(Position.block(x, y, z), localBlock.getBlockData());
                                exitCount++;
                            }
                            continue;
                        }

                        localFrame.transformPointInto(cx, cy, cz,
                            localOriginX, localOriginY, localOriginZ,
                            remoteOriginX, remoteOriginY, remoteOriginZ,
                            remoteFrame, scratchRemotePoint);

                        int rx = (int) Math.floor(scratchRemotePoint[0]);
                        int ry = (int) Math.floor(scratchRemotePoint[1]);
                        int rz = (int) Math.floor(scratchRemotePoint[2]);
                        BlockData remoteData = sampleRemoteBlock(destWorld, rx, ry, rz);
                        if (remoteData == null) {
                            if (wasProjected) {
                                Block localBlock = localWorld.getBlockAt(x, y, z);
                                revertBuffer.put(Position.block(x, y, z), localBlock.getBlockData());
                                exitCount++;
                            }
                            continue;
                        }

                        BlockData projectedHit;
                        if (remoteData.getMaterial().isAir()) {
                            Block localBlock = localWorld.getBlockAt(x, y, z);
                            if (localBlock.getType().isAir()) {
                                if (wasProjected) {
                                    revertBuffer.put(Position.block(x, y, z), localBlock.getBlockData());
                                    exitCount++;
                                }
                                continue;
                            }
                            projectedHit = remoteData;
                        } else {
                            projectedHit = ProjectedBlockDataTransformer.transform(remoteData, remoteFrame, localFrame, scratchRot);
                        }
                        if (!projectedHit.equals(previousData)) {
                            sendBuffer.put(Position.block(x, y, z), projectedHit);
                            enterCount++;
                        } else {
                            keptCount++;
                        }
                        nextProjected.put(key, projectedHit);
                        nextProjectedRemote.put(key, packKey(rx, ry, rz));
                        continue;
                    }

                    if (inLast && wasProjected) {
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

        if (Settings.LIGHTING_FIDELITY) {
            if (nextProjected.isEmpty()) {
                lighting.revert(observer, localWorld);
            } else {
                lighting.apply(observer, localWorld, destWorld, nextProjected, nextProjectedRemote);
            }
        }

        updateProjectedEntities(dest, next, depthBlocks, !nextProjected.isEmpty());

        boolean shouldLog = projectCallCount <= 3L || (projectCallCount - lastDiagLogCall) >= 50L;
        if (shouldLog && (enterCount > 0 || exitCount > 0)) {
            Wormholes.v("[Projector] portal=" + portal.getName() + " observer=" + observer.getName()
                + " diff: enter=" + enterCount + " exit=" + exitCount + " kept=" + keptCount
                + " rendered=" + nextProjected.size() + " call#" + projectCallCount);
            lastDiagLogCall = projectCallCount;
        }

        Map<Long, BlockData> swap = projected;
        projected = nextProjected;
        nextProjected = swap;
        Map<Long, Long> remoteSwap = projectedRemote;
        projectedRemote = nextProjectedRemote;
        nextProjectedRemote = remoteSwap;

        previousFrustum = next;
        firstProjectionDone = true;
    }

    private static BlockData sampleRemoteBlock(World world, int x, int y, int z) {
        int minY = world.getMinHeight();
        int maxY = world.getMaxHeight() - 1;
        if (y < minY || y > maxY) {
            return null;
        }
        Block block = world.getBlockAt(x, y, z);
        return block.getBlockData();
    }

    static boolean projectsBehindPortalPlane(double signedCellDistance, boolean eyeFrontSide, double portalPlaneClearance) {
        if (Math.abs(signedCellDistance) <= portalPlaneClearance) {
            return false;
        }
        boolean cellFrontSide = signedCellDistance >= 0.0D;
        return cellFrontSide != eyeFrontSide;
    }

    static double portalPlaneClearance(AxisAlignedBB area, PortalFrame frame) {
        double normalDepth;
        if (frame.getNormal().x() != 0) {
            normalDepth = area.sizeX();
        } else if (frame.getNormal().y() != 0) {
            normalDepth = area.sizeY();
        } else {
            normalDepth = area.sizeZ();
        }
        return Math.max(0.5001D, (normalDepth * 0.5D) + 0.001D);
    }

    private void updateProjectedEntities(ILocalPortal dest, Frustum4D frustum, double depthBlocks, boolean hasVisibleProjection) {
        if (!hasVisibleProjection) {
            entityRenderer.close(observer);
            return;
        }
        entityRenderer.apply(observer, portal, dest, frustum, depthBlocks);
    }

    public void close() {
        if (closed) {
            return;
        }
        closed = true;

        if (projected.isEmpty()) {
            projectedRemote.clear();
            entityRenderer.close(observer);
            return;
        }

        if (observer == null || !observer.isOnline()) {
            projected.clear();
            projectedRemote.clear();
            entityRenderer.close(observer);
            return;
        }

        World world = observer.getWorld();
        if (world == null) {
            projected.clear();
            projectedRemote.clear();
            entityRenderer.close(observer);
            return;
        }

        revertBuffer.clear();
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

        if (Settings.LIGHTING_FIDELITY) {
            lighting.revert(observer, world);
        }
        entityRenderer.close(observer);

        projected.clear();
        projectedRemote.clear();
        revertBuffer.clear();
    }

    private static double capProjectionDistance(Player observer, double requestedBlocks) {
        if (!Settings.PROJECTION_CLIENT_VIEW_DISTANCE_CAP || observer == null) {
            return requestedBlocks;
        }
        int serverChunks = Wormholes.instance == null ? 8 : Wormholes.instance.getServer().getViewDistance();
        int clientChunks = observer.getClientViewDistance();
        if (clientChunks <= 0) {
            clientChunks = serverChunks;
        }
        int chunks = Math.max(2, Math.min(serverChunks, clientChunks));
        double cap = chunks * 16.0D;
        return Math.max(1.0D, Math.min(requestedBlocks, cap));
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
        return "[" + box.getXa() + "," + box.getYa() + "," + box.getZa()
            + " -> " + box.getXb() + "," + box.getYb() + "," + box.getZb() + "]";
    }
}
