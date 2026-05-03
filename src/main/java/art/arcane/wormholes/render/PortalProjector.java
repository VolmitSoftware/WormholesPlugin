package art.arcane.wormholes.render;

import io.papermc.paper.math.Position;
import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Player;

import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityAnimation.EntityAnimationType;

import art.arcane.wormholes.Settings;
import art.arcane.wormholes.Wormholes;
import art.arcane.wormholes.portal.ILocalPortal;
import art.arcane.wormholes.portal.IPortal;
import art.arcane.wormholes.portal.PortalFrame;
import art.arcane.wormholes.util.AxisAlignedBB;
import art.arcane.wormholes.util.Direction;

public final class PortalProjector {
    private static final long NO_REMOTE_KEY = Long.MIN_VALUE;
    private static final double PLANE_WINDOW_EXTRA_PADDING = 1.0D;

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
    private long lastProjectNanos;
    private int lastPlaneRejected;
    private int lastWindowRejected;
    private int lastFrustumRejected;
    private int lastRemoteSamples;
    private int lastBlockChanges;
    private int lastRenderedCells;
    private int lastReuseSkips;
    private int initialFullSendPassesRemaining;
    private double lastEyeX;
    private double lastEyeY;
    private double lastEyeZ;
    private float lastYaw;
    private float lastPitch;
    private boolean hasCameraSnapshot;
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
        this.lastProjectNanos = 0L;
        this.lastPlaneRejected = 0;
        this.lastWindowRejected = 0;
        this.lastFrustumRejected = 0;
        this.lastRemoteSamples = 0;
        this.lastBlockChanges = 0;
        this.lastRenderedCells = 0;
        this.lastReuseSkips = 0;
        this.initialFullSendPassesRemaining = Math.max(0, Settings.PROJECTION_INITIAL_RESEND_PASSES);
        this.lastEyeX = 0.0D;
        this.lastEyeY = 0.0D;
        this.lastEyeZ = 0.0D;
        this.lastYaw = 0.0F;
        this.lastPitch = 0.0F;
        this.hasCameraSnapshot = false;
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

    public boolean hasProjectedEntity(UUID entityId) {
        return entityRenderer.hasProjectedEntity(entityId);
    }

    public void sendProjectedEntityAnimation(UUID entityId, EntityAnimationType type) {
        if (closed) {
            return;
        }
        entityRenderer.sendAnimation(observer, entityId, type);
    }

    public void sendProjectedEntityHurt(UUID entityId, float yaw) {
        if (closed) {
            return;
        }
        entityRenderer.sendHurt(observer, entityId, yaw);
    }

    public String getDiagnostics() {
        return "rendered=" + lastRenderedCells
            + " changes=" + lastBlockChanges
            + " planeReject=" + lastPlaneRejected
            + " windowReject=" + lastWindowRejected
            + " frustumReject=" + lastFrustumRejected
            + " remoteSamples=" + lastRemoteSamples
            + " reuseSkips=" + lastReuseSkips
            + " nanos=" + lastProjectNanos;
    }

    public void project() {
        if (closed) {
            return;
        }

        long startNanos = System.nanoTime();
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
        if (canReuseProjection(eye)) {
            lastReuseSkips++;
            lastBlockChanges = 0;
            lastProjectNanos = System.nanoTime() - startNanos;
            return;
        }

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
        PortalFrame projectionLocalFrame = viewFrame(localFrame, eyeFrontSide);
        PortalFrame projectionRemoteFrame = viewFrame(remoteFrame, eyeFrontSide);
        prepareTransformCache(projectionRemoteFrame, projectionLocalFrame);
        double projectionFacingX = projectionLocalFrame.getNormal().x();
        double projectionFacingY = projectionLocalFrame.getNormal().y();
        double projectionFacingZ = projectionLocalFrame.getNormal().z();
        double projectionEyeDot = (eyeRelX * projectionFacingX) + (eyeRelY * projectionFacingY) + (eyeRelZ * projectionFacingZ);
        double portalPlaneClearance = portalPlaneClearance(portal.getStructure().getArea(), localFrame);
        double maxProjectionDepth = depthBlocks + portalPlaneClearance;
        double signedMinDistance = eyeFrontSide ? -maxProjectionDepth : portalPlaneClearance;
        double signedMaxDistance = eyeFrontSide ? -portalPlaneClearance : maxProjectionDepth;
        boolean forceStableCellResample = shouldResampleStableCells();
        boolean forceFullSend = initialFullSendPassesRemaining > 0;
        boolean collectLightingChunkKeys = Settings.LIGHTING_FIDELITY && isLightingUpdatePass();
        PortalPlaneWindow planeWindow = PortalPlaneWindow.create(portal.getStructure().getArea(), projectionLocalFrame,
            localOriginX, localOriginY, localOriginZ, Settings.PROJECTION_APERTURE_PADDING_BLOCKS + PLANE_WINDOW_EXTRA_PADDING,
            projectionEyeDot);
        lastPlaneRejected = 0;
        lastWindowRejected = 0;
        lastFrustumRejected = 0;
        lastRemoteSamples = 0;
        lastBlockChanges = 0;

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
                        lastPlaneRejected++;
                        continue;
                    }

