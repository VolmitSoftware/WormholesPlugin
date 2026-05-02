package art.arcane.wormholes.render;

import io.papermc.paper.math.Position;
import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;

import java.util.HashMap;
import java.util.Map;

import org.bukkit.Location;
import org.bukkit.Material;
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
    private static final long NO_REMOTE_KEY = Long.MIN_VALUE;

    private final ILocalPortal portal;
    private final Player observer;
    private Long2ObjectOpenHashMap<ProjectedCell> projected;
    private Long2ObjectOpenHashMap<ProjectedCell> nextProjected;
    private final Long2LongOpenHashMap nextProjectedRemote;
    private final Map<Position, BlockData> blockChangeBuffer;
    private final LongOpenHashSet pendingLightingDirtyKeys;
    private final LongOpenHashSet nextProjectedChunkKeys;
    private final HashMap<BlockData, BlockData> transformedBlockCache;
    private final BlockData airBlockData;
    private final double[] scratchRot = new double[3];
    private final double[] scratchRemotePoint = new double[3];

    private final ProjectorLighting lighting = new ProjectorLighting();
    private final ProjectedEntityRenderer entityRenderer = new ProjectedEntityRenderer();

    private boolean firstProjectionDone;
    private boolean closed;
    private long projectCallCount;
    private long lastDiagLogCall;
    private String transformedBlockCacheFrameKey;

    public PortalProjector(ILocalPortal portal, Player observer) {
        this.portal = portal;
        this.observer = observer;
        this.projected = new Long2ObjectOpenHashMap<ProjectedCell>(256);
        this.nextProjected = new Long2ObjectOpenHashMap<ProjectedCell>(256);
        this.nextProjectedRemote = new Long2LongOpenHashMap(256);
        this.nextProjectedRemote.defaultReturnValue(NO_REMOTE_KEY);
        this.blockChangeBuffer = new HashMap<Position, BlockData>(64);
        this.pendingLightingDirtyKeys = new LongOpenHashSet(64);
        this.nextProjectedChunkKeys = new LongOpenHashSet(8);
        this.transformedBlockCache = new HashMap<BlockData, BlockData>(128);
        this.airBlockData = Material.AIR.createBlockData();
        this.firstProjectionDone = false;
        this.closed = false;
        this.projectCallCount = 0L;
        this.lastDiagLogCall = 0L;
        this.transformedBlockCacheFrameKey = "";
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
                + " range=" + range + " depth=" + depthBlocks
                + " aperturePadding=" + Settings.PROJECTION_APERTURE_PADDING_BLOCKS);
        }

        blockChangeBuffer.clear();
        nextProjectedChunkKeys.clear();
        nextProjected.clear();
        nextProjectedRemote.clear();

        int enterCount = 0;
        int exitCount = 0;
        int keptCount = 0;

        int localMinY = localWorld.getMinHeight();
        int localMaxY = localWorld.getMaxHeight() - 1;
        AxisAlignedBB area = next.getRegion();
        int xa = (int) Math.floor(area.getXa());
        int ya = Math.max((int) Math.floor(area.getYa()), localMinY);
        int za = (int) Math.floor(area.getZa());
        int xb = (int) Math.floor(area.getXb());
        int yb = Math.min((int) Math.floor(area.getYb()), localMaxY);
        int zb = (int) Math.floor(area.getZb());

        PortalFrame localFrame = portal.getFrame();
        PortalFrame remoteFrame = dest.getFrame();
        prepareTransformCache(remoteFrame, localFrame);
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
        double maxProjectionDepth = depthBlocks + portalPlaneClearance;
        double signedMinDistance = eyeFrontSide ? -maxProjectionDepth : portalPlaneClearance;
        double signedMaxDistance = eyeFrontSide ? -portalPlaneClearance : maxProjectionDepth;
        boolean forceStableCellResample = shouldResampleStableCells();
        boolean collectLightingChunkKeys = Settings.LIGHTING_FIDELITY && isLightingUpdatePass();

        if (facingX != 0.0D) {
            double centerA = localOriginX + (signedMinDistance / facingX);
            double centerB = localOriginX + (signedMaxDistance / facingX);
            xa = Math.max(xa, minBlockForCenter(Math.min(centerA, centerB)));
            xb = Math.min(xb, maxBlockForCenter(Math.max(centerA, centerB)));
        } else if (facingY != 0.0D) {
            double centerA = localOriginY + (signedMinDistance / facingY);
            double centerB = localOriginY + (signedMaxDistance / facingY);
            ya = Math.max(ya, minBlockForCenter(Math.min(centerA, centerB)));
            yb = Math.min(yb, maxBlockForCenter(Math.max(centerA, centerB)));
        } else {
            double centerA = localOriginZ + (signedMinDistance / facingZ);
            double centerB = localOriginZ + (signedMaxDistance / facingZ);
            za = Math.max(za, minBlockForCenter(Math.min(centerA, centerB)));
            zb = Math.min(zb, maxBlockForCenter(Math.max(centerA, centerB)));
        }

        for (int x = xa; x <= xb; x++) {
            double cx = x + 0.5D;
            for (int y = ya; y <= yb; y++) {
                double cy = y + 0.5D;
                for (int z = za; z <= zb; z++) {
                    double cz = z + 0.5D;

                    double cellRelX = cx - localOriginX;
                    double cellRelY = cy - localOriginY;
                    double cellRelZ = cz - localOriginZ;
                    double cellDot = cellRelX * facingX + cellRelY * facingY + cellRelZ * facingZ;
                    if (!projectsBehindPortalPlane(cellDot, eyeFrontSide, portalPlaneClearance)) {
                        continue;
                    }

                    if (Math.abs(cellDot) > maxProjectionDepth) {
                        continue;
                    }

                    if (!next.containsPrimitive(cx, cy, cz)) {
                        continue;
                    }

                    long key = packKey(x, y, z);
                    localFrame.transformPointInto(cx, cy, cz,
                        localOriginX, localOriginY, localOriginZ,
                        remoteOriginX, remoteOriginY, remoteOriginZ,
                        remoteFrame, scratchRemotePoint);

                    int rx = (int) Math.floor(scratchRemotePoint[0]);
                    int ry = (int) Math.floor(scratchRemotePoint[1]);
                    int rz = (int) Math.floor(scratchRemotePoint[2]);
                    long remoteKey = packKey(rx, ry, rz);
                    ProjectedCell previousCell = projected.get(key);
                    BlockData previousData = previousCell == null ? null : previousCell.data;
                    long previousRemoteKey = previousCell == null ? NO_REMOTE_KEY : previousCell.remoteKey;
                    if (previousCell != null && previousRemoteKey == remoteKey) {
                        if (!forceStableCellResample) {
                            nextProjected.put(key, previousCell);
                            nextProjectedRemote.put(key, remoteKey);
                            if (collectLightingChunkKeys) {
                                nextProjectedChunkKeys.add(chunkKey(x, z));
                            }
                            keptCount++;
                            continue;
                        }
                    }

                    Block remoteBlock = sampleRemoteBlock(destWorld, rx, ry, rz);
                    if (remoteBlock == null) {
                        continue;
                    }

                    BlockData projectedHit;
                    Material remoteType = remoteBlock.getType();
                    if (isAir(remoteType)) {
                        Block localBlock = localWorld.getBlockAt(x, y, z);
                        if (isAir(localBlock.getType())) {
                            continue;
                        }
                        projectedHit = airBlockData;
                    } else {
                        BlockData remoteData = remoteBlock.getBlockData();
                        projectedHit = transformProjectedBlockData(remoteData, remoteFrame, localFrame);
                    }

                    if (!projectedHit.equals(previousData)) {
                        blockChangeBuffer.put(Position.block(x, y, z), projectedHit);
                        markLightingDirty(key);
                        enterCount++;
                    } else if (previousRemoteKey == NO_REMOTE_KEY || previousRemoteKey != remoteKey) {
                        markLightingDirty(key);
                        keptCount++;
                    } else {
                        keptCount++;
                    }
                    ProjectedCell nextCell = previousCell;
                    if (nextCell == null || nextCell.remoteKey != remoteKey || !nextCell.data.equals(projectedHit)) {
                        nextCell = new ProjectedCell(projectedHit, remoteKey);
                    }
                    nextProjected.put(key, nextCell);
                    nextProjectedRemote.put(key, remoteKey);
                    if (collectLightingChunkKeys) {
                        nextProjectedChunkKeys.add(chunkKey(x, z));
                    }
                }
            }
        }

        for (Long2ObjectMap.Entry<ProjectedCell> entry : projected.long2ObjectEntrySet()) {
            long key = entry.getLongKey();
            if (nextProjected.containsKey(key)) {
                continue;
            }

            int x = unpackX(key);
            int y = unpackY(key);
            int z = unpackZ(key);
            Block localBlock = localWorld.getBlockAt(x, y, z);
            blockChangeBuffer.put(Position.block(x, y, z), localBlock.getBlockData());
            markLightingDirty(key);
            exitCount++;
        }

        if (!blockChangeBuffer.isEmpty()) {
            observer.sendMultiBlockChange(blockChangeBuffer);
        }

        if (Settings.LIGHTING_FIDELITY) {
            if (nextProjected.isEmpty()) {
                lighting.revert(observer, localWorld);
                pendingLightingDirtyKeys.clear();
            } else {
                if (shouldUpdateLighting()) {
                    lighting.apply(observer, localWorld, destWorld, nextProjected.keySet(), nextProjectedRemote, pendingLightingDirtyKeys, nextProjectedChunkKeys);
                    pendingLightingDirtyKeys.clear();
                }
            }
        } else {
            lighting.revert(observer, localWorld);
            pendingLightingDirtyKeys.clear();
        }

        updateProjectedEntities(dest, next, depthBlocks, !nextProjected.isEmpty());

        boolean shouldLog = projectCallCount <= 3L || (projectCallCount - lastDiagLogCall) >= 50L;
        if (shouldLog && (enterCount > 0 || exitCount > 0)) {
            Wormholes.v("[Projector] portal=" + portal.getName() + " observer=" + observer.getName()
                + " diff: enter=" + enterCount + " exit=" + exitCount + " kept=" + keptCount
                + " rendered=" + nextProjected.size() + " call#" + projectCallCount);
            lastDiagLogCall = projectCallCount;
        }

        Long2ObjectOpenHashMap<ProjectedCell> swap = projected;
        projected = nextProjected;
        nextProjected = swap;

        firstProjectionDone = true;
    }

    private static Block sampleRemoteBlock(World world, int x, int y, int z) {
        int minY = world.getMinHeight();
        int maxY = world.getMaxHeight() - 1;
        if (y < minY || y > maxY) {
            return null;
        }
        return world.getBlockAt(x, y, z);
    }

    private static boolean isAir(Material material) {
        return material == Material.AIR || material == Material.CAVE_AIR || material == Material.VOID_AIR;
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

    private void markLightingDirty(long key) {
        pendingLightingDirtyKeys.add(key);
    }

    private boolean shouldUpdateLighting() {
        if (pendingLightingDirtyKeys.isEmpty()) {
            return false;
        }
        return isLightingUpdatePass();
    }

    private boolean isLightingUpdatePass() {
        if (!firstProjectionDone) {
            return true;
        }

        int projectionInterval = Math.max(1, Settings.PROJECTION_REFRESH_INTERVAL_TICKS);
        int lightingInterval = Math.max(1, Settings.LIGHTING_REFRESH_INTERVAL_TICKS);
        int projectPassInterval = Math.max(1, (lightingInterval + projectionInterval - 1) / projectionInterval);
        return (projectCallCount % projectPassInterval) == 0L;
    }

    private boolean shouldResampleStableCells() {
        if (!firstProjectionDone) {
            return true;
        }

        int projectionInterval = Math.max(1, Settings.PROJECTION_REFRESH_INTERVAL_TICKS);
        int resampleInterval = Math.max(1, Settings.PROJECTION_STABLE_CELL_RESAMPLE_INTERVAL_TICKS);
        int projectPassInterval = Math.max(1, (resampleInterval + projectionInterval - 1) / projectionInterval);
        return (projectCallCount % projectPassInterval) == 0L;
    }

    private void prepareTransformCache(PortalFrame fromFrame, PortalFrame toFrame) {
        String frameKey = frameCacheKey(fromFrame) + ">" + frameCacheKey(toFrame);
        if (frameKey.equals(transformedBlockCacheFrameKey)) {
            return;
        }
        transformedBlockCacheFrameKey = frameKey;
        transformedBlockCache.clear();
    }

    private BlockData transformProjectedBlockData(BlockData source, PortalFrame fromFrame, PortalFrame toFrame) {
        if (!ProjectedBlockDataTransformer.requiresTransform(source)) {
            return source;
        }

        BlockData cached = transformedBlockCache.get(source);
        if (cached != null) {
            return cached;
        }

        BlockData transformed = ProjectedBlockDataTransformer.transform(source, fromFrame, toFrame, scratchRot);
        if (transformedBlockCache.size() >= 4096) {
            transformedBlockCache.clear();
        }
        transformedBlockCache.put(source, transformed);
        return transformed;
    }

    private static String frameCacheKey(PortalFrame frame) {
        return frame.getNormal().name() + "/" + frame.getRight().name() + "/" + frame.getUp().name();
    }

    public void close() {
        if (closed) {
            return;
        }
        closed = true;

        if (projected.isEmpty()) {
            nextProjectedRemote.clear();
            pendingLightingDirtyKeys.clear();
            entityRenderer.close(observer);
            return;
        }

        if (observer == null || !observer.isOnline()) {
            projected.clear();
            nextProjectedRemote.clear();
            pendingLightingDirtyKeys.clear();
            entityRenderer.close(observer);
            return;
        }

        World world = observer.getWorld();
        if (world == null) {
            projected.clear();
            nextProjectedRemote.clear();
            pendingLightingDirtyKeys.clear();
            entityRenderer.close(observer);
            return;
        }

        blockChangeBuffer.clear();
        for (Long2ObjectMap.Entry<ProjectedCell> entry : projected.long2ObjectEntrySet()) {
            long key = entry.getLongKey();
            int x = unpackX(key);
            int y = unpackY(key);
            int z = unpackZ(key);
            Block localBlock = world.getBlockAt(x, y, z);
            BlockData realData = localBlock.getBlockData();
            blockChangeBuffer.put(Position.block(x, y, z), realData);
        }

        if (!blockChangeBuffer.isEmpty()) {
            observer.sendMultiBlockChange(blockChangeBuffer);
            Wormholes.v("[Projector] portal=" + portal.getName() + " observer=" + observer.getName()
                + " close: reverted=" + blockChangeBuffer.size());
        }

        if (Settings.LIGHTING_FIDELITY) {
            lighting.revert(observer, world);
        }
        entityRenderer.close(observer);

        projected.clear();
        nextProjectedRemote.clear();
        pendingLightingDirtyKeys.clear();
        blockChangeBuffer.clear();
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

    static int minBlockForCenter(double centerMin) {
        return (int) Math.ceil(centerMin - 0.500001D);
    }

    static int maxBlockForCenter(double centerMax) {
        return (int) Math.floor(centerMax - 0.499999D);
    }

    private static long packKey(int x, int y, int z) {
        return (((long) x & 0x3FFFFFFL) << 38) | ((((long) y) & 0xFFFL) << 26) | (((long) z) & 0x3FFFFFFL);
    }

    private static long chunkKey(int x, int z) {
        return (((long) (x >> 4)) << 32) | (((long) (z >> 4)) & 0xFFFFFFFFL);
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

    private static final class ProjectedCell {
        private final BlockData data;
        private final long remoteKey;

        private ProjectedCell(BlockData data, long remoteKey) {
            this.data = data;
            this.remoteKey = remoteKey;
        }
    }
}