                    if (Math.abs(cellDot) > maxProjectionDepth) {
                        lastPlaneRejected++;
                        continue;
                    }

                    double projectionCellDot = (cellRelX * projectionFacingX) + (cellRelY * projectionFacingY) + (cellRelZ * projectionFacingZ);
                    if (!planeWindow.containsRayIntersection(eyeX, eyeY, eyeZ, cx, cy, cz, projectionCellDot)) {
                        lastWindowRejected++;
                        continue;
                    }

                    if (!next.containsPrimitive(cx, cy, cz)) {
                        lastFrustumRejected++;
                        continue;
                    }

                    long key = packKey(x, y, z);
                    projectionLocalFrame.transformPointInto(cx, cy, cz,
                        localOriginX, localOriginY, localOriginZ,
                        remoteOriginX, remoteOriginY, remoteOriginZ,
                        projectionRemoteFrame, scratchRemotePoint);

                    int rx = (int) Math.floor(scratchRemotePoint[0]);
                    int ry = (int) Math.floor(scratchRemotePoint[1]);
                    int rz = (int) Math.floor(scratchRemotePoint[2]);
                    long remoteKey = packKey(rx, ry, rz);
                    ProjectedCell previousCell = projected.get(key);
                    BlockData previousData = previousCell == null ? null : previousCell.data;
                    long previousRemoteKey = previousCell == null ? NO_REMOTE_KEY : previousCell.remoteKey;
                    if (previousCell != null && previousRemoteKey == remoteKey) {
                        if (!forceStableCellResample && !forceFullSend) {
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
                    lastRemoteSamples++;

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
                        projectedHit = transformProjectedBlockData(remoteData, projectionRemoteFrame, projectionLocalFrame);
                    }

                    if (forceFullSend || !projectedHit.equals(previousData)) {
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
        lastBlockChanges = blockChangeBuffer.size();
        lastRenderedCells = nextProjected.size();

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

        if (forceFullSend && initialFullSendPassesRemaining > 0) {
            initialFullSendPassesRemaining--;
        }

        updateProjectedEntities(dest, next, depthBlocks, !nextProjected.isEmpty(), projectionLocalFrame, projectionRemoteFrame);
        lastProjectNanos = System.nanoTime() - startNanos;

        boolean shouldLog = projectCallCount <= 3L || (projectCallCount - lastDiagLogCall) >= 50L;
        if (shouldLog && (enterCount > 0 || exitCount > 0)) {
            Wormholes.v("[Projector] portal=" + portal.getName() + " observer=" + observer.getName()
                + " diff: enter=" + enterCount + " exit=" + exitCount + " kept=" + keptCount
                + " " + getDiagnostics() + " call#" + projectCallCount);
            lastDiagLogCall = projectCallCount;
        }

        Long2ObjectOpenHashMap<ProjectedCell> swap = projected;
        projected = nextProjected;
        nextProjected = swap;

        firstProjectionDone = true;
        rememberCamera(eye);
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

    static PortalFrame viewFrame(PortalFrame frame, boolean frontSide) {
        return frame.view(frontSide);
    }

    private void updateProjectedEntities(ILocalPortal dest,
                                         Frustum4D frustum,
                                         double depthBlocks,
                                         boolean hasVisibleProjection,
                                         PortalFrame projectionLocalFrame,
                                         PortalFrame projectionRemoteFrame) {
        if (!hasVisibleProjection) {
            entityRenderer.close(observer);
            return;
        }
        entityRenderer.apply(observer, portal, dest, frustum, depthBlocks, projectionLocalFrame, projectionRemoteFrame);
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

    private boolean canReuseProjection(Location eye) {
        if (!firstProjectionDone || projected.isEmpty() || !hasCameraSnapshot) {
            return false;
        }
        if (Settings.PROJECTION_STATIONARY_REUSE_DISTANCE_BLOCKS <= 0.0D || Settings.PROJECTION_STATIONARY_REUSE_ANGLE_DEGREES <= 0.0D) {
            return false;
        }
        if (shouldResampleStableCells()) {
            return false;
        }
        if (!pendingLightingDirtyKeys.isEmpty() && isLightingUpdatePass()) {
            return false;
        }
        return isStationaryCamera(eye.getX(), eye.getY(), eye.getZ(), eye.getYaw(), eye.getPitch(),
            lastEyeX, lastEyeY, lastEyeZ, lastYaw, lastPitch,
            Settings.PROJECTION_STATIONARY_REUSE_DISTANCE_BLOCKS, Settings.PROJECTION_STATIONARY_REUSE_ANGLE_DEGREES);
    }

    private void rememberCamera(Location eye) {
        lastEyeX = eye.getX();
        lastEyeY = eye.getY();
        lastEyeZ = eye.getZ();
        lastYaw = eye.getYaw();
        lastPitch = eye.getPitch();
        hasCameraSnapshot = true;
    }

    static boolean isStationaryCamera(double eyeX,
                                      double eyeY,
                                      double eyeZ,
                                      float yaw,
                                      float pitch,
                                      double lastEyeX,
                                      double lastEyeY,
                                      double lastEyeZ,
                                      float lastYaw,
                                      float lastPitch,
                                      double maxDistance,
                                      double maxAngleDegrees) {
        double dx = eyeX - lastEyeX;
        double dy = eyeY - lastEyeY;
        double dz = eyeZ - lastEyeZ;
        if (((dx * dx) + (dy * dy) + (dz * dz)) > maxDistance * maxDistance) {
            return false;
        }
        return Math.abs(angleDeltaDegrees(yaw, lastYaw)) <= maxAngleDegrees
            && Math.abs(angleDeltaDegrees(pitch, lastPitch)) <= maxAngleDegrees;
    }

    private static float angleDeltaDegrees(float current, float previous) {
        float delta = current - previous;
        while (delta > 180.0F) {
            delta -= 360.0F;
        }
        while (delta < -180.0F) {
            delta += 360.0F;
        }
        return delta;
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

    static final class PortalPlaneWindow {
        private static final double EPSILON = 1.0E-7D;

        private final double originX;
        private final double originY;
        private final double originZ;
        private final double rightX;
        private final double rightY;
        private final double rightZ;
        private final double upX;
        private final double upY;
        private final double upZ;
        private final double eyeSignedDistance;
        private final double rightMin;
        private final double rightMax;
        private final double upMin;
        private final double upMax;

        private PortalPlaneWindow(double originX,
                                  double originY,
                                  double originZ,
                                  double rightX,
                                  double rightY,
                                  double rightZ,
                                  double upX,
                                  double upY,
                                  double upZ,
                                  double eyeSignedDistance,
                                  double rightMin,
                                  double rightMax,
                                  double upMin,
                                  double upMax) {
            this.originX = originX;
            this.originY = originY;
            this.originZ = originZ;
            this.rightX = rightX;
            this.rightY = rightY;
            this.rightZ = rightZ;
            this.upX = upX;
            this.upY = upY;
            this.upZ = upZ;
            this.eyeSignedDistance = eyeSignedDistance;
            this.rightMin = rightMin;
            this.rightMax = rightMax;
            this.upMin = upMin;
            this.upMax = upMax;
        }

        static PortalPlaneWindow create(AxisAlignedBB area,
                                        PortalFrame frame,
                                        double originX,
                                        double originY,
                                        double originZ,
                                        double padding,
                                        double eyeSignedDistance) {
            double rightMin = Double.POSITIVE_INFINITY;
            double rightMax = Double.NEGATIVE_INFINITY;
            double upMin = Double.POSITIVE_INFINITY;
            double upMax = Double.NEGATIVE_INFINITY;

            for (int xi = 0; xi < 2; xi++) {
                double x = xi == 0 ? area.getXa() : area.getXb();
                for (int yi = 0; yi < 2; yi++) {
                    double y = yi == 0 ? area.getYa() : area.getYb();
                    for (int zi = 0; zi < 2; zi++) {
                        double z = zi == 0 ? area.getZa() : area.getZb();
                        double relX = x - originX;
                        double relY = y - originY;
                        double relZ = z - originZ;
                        double right = dot(relX, relY, relZ, frame.getRight());
                        double up = dot(relX, relY, relZ, frame.getUp());
                        rightMin = Math.min(rightMin, right);
                        rightMax = Math.max(rightMax, right);
                        upMin = Math.min(upMin, up);
                        upMax = Math.max(upMax, up);
                    }
                }
            }

            return new PortalPlaneWindow(originX, originY, originZ,
                frame.getRight().x(), frame.getRight().y(), frame.getRight().z(),
                frame.getUp().x(), frame.getUp().y(), frame.getUp().z(),
                eyeSignedDistance, rightMin - padding, rightMax + padding, upMin - padding, upMax + padding);
        }

        boolean containsRayIntersection(double eyeX,
                                        double eyeY,
                                        double eyeZ,
                                        double cellX,
                                        double cellY,
                                        double cellZ,
                                        double cellSignedDistance) {
            double denom = cellSignedDistance - eyeSignedDistance;
            if (Math.abs(denom) <= EPSILON) {
                return false;
            }
            double t = -eyeSignedDistance / denom;
            if (t < -EPSILON || t > 1.0D + EPSILON) {
                return false;
            }
            double hitX = eyeX + ((cellX - eyeX) * t);
            double hitY = eyeY + ((cellY - eyeY) * t);
            double hitZ = eyeZ + ((cellZ - eyeZ) * t);
            double relX = hitX - originX;
            double relY = hitY - originY;
            double relZ = hitZ - originZ;
            double right = (relX * rightX) + (relY * rightY) + (relZ * rightZ);
            double up = (relX * upX) + (relY * upY) + (relZ * upZ);
            return right >= rightMin - EPSILON && right <= rightMax + EPSILON
                && up >= upMin - EPSILON && up <= upMax + EPSILON;
        }

        private static double dot(double x, double y, double z, Direction direction) {
            return (x * direction.x()) + (y * direction.y()) + (z * direction.z());
        }
    }
}
