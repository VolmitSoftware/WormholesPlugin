package art.arcane.wormholes.render;

import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.UUID;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Player;

import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityAnimation.EntityAnimationType;

import art.arcane.wormholes.Settings;
import art.arcane.wormholes.Wormholes;
import art.arcane.wormholes.service.WormholesTelemetry;
import art.arcane.wormholes.network.view.RemoteViewCache;
import art.arcane.wormholes.network.view.ViewSubscriptionManager;
import art.arcane.wormholes.portal.ILocalPortal;
import art.arcane.wormholes.portal.IPortal;
import art.arcane.wormholes.portal.PortalFrame;
import art.arcane.wormholes.portal.ProjectionMode;
import art.arcane.wormholes.portal.RemotePortal;
import art.arcane.wormholes.portal.UniversalTunnel;
import art.arcane.wormholes.render.view.LiveWorldView;
import art.arcane.wormholes.render.view.ProjectionWorldView;
import art.arcane.wormholes.render.view.RemoteWorldView;
import art.arcane.wormholes.util.AxisAlignedBB;
import art.arcane.wormholes.util.Direction;

public final class PortalProjector {
    private static final long NO_REMOTE_KEY = Long.MIN_VALUE;
    private static final double PLANE_WINDOW_EXTRA_PADDING = 1.0D;
    private static final int RECURSIVE_BUCKET_SHIFT = 3;
    private static final int MAX_RECURSIVE_INDEXES_PER_PASS = 256;

    private final ILocalPortal portal;
    private final Player observer;
    private final ProjectionClaimArbiter claimArbiter;
    private Long2ObjectOpenHashMap<ProjectedBlockClaim> projected;
    private Long2ObjectOpenHashMap<ProjectedBlockClaim> nextProjected;
    private final HashMap<BlockData, BlockData> transformedBlockCache;
    private final HashMap<World, List<ILocalPortal>> recursivePortalCandidates;
    private final HashMap<ProjectionWorldView, Long2ObjectOpenHashMap<ProjectedSample>> remoteSampleCache;
    private final HashMap<World, LiveWorldView> liveViews;
    private final ArrayList<RecursivePortalIndex> recursivePortalIndexes;
    private final BlockData airBlockData;
    private final double[] scratchRot = new double[3];
    private final double[] scratchRemotePoint = new double[3];
    private final double[] scratchRemoteEye = new double[3];

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
    private int lastClaimConflicts;
    private int lastWinnerChanges;
    private int lastClaimReverts;
    private int lastMaskedCells;
    private int initialFullSendPassesRemaining;
    private double lastEyeX;
    private double lastEyeY;
    private double lastEyeZ;
    private float lastYaw;
    private float lastPitch;
    private boolean hasCameraSnapshot;
    private String transformedBlockCacheFrameKey;
    private BlockData remoteFallback;
    private String remoteFallbackState;
    private long lastRemoteRevision = -1L;
    private boolean pendingRemoteResample;
    private int remoteResendStage;

    public PortalProjector(ILocalPortal portal, Player observer, ProjectionClaimArbiter claimArbiter) {
        this.portal = portal;
        this.observer = observer;
        this.claimArbiter = claimArbiter;
        this.projected = new Long2ObjectOpenHashMap<ProjectedBlockClaim>(256);
        this.nextProjected = new Long2ObjectOpenHashMap<ProjectedBlockClaim>(256);
        this.transformedBlockCache = new HashMap<BlockData, BlockData>(128);
        this.recursivePortalCandidates = new HashMap<World, List<ILocalPortal>>(4);
        this.remoteSampleCache = new HashMap<ProjectionWorldView, Long2ObjectOpenHashMap<ProjectedSample>>(4);
        this.liveViews = new HashMap<World, LiveWorldView>(4);
        this.recursivePortalIndexes = new ArrayList<RecursivePortalIndex>(4);
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
        this.lastClaimConflicts = 0;
        this.lastWinnerChanges = 0;
        this.lastClaimReverts = 0;
        this.lastMaskedCells = 0;
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

    public int getSpoofedEntityCount() {
        return entityRenderer.getSpoofedCount();
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
            + " maskAir=" + lastMaskedCells
            + " claimConflicts=" + lastClaimConflicts
            + " winnerChanges=" + lastWinnerChanges
            + " claimReverts=" + lastClaimReverts
            + " nanos=" + lastProjectNanos;
    }

    public void project() {
        project(true, true);
    }

    public void project(boolean updateBlocks, boolean updateEntities) {
        if (closed) {
            return;
        }
        if (!updateBlocks && !updateEntities) {
            return;
        }

        long startNanos = System.nanoTime();

        if (!portal.isOpen()) {
            Wormholes.v("[Projector] portal " + portal.getName() + " no longer open, closing projector");
            close();
            return;
        }

        ProjectionMode mode = portal.getProjectionMode();
        boolean mirrorMode = mode == ProjectionMode.MIRROR;
        if (!mirrorMode && !portal.hasTunnel()) {
            Wormholes.v("[Projector] portal " + portal.getName() + " no longer linked, closing projector");
            close();
            return;
        }

        ILocalPortal dest;
        IPortal destAnchor;
        ProjectionWorldView remoteView = null;
        World localWorld = portal.getWorld();
        World destWorld;
        if (mirrorMode) {
            dest = portal;
            destAnchor = portal;
            destWorld = localWorld;
        } else {
            IPortal destPortal = portal.getTunnel().getDestination();
            if (destPortal instanceof ILocalPortal localDest) {
                dest = localDest;
                destAnchor = localDest;
                destWorld = dest.getWorld();
            } else if (destPortal instanceof RemotePortal remotePortal && portal.getTunnel() instanceof UniversalTunnel universal) {
                dest = null;
                destAnchor = remotePortal;
                destWorld = null;
                remoteView = remoteWorldView(universal.getServerName(), remotePortal.getId());
                if (remoteView == null) {
                    Wormholes.v("[Projector] portal " + portal.getName() + " remote view unavailable, closing projector");
                    close();
                    return;
                }
            } else {
                Wormholes.v("[Projector] portal " + portal.getName() + " destination is unresolvable, closing projector");
                close();
                return;
            }
        }

        if (localWorld == null || (destWorld == null && remoteView == null)) {
            Wormholes.w("[Projector] portal " + portal.getName() + " has null world (local=" + localWorld + " dest=" + destWorld + "), closing");
            close();
            return;
        }

        ProjectionWorldView destView = remoteView != null ? remoteView : liveView(destWorld);

        if (observer == null || !observer.isOnline()) {
            close();
            return;
        }

        if (!localWorld.equals(observer.getWorld())) {
            close();
            return;
        }

        Location eye = observer.getEyeLocation();
        if (!updateBlocks) {
            updateEntitiesOnly(startNanos, dest, mirrorMode, eye);
            return;
        }

        projectCallCount++;
        if (remoteView instanceof RemoteWorldView remoteResendView) {
            maybeForceRemoteResend(remoteResendView);
        }
        if (canReuseProjection(eye)) {
            lastReuseSkips++;
            lastBlockChanges = 0;
            lastProjectNanos = System.nanoTime() - startNanos;
            WormholesTelemetry.addRenderNanos(lastProjectNanos);
            if (updateEntities) {
                updateEntitiesOnly(startNanos, dest, mirrorMode, eye);
            }
            return;
        }

        double portalDepth = portal.getNetworkViewDepth();
        double range = capProjectionDistance(observer, portalDepth);
        double depthBlocks = capProjectionDistance(observer, portalDepth);
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

        nextProjected.clear();
        recursivePortalCandidates.clear();
        remoteSampleCache.clear();
        recursivePortalIndexes.clear();

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
        PortalFrame remoteFrame = mirrorMode ? localFrame.flipNormal() : destAnchor.getFrame();
        double localOriginX = portal.getOrigin().getX();
        double localOriginY = portal.getOrigin().getY();
        double localOriginZ = portal.getOrigin().getZ();
        double remoteOriginX = mirrorMode ? localOriginX : destAnchor.getOrigin().getX();
        double remoteOriginY = mirrorMode ? localOriginY : destAnchor.getOrigin().getY();
        double remoteOriginZ = mirrorMode ? localOriginZ : destAnchor.getOrigin().getZ();

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
        projectionLocalFrame.transformPointInto(eyeX, eyeY, eyeZ,
            localOriginX, localOriginY, localOriginZ,
            remoteOriginX, remoteOriginY, remoteOriginZ,
            projectionRemoteFrame, scratchRemoteEye);
        prepareTransformCache(projectionRemoteFrame, projectionLocalFrame);
        double projectionFacingX = projectionLocalFrame.getNormal().x();
        double projectionFacingY = projectionLocalFrame.getNormal().y();
        double projectionFacingZ = projectionLocalFrame.getNormal().z();
        double projectionEyeDot = (eyeRelX * projectionFacingX) + (eyeRelY * projectionFacingY) + (eyeRelZ * projectionFacingZ);
        double portalPlaneClearance = portalPlaneClearance(portal.getStructure().getArea(), localFrame);
        double maxProjectionDepth = depthBlocks + portalPlaneClearance;
        double signedMinDistance = eyeFrontSide ? -maxProjectionDepth : portalPlaneClearance;
        double signedMaxDistance = eyeFrontSide ? -portalPlaneClearance : maxProjectionDepth;
        boolean forceStableCellResample = shouldResampleStableCells() || pendingRemoteResample;
        pendingRemoteResample = false;
        boolean forceFullSend = initialFullSendPassesRemaining > 0;
        PortalPlaneWindow planeWindow = PortalPlaneWindow.create(portal.getStructure().getArea(), projectionLocalFrame,
            localOriginX, localOriginY, localOriginZ, Settings.PROJECTION_APERTURE_PADDING_BLOCKS + PLANE_WINDOW_EXTRA_PADDING,
            projectionEyeDot);
        lastPlaneRejected = 0;
        lastWindowRejected = 0;
        lastFrustumRejected = 0;
        lastRemoteSamples = 0;
        lastBlockChanges = 0;
        lastClaimConflicts = 0;
        lastWinnerChanges = 0;
        lastClaimReverts = 0;
        lastMaskedCells = 0;

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
                    ProjectedBlockClaim previousCell = projected.get(key);
                    BlockData previousData = previousCell == null ? null : previousCell.getData();
                    long previousRemoteKey = previousCell == null ? NO_REMOTE_KEY : previousCell.getLightRemoteKey();
                    if (previousCell != null && previousRemoteKey == remoteKey) {
                        if (!forceStableCellResample && !forceFullSend) {
                            nextProjected.put(key, previousCell);
                            keptCount++;
                            continue;
                        }
                    }

                    ProjectedSample sample = resolveProjectedSample(destView,
                        scratchRemotePoint[0], scratchRemotePoint[1], scratchRemotePoint[2],
                        scratchRemoteEye[0], scratchRemoteEye[1], scratchRemoteEye[2],
                        dest,
                        Settings.PROJECTION_RECURSIVE_PORTAL_DEPTH);
                    if (sample.kind == ProjectedSampleKind.NO_SAMPLE) {
                        continue;
                    }

                    BlockData projectedHit;
                    boolean maskAir = sample.kind == ProjectedSampleKind.MASK_AIR;
                    if (shouldProjectAirSample(sample.kind, isLocalAir(localWorld, x, y, z))) {
                        if (maskAir) {
                            lastMaskedCells++;
                        }
                        projectedHit = airBlockData;
                    } else if (sample.kind == ProjectedSampleKind.REMOTE_AIR) {
                        continue;
                    } else {
                        projectedHit = transformProjectedBlockData(sample.data, projectionRemoteFrame, projectionLocalFrame);
                    }

                    if (forceFullSend || !projectedHit.equals(previousData)) {
                        enterCount++;
                    } else {
                        keptCount++;
                    }
                    ProjectedBlockClaim candidateClaim = sample.asClaim(projectedHit);
                    ProjectedBlockClaim nextCell = previousCell;
                    if (nextCell == null
                        || nextCell.getLightRemoteKey() != sample.remoteKey
                        || !nextCell.sameLightSource(candidateClaim)
                        || !nextCell.getData().equals(projectedHit)
                        || nextCell.isMaskAir() != maskAir) {
                        nextCell = candidateClaim;
                    }
                    nextProjected.put(key, nextCell);
                }
            }
        }

        for (Long2ObjectMap.Entry<ProjectedBlockClaim> entry : projected.long2ObjectEntrySet()) {
            long key = entry.getLongKey();
            if (nextProjected.containsKey(key)) {
                continue;
            }
            exitCount++;
        }

        ProjectionClaimArbiter.ClaimUpdateResult claimResult = claimArbiter.submit(observer, portal, localWorld,
            nextProjected, Math.abs(projectionEyeDot), Settings.LIGHTING_FIDELITY && isLightingUpdatePass());
        lastBlockChanges = claimResult.getBlockChanges();
        lastClaimConflicts = claimResult.getConflicts();
        lastWinnerChanges = claimResult.getWinnerChanges();
        lastClaimReverts = claimResult.getReverts();
        lastRenderedCells = nextProjected.size();

        if (forceFullSend && initialFullSendPassesRemaining > 0) {
            initialFullSendPassesRemaining--;
        }

        if (updateEntities && dest != null) {
            updateProjectedEntities(dest, next, depthBlocks, !nextProjected.isEmpty(), projectionLocalFrame, projectionRemoteFrame);
        } else if (updateEntities && remoteView instanceof RemoteWorldView remoteWorldView) {
            entityRenderer.applyRemote(observer, portal,
                remoteOriginX, remoteOriginY, remoteOriginZ,
                remoteWorldView, next, depthBlocks,
                projectionLocalFrame, projectionRemoteFrame);
        }
        lastProjectNanos = System.nanoTime() - startNanos;
        WormholesTelemetry.addRenderNanos(lastProjectNanos);

        boolean shouldLog = projectCallCount <= 3L || (projectCallCount - lastDiagLogCall) >= 50L;
        if (shouldLog && (enterCount > 0 || exitCount > 0)) {
            Wormholes.v("[Projector] portal=" + portal.getName() + " observer=" + observer.getName()
                + " diff: enter=" + enterCount + " exit=" + exitCount + " kept=" + keptCount
                + " " + getDiagnostics() + " call#" + projectCallCount);
            lastDiagLogCall = projectCallCount;
        }

        Long2ObjectOpenHashMap<ProjectedBlockClaim> swap = projected;
        projected = nextProjected;
        nextProjected = swap;

        firstProjectionDone = true;
        rememberCamera(eye);
    }

    private void updateEntitiesOnly(long startNanos,
                                    ILocalPortal dest,
                                    boolean mirrorMode,
                                    Location eye) {
        if (dest == null || !firstProjectionDone || projected.isEmpty()) {
            lastProjectNanos = System.nanoTime() - startNanos;
            WormholesTelemetry.addRenderNanos(lastProjectNanos);
            return;
        }

        double portalDepth = portal.getNetworkViewDepth();
        double range = capProjectionDistance(observer, portalDepth);
        double depthBlocks = capProjectionDistance(observer, portalDepth);
        Frustum4D frustum;
        try {
            frustum = new Frustum4D(eye, portal.getStructure(), range);
        } catch (RuntimeException ex) {
            Wormholes.w("[Projector] failed to build entity frustum for portal " + portal.getName() + " observer " + observer.getName() + ": " + ex);
            ex.printStackTrace();
            return;
        }

        PortalFrame localFrame = portal.getFrame();
        PortalFrame remoteFrame = mirrorMode ? localFrame.flipNormal() : dest.getFrame();
        double localOriginX = portal.getOrigin().getX();
        double localOriginY = portal.getOrigin().getY();
        double localOriginZ = portal.getOrigin().getZ();
        double facingX = localFrame.getNormal().x();
        double facingY = localFrame.getNormal().y();
        double facingZ = localFrame.getNormal().z();
        double eyeRelX = eye.getX() - localOriginX;
        double eyeRelY = eye.getY() - localOriginY;
        double eyeRelZ = eye.getZ() - localOriginZ;
        boolean eyeFrontSide = (eyeRelX * facingX + eyeRelY * facingY + eyeRelZ * facingZ) >= 0.0D;
        PortalFrame projectionLocalFrame = viewFrame(localFrame, eyeFrontSide);
        PortalFrame projectionRemoteFrame = viewFrame(remoteFrame, eyeFrontSide);
        updateProjectedEntities(dest, frustum, depthBlocks, true, projectionLocalFrame, projectionRemoteFrame);
        lastProjectNanos = System.nanoTime() - startNanos;
        WormholesTelemetry.addRenderNanos(lastProjectNanos);
    }

    private ProjectedSample resolveProjectedSample(ProjectionWorldView view,
                                                   double sampleX,
                                                   double sampleY,
                                                   double sampleZ,
                                                   double eyeX,
                                                   double eyeY,
                                                   double eyeZ,
                                                   ILocalPortal excludedPortal,
                                                   int remainingDepth) {
        if (view == null) {
            return ProjectedSample.noSample();
        }

        World world = view.getWorld();
        RecursivePortalHit hit = remainingDepth < 0 || world == null ? null : findRecursivePortalHit(world, sampleX, sampleY, sampleZ, eyeX, eyeY, eyeZ, excludedPortal, remainingDepth);
        if (hit != null) {
            if (shouldMaskRecursivePortalAperture(hit.traversable, hit.cycle, remainingDepth)) {
                return ProjectedSample.maskAir(airBlockData);
            }
            ProjectedSample nested = resolveProjectedSample(liveView(hit.world),
                hit.pointX, hit.pointY, hit.pointZ,
                hit.eyeX, hit.eyeY, hit.eyeZ,
                hit.destinationPortal,
                remainingDepth - 1);
            if (nested.kind == ProjectedSampleKind.NO_SAMPLE) {
                return ProjectedSample.maskAir(airBlockData);
            }
            if (nested.kind != ProjectedSampleKind.BLOCK || !ProjectedBlockDataTransformer.requiresTransform(nested.data)) {
                return nested;
            }
            BlockData transformed = ProjectedBlockDataTransformer.transform(nested.data, hit.remoteFrame, hit.localFrame, scratchRot);
            return new ProjectedSample(ProjectedSampleKind.BLOCK, transformed, nested.lightView, nested.remoteKey);
        }

        int x = (int) Math.floor(sampleX);
        int y = (int) Math.floor(sampleY);
        int z = (int) Math.floor(sampleZ);
        ProjectedSample cached = cachedRemoteSample(view, x, y, z);
        if (cached != null) {
            return cached;
        }
        BlockData remoteData = view.sampleBlockData(x, y, z);
        if (remoteData == null) {
            ProjectedSample sample = ProjectedSample.noSample();
            cacheRemoteSample(view, x, y, z, sample);
            return sample;
        }
        lastRemoteSamples++;

        long remoteKey = packKey(x, y, z);
        ProjectedSample sample;
        if (isAir(remoteData.getMaterial())) {
            sample = new ProjectedSample(ProjectedSampleKind.REMOTE_AIR, airBlockData, view, remoteKey);
        } else {
            sample = new ProjectedSample(ProjectedSampleKind.BLOCK, remoteData, view, remoteKey);
        }
        cacheRemoteSample(view, x, y, z, sample);
        return sample;
    }

    private LiveWorldView liveView(World world) {
        LiveWorldView view = liveViews.get(world);
        if (view == null) {
            view = new LiveWorldView(world);
            liveViews.put(world, view);
        }
        return view;
    }

    private void maybeForceRemoteResend(RemoteWorldView remoteView) {
        long revision = remoteView.getRevision();
        if (revision != lastRemoteRevision) {
            lastRemoteRevision = revision;
            // A remote block changed: re-evaluate every projected cell next frame so cells that became
            // air drop out of nextProjected and the claim arbiter REVERTS them (restores the real local
            // block). Do NOT releaseSilently/clear here -- that forgets the already-sent fake blocks
            // without reverting, leaving a stale block (e.g. a broken block stuck in the projection).
            pendingRemoteResample = true;
        }
        boolean fullResend = false;
        if (remoteResendStage == 0 && projectCallCount >= 20L) {
            remoteResendStage = 1;
            fullResend = true;
        } else if (remoteResendStage == 1 && projectCallCount >= 60L) {
            remoteResendStage = 2;
            fullResend = true;
        }
        if (!fullResend) {
            return;
        }
        projected.clear();
        nextProjected.clear();
        hasCameraSnapshot = false;
        initialFullSendPassesRemaining = Math.max(initialFullSendPassesRemaining, Math.max(1, Settings.PROJECTION_INITIAL_RESEND_PASSES));
        if (observer != null && portal.getId() != null) {
            claimArbiter.releaseSilently(observer.getUniqueId(), portal.getId());
        }
    }

    private ProjectionWorldView remoteWorldView(String peerName, UUID portalId) {
        ViewSubscriptionManager subscriptions = Wormholes.viewSubscriptions;
        if (subscriptions == null || peerName == null || portalId == null) {
            return null;
        }
        RemoteViewCache.RemoteView view = subscriptions.touch(peerName, portalId, portal.getNetworkViewUnsubscribeGraceSeconds());
        String fallbackState = portal.getNetworkViewFallbackBlock();
        if (remoteFallback == null || !fallbackState.equals(remoteFallbackState)) {
            remoteFallback = parseRemoteFallback(fallbackState);
            remoteFallbackState = fallbackState;
        }
        return new RemoteWorldView(view, remoteFallback);
    }

    private static BlockData parseRemoteFallback(String fallbackState) {
        try {
            return Bukkit.createBlockData(fallbackState);
        } catch (IllegalArgumentException e) {
            return Material.AIR.createBlockData();
        }
    }

    private RecursivePortalHit findRecursivePortalHit(World world,
                                                      double pointX,
                                                      double pointY,
                                                      double pointZ,
                                                      double eyeX,
                                                      double eyeY,
                                                      double eyeZ,
                                                      ILocalPortal excludedPortal,
                                                      int remainingDepth) {
        RecursivePortalIndex index = recursivePortalIndex(world, eyeX, eyeY, eyeZ, excludedPortal);
        return index.find(pointX, pointY, pointZ, remainingDepth);
    }

    private RecursivePortalIndex recursivePortalIndex(World world,
                                                      double eyeX,
                                                      double eyeY,
                                                      double eyeZ,
                                                      ILocalPortal excludedPortal) {
        for (RecursivePortalIndex index : recursivePortalIndexes) {
            if (index.matches(world, eyeX, eyeY, eyeZ, excludedPortal)) {
                return index;
            }
        }
        if (recursivePortalIndexes.size() >= MAX_RECURSIVE_INDEXES_PER_PASS) {
            recursivePortalIndexes.clear();
        }
        RecursivePortalIndex created = new RecursivePortalIndex(world, eyeX, eyeY, eyeZ, excludedPortal);
        recursivePortalIndexes.add(created);
        return created;
    }

    private List<ILocalPortal> getRecursivePortalCandidates(World world) {
        List<ILocalPortal> cached = recursivePortalCandidates.get(world);
        if (cached != null) {
            return cached;
        }

        List<ILocalPortal> candidates = new ArrayList<ILocalPortal>();
        if (Wormholes.portalManager != null) {
            for (ILocalPortal candidate : Wormholes.portalManager.getLocalPortals()) {
                if (!isRecursivePortalCandidate(candidate, world)) {
                    continue;
                }
                candidates.add(candidate);
            }
        }
        recursivePortalCandidates.put(world, candidates);
        return candidates;
    }

    private static boolean isRecursivePortalCandidate(ILocalPortal candidate, World world) {
        if (candidate == null || world == null) {
            return false;
        }
        if (!candidate.supportsProjections() || !candidate.isProjecting() || !candidate.isOpen()) {
            return false;
        }
        World candidateWorld = candidate.getWorld();
        if (candidateWorld == null || !candidateWorld.equals(world)) {
            return false;
        }
        return true;
    }

    private static boolean isExcludedRecursivePortal(ILocalPortal candidate, ILocalPortal excludedPortal) {
        return candidate != null
            && excludedPortal != null
            && candidate.getId() != null
            && candidate.getId().equals(excludedPortal.getId());
    }

    private static double rayPlaneT(double eyeSignedDistance, double pointSignedDistance) {
        double denominator = pointSignedDistance - eyeSignedDistance;
        if (Math.abs(denominator) < 1.0E-7D) {
            return -1.0D;
        }
        double t = -eyeSignedDistance / denominator;
        return t > 1.0E-7D && t < 1.0D ? t : -1.0D;
    }

    private ProjectedSample cachedRemoteSample(ProjectionWorldView view, int x, int y, int z) {
        Long2ObjectOpenHashMap<ProjectedSample> viewSamples = remoteSampleCache.get(view);
        if (viewSamples == null) {
            return null;
        }
        return viewSamples.get(packKey(x, y, z));
    }

    private void cacheRemoteSample(ProjectionWorldView view, int x, int y, int z, ProjectedSample sample) {
        Long2ObjectOpenHashMap<ProjectedSample> viewSamples = remoteSampleCache.get(view);
        if (viewSamples == null) {
            viewSamples = new Long2ObjectOpenHashMap<ProjectedSample>(256);
            remoteSampleCache.put(view, viewSamples);
        }
        viewSamples.put(packKey(x, y, z), sample);
    }

    private static boolean isAir(Material material) {
        return ProjectionWorldView.isAir(material);
    }

    private static boolean isLocalAir(World world, int x, int y, int z) {
        Block localBlock = world.getBlockAt(x, y, z);
        return isAir(localBlock.getType());
    }

    static boolean shouldMaskRecursivePortalAperture(boolean traversable, boolean cycle, int remainingDepth) {
        return !traversable || cycle || remainingDepth <= 0;
    }

    static boolean shouldProjectAirSample(ProjectedSampleKind kind, boolean localAir) {
        return kind == ProjectedSampleKind.MASK_AIR || (kind == ProjectedSampleKind.REMOTE_AIR && !localAir);
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
        if (claimArbiter.hasPendingLighting(observer) && isLightingUpdatePass()) {
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

        if (observer == null || !observer.isOnline()) {
            if (observer != null && portal.getId() != null) {
                claimArbiter.releaseSilently(observer.getUniqueId(), portal.getId());
            }
            projected.clear();
            entityRenderer.close(observer);
            return;
        }

        World world = observer.getWorld();
        if (world == null) {
            if (portal.getId() != null) {
                claimArbiter.releaseSilently(observer.getUniqueId(), portal.getId());
            }
            projected.clear();
            entityRenderer.close(observer);
            return;
        }

        ProjectionClaimArbiter.ClaimUpdateResult result = claimArbiter.release(observer, portal, world, true);
        if (result.getBlockChanges() > 0) {
            Wormholes.v("[Projector] portal=" + portal.getName() + " observer=" + observer.getName()
                + " close: reverted=" + result.getBlockChanges());
        }
        entityRenderer.close(observer);

        projected.clear();
    }

    private static double capProjectionDistance(Player observer, double requestedBlocks) {
        if (!Settings.PROJECTION_CLIENT_VIEW_DISTANCE_CAP || observer == null) {
            return requestedBlocks;
        }
        int serverChunks = Wormholes.instance == null ? 8 : Wormholes.instance.getServer().getViewDistance();
        int clientChunks = clientViewDistance(observer);
        if (clientChunks <= 0) {
            clientChunks = serverChunks;
        }
        int chunks = Math.max(2, Math.min(serverChunks, clientChunks));
        double cap = chunks * 16.0D;
        return Math.max(1.0D, Math.min(requestedBlocks, cap));
    }

    private static final java.lang.reflect.Method CLIENT_VIEW_DISTANCE_METHOD = resolveClientViewDistanceMethod();

    private static java.lang.reflect.Method resolveClientViewDistanceMethod() {
        try {
            return Player.class.getMethod("getClientViewDistance");
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static int clientViewDistance(Player observer) {
        if (CLIENT_VIEW_DISTANCE_METHOD == null || observer == null) {
            return 0;
        }
        try {
            Object result = CLIENT_VIEW_DISTANCE_METHOD.invoke(observer);
            if (result instanceof Integer) {
                return (Integer) result;
            }
        } catch (Throwable ignored) {
        }
        return 0;
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

    enum ProjectedSampleKind {
        BLOCK,
        REMOTE_AIR,
        MASK_AIR,
        NO_SAMPLE
    }

    private static final class ProjectedSample {
        private final ProjectedSampleKind kind;
        private final BlockData data;
        private final ProjectionWorldView lightView;
        private final long remoteKey;

        private ProjectedSample(ProjectedSampleKind kind, BlockData data, ProjectionWorldView lightView, long remoteKey) {
            this.kind = kind;
            this.data = data;
            this.lightView = lightView;
            this.remoteKey = remoteKey;
        }

        private static ProjectedSample noSample() {
            return new ProjectedSample(ProjectedSampleKind.NO_SAMPLE, null, null, ProjectedBlockClaim.NO_REMOTE_KEY);
        }

        private static ProjectedSample maskAir(BlockData airBlockData) {
            return new ProjectedSample(ProjectedSampleKind.MASK_AIR, airBlockData, null, ProjectedBlockClaim.NO_REMOTE_KEY);
        }

        private ProjectedBlockClaim asClaim(BlockData projectedData) {
            if (kind == ProjectedSampleKind.MASK_AIR) {
                return new ProjectedBlockClaim(projectedData, null, ProjectedBlockClaim.NO_REMOTE_KEY, true);
            }
            return new ProjectedBlockClaim(projectedData, lightView, remoteKey, false);
        }
    }

    private final class RecursivePortalIndex {
        private final World world;
        private final UUID excludedPortalId;
        private final double eyeX;
        private final double eyeY;
        private final double eyeZ;
        private final Long2ObjectOpenHashMap<ArrayList<RecursivePortalCandidate>> buckets;

        private RecursivePortalIndex(World world, double eyeX, double eyeY, double eyeZ, ILocalPortal excludedPortal) {
            this.world = world;
            this.excludedPortalId = excludedPortal == null ? null : excludedPortal.getId();
            this.eyeX = eyeX;
            this.eyeY = eyeY;
            this.eyeZ = eyeZ;
            this.buckets = new Long2ObjectOpenHashMap<ArrayList<RecursivePortalCandidate>>();
            for (ILocalPortal candidate : getRecursivePortalCandidates(world)) {
                if (isExcludedRecursivePortal(candidate, excludedPortal)) {
                    continue;
                }
                RecursivePortalCandidate indexed = new RecursivePortalCandidate(candidate, eyeX, eyeY, eyeZ);
                if (indexed.valid) {
                    index(indexed);
                }
            }
        }

        private boolean matches(World world, double eyeX, double eyeY, double eyeZ, ILocalPortal excludedPortal) {
            UUID candidateExcludedId = excludedPortal == null ? null : excludedPortal.getId();
            if (this.world == null ? world != null : !this.world.equals(world)) {
                return false;
            }
            if (excludedPortalId == null ? candidateExcludedId != null : !excludedPortalId.equals(candidateExcludedId)) {
                return false;
            }
            return Double.compare(this.eyeX, eyeX) == 0
                && Double.compare(this.eyeY, eyeY) == 0
                && Double.compare(this.eyeZ, eyeZ) == 0;
        }

        private RecursivePortalHit find(double pointX, double pointY, double pointZ, int remainingDepth) {
            int bucketX = bucket(pointX);
            int bucketY = bucket(pointY);
            int bucketZ = bucket(pointZ);
            ArrayList<RecursivePortalCandidate> bucketCandidates = buckets.get(packKey(bucketX, bucketY, bucketZ));
            if (bucketCandidates == null) {
                return null;
            }
            RecursivePortalHit best = null;
            for (RecursivePortalCandidate candidate : bucketCandidates) {
                RecursivePortalHit hit = candidate.hit(pointX, pointY, pointZ, remainingDepth);
                if (hit == null) {
                    continue;
                }
                if (best == null || hit.rayT < best.rayT) {
                    best = hit;
                }
            }
            return best;
        }

        private void index(RecursivePortalCandidate candidate) {
            int minX = bucket(candidate.view.getXa());
            int maxX = bucket(candidate.view.getXb());
            int minY = bucket(candidate.view.getYa());
            int maxY = bucket(candidate.view.getYb());
            int minZ = bucket(candidate.view.getZa());
            int maxZ = bucket(candidate.view.getZb());
            for (int x = minX; x <= maxX; x++) {
                for (int y = minY; y <= maxY; y++) {
                    for (int z = minZ; z <= maxZ; z++) {
                        long key = packKey(x, y, z);
                        ArrayList<RecursivePortalCandidate> bucketCandidates = buckets.get(key);
                        if (bucketCandidates == null) {
                            bucketCandidates = new ArrayList<RecursivePortalCandidate>(2);
                            buckets.put(key, bucketCandidates);
                        }
                        bucketCandidates.add(candidate);
                    }
                }
            }
        }

        private int bucket(double coordinate) {
            return ((int) Math.floor(coordinate)) >> RECURSIVE_BUCKET_SHIFT;
        }
    }

    private final class RecursivePortalCandidate {
        private final ILocalPortal candidate;
        private final AxisAlignedBB view;
        private final PortalFrame localFrame;
        private final PortalFrame remoteFrame;
        private final World nestedWorld;
        private final ILocalPortal nestedDestination;
        private final PortalPlaneWindow planeWindow;
        private final double originX;
        private final double originY;
        private final double originZ;
        private final double remoteOriginX;
        private final double remoteOriginY;
        private final double remoteOriginZ;
        private final double normalX;
        private final double normalY;
        private final double normalZ;
        private final double projectionNormalX;
        private final double projectionNormalY;
        private final double projectionNormalZ;
        private final double transformXX;
        private final double transformXY;
        private final double transformXZ;
        private final double transformYX;
        private final double transformYY;
        private final double transformYZ;
        private final double transformZX;
        private final double transformZY;
        private final double transformZZ;
        private final double transformedEyeX;
        private final double transformedEyeY;
        private final double transformedEyeZ;
        private final double eyeX;
        private final double eyeY;
        private final double eyeZ;
        private final double eyeSignedDistance;
        private final double clearance;
        private final double maxDepth;
        private final boolean eyeFrontSide;
        private final boolean traversable;
        private final boolean valid;

        private RecursivePortalCandidate(ILocalPortal candidate, double eyeX, double eyeY, double eyeZ) {
            this.candidate = candidate;
            this.eyeX = eyeX;
            this.eyeY = eyeY;
            this.eyeZ = eyeZ;
            if (candidate == null || candidate.getOrigin() == null || candidate.getFrame() == null || candidate.getStructure() == null) {
                this.view = null;
                this.localFrame = null;
                this.remoteFrame = null;
                this.nestedWorld = null;
                this.nestedDestination = null;
                this.planeWindow = null;
                this.originX = 0.0D;
                this.originY = 0.0D;
                this.originZ = 0.0D;
                this.remoteOriginX = 0.0D;
                this.remoteOriginY = 0.0D;
                this.remoteOriginZ = 0.0D;
                this.normalX = 0.0D;
                this.normalY = 0.0D;
                this.normalZ = 0.0D;
                this.projectionNormalX = 0.0D;
                this.projectionNormalY = 0.0D;
                this.projectionNormalZ = 0.0D;
                this.transformXX = 0.0D;
                this.transformXY = 0.0D;
                this.transformXZ = 0.0D;
                this.transformYX = 0.0D;
                this.transformYY = 0.0D;
                this.transformYZ = 0.0D;
                this.transformZX = 0.0D;
                this.transformZY = 0.0D;
                this.transformZZ = 0.0D;
                this.transformedEyeX = 0.0D;
                this.transformedEyeY = 0.0D;
                this.transformedEyeZ = 0.0D;
                this.eyeSignedDistance = 0.0D;
                this.clearance = 0.0D;
                this.maxDepth = 0.0D;
                this.eyeFrontSide = false;
                this.traversable = false;
                this.valid = false;
                return;
            }

            AxisAlignedBB candidateView = candidate.getView();
            PortalFrame frame = candidate.getFrame();
            double candidateOriginX = candidate.getOrigin().getX();
            double candidateOriginY = candidate.getOrigin().getY();
            double candidateOriginZ = candidate.getOrigin().getZ();
            double frameNormalX = frame.getNormal().x();
            double frameNormalY = frame.getNormal().y();
            double frameNormalZ = frame.getNormal().z();
            double eyeRelX = eyeX - candidateOriginX;
            double eyeRelY = eyeY - candidateOriginY;
            double eyeRelZ = eyeZ - candidateOriginZ;
            boolean frontSide = ((eyeRelX * frameNormalX) + (eyeRelY * frameNormalY) + (eyeRelZ * frameNormalZ)) >= 0.0D;
            PortalFrame candidateLocalFrame = viewFrame(frame, frontSide);
            double localProjectionNormalX = candidateLocalFrame.getNormal().x();
            double localProjectionNormalY = candidateLocalFrame.getNormal().y();
            double localProjectionNormalZ = candidateLocalFrame.getNormal().z();
            double signedEyeDistance = (eyeRelX * localProjectionNormalX) + (eyeRelY * localProjectionNormalY) + (eyeRelZ * localProjectionNormalZ);
            double candidateClearance = portalPlaneClearance(candidate.getStructure().getArea(), frame);

            ILocalPortal destination;
            World destinationWorld;
            PortalFrame destinationFrame;
            double destinationOriginX;
            double destinationOriginY;
            double destinationOriginZ;
            boolean canTraverse;
            if (candidate.getProjectionMode() == ProjectionMode.MIRROR) {
                destination = candidate;
                destinationWorld = candidate.getWorld();
                destinationFrame = viewFrame(frame.flipNormal(), frontSide);
                destinationOriginX = candidateOriginX;
                destinationOriginY = candidateOriginY;
                destinationOriginZ = candidateOriginZ;
                canTraverse = destinationWorld != null;
            } else if (candidate.getTunnel() != null && candidate.getTunnel().getDestination() instanceof ILocalPortal linkedDestination) {
                destination = linkedDestination;
                destinationWorld = linkedDestination.getWorld();
                destinationFrame = linkedDestination.getFrame() == null ? null : viewFrame(linkedDestination.getFrame(), frontSide);
                destinationOriginX = linkedDestination.getOrigin() == null ? 0.0D : linkedDestination.getOrigin().getX();
                destinationOriginY = linkedDestination.getOrigin() == null ? 0.0D : linkedDestination.getOrigin().getY();
                destinationOriginZ = linkedDestination.getOrigin() == null ? 0.0D : linkedDestination.getOrigin().getZ();
                canTraverse = destinationWorld != null && destinationFrame != null && linkedDestination.getOrigin() != null;
            } else {
                destination = null;
                destinationWorld = null;
                destinationFrame = null;
                destinationOriginX = 0.0D;
                destinationOriginY = 0.0D;
                destinationOriginZ = 0.0D;
                canTraverse = false;
            }

            double matrixXX = 0.0D;
            double matrixXY = 0.0D;
            double matrixXZ = 0.0D;
            double matrixYX = 0.0D;
            double matrixYY = 0.0D;
            double matrixYZ = 0.0D;
            double matrixZX = 0.0D;
            double matrixZY = 0.0D;
            double matrixZZ = 0.0D;
            double nestedEyeX = 0.0D;
            double nestedEyeY = 0.0D;
            double nestedEyeZ = 0.0D;
            if (canTraverse) {
                int fromRightX = candidateLocalFrame.getRight().x();
                int fromRightY = candidateLocalFrame.getRight().y();
                int fromRightZ = candidateLocalFrame.getRight().z();
                int fromUpX = candidateLocalFrame.getUp().x();
                int fromUpY = candidateLocalFrame.getUp().y();
                int fromUpZ = candidateLocalFrame.getUp().z();
                int fromNormalX = candidateLocalFrame.getNormal().x();
                int fromNormalY = candidateLocalFrame.getNormal().y();
                int fromNormalZ = candidateLocalFrame.getNormal().z();
                int toRightX = destinationFrame.getRight().x();
                int toRightY = destinationFrame.getRight().y();
                int toRightZ = destinationFrame.getRight().z();
                int toUpX = destinationFrame.getUp().x();
                int toUpY = destinationFrame.getUp().y();
                int toUpZ = destinationFrame.getUp().z();
                int toNormalX = destinationFrame.getNormal().x();
                int toNormalY = destinationFrame.getNormal().y();
                int toNormalZ = destinationFrame.getNormal().z();

                matrixXX = (fromRightX * toRightX) + (fromUpX * toUpX) + (fromNormalX * toNormalX);
                matrixXY = (fromRightY * toRightX) + (fromUpY * toUpX) + (fromNormalY * toNormalX);
                matrixXZ = (fromRightZ * toRightX) + (fromUpZ * toUpX) + (fromNormalZ * toNormalX);
                matrixYX = (fromRightX * toRightY) + (fromUpX * toUpY) + (fromNormalX * toNormalY);
                matrixYY = (fromRightY * toRightY) + (fromUpY * toUpY) + (fromNormalY * toNormalY);
                matrixYZ = (fromRightZ * toRightY) + (fromUpZ * toUpY) + (fromNormalZ * toNormalY);
                matrixZX = (fromRightX * toRightZ) + (fromUpX * toUpZ) + (fromNormalX * toNormalZ);
                matrixZY = (fromRightY * toRightZ) + (fromUpY * toUpZ) + (fromNormalY * toNormalZ);
                matrixZZ = (fromRightZ * toRightZ) + (fromUpZ * toUpZ) + (fromNormalZ * toNormalZ);
                nestedEyeX = destinationOriginX + (eyeRelX * matrixXX) + (eyeRelY * matrixXY) + (eyeRelZ * matrixXZ);
                nestedEyeY = destinationOriginY + (eyeRelX * matrixYX) + (eyeRelY * matrixYY) + (eyeRelZ * matrixYZ);
                nestedEyeZ = destinationOriginZ + (eyeRelX * matrixZX) + (eyeRelY * matrixZY) + (eyeRelZ * matrixZZ);
            }

            this.view = candidateView;
            this.localFrame = candidateLocalFrame;
            this.remoteFrame = destinationFrame;
            this.nestedWorld = destinationWorld;
            this.nestedDestination = destination;
            this.planeWindow = candidateView == null ? null : PortalPlaneWindow.create(candidate.getStructure().getArea(), candidateLocalFrame,
                candidateOriginX, candidateOriginY, candidateOriginZ, Settings.PROJECTION_APERTURE_PADDING_BLOCKS + PLANE_WINDOW_EXTRA_PADDING,
                signedEyeDistance);
            this.originX = candidateOriginX;
            this.originY = candidateOriginY;
            this.originZ = candidateOriginZ;
            this.remoteOriginX = destinationOriginX;
            this.remoteOriginY = destinationOriginY;
            this.remoteOriginZ = destinationOriginZ;
            this.normalX = frameNormalX;
            this.normalY = frameNormalY;
            this.normalZ = frameNormalZ;
            this.projectionNormalX = localProjectionNormalX;
            this.projectionNormalY = localProjectionNormalY;
            this.projectionNormalZ = localProjectionNormalZ;
            this.transformXX = matrixXX;
            this.transformXY = matrixXY;
            this.transformXZ = matrixXZ;
            this.transformYX = matrixYX;
            this.transformYY = matrixYY;
            this.transformYZ = matrixYZ;
            this.transformZX = matrixZX;
            this.transformZY = matrixZY;
            this.transformZZ = matrixZZ;
            this.transformedEyeX = nestedEyeX;
            this.transformedEyeY = nestedEyeY;
            this.transformedEyeZ = nestedEyeZ;
            this.eyeSignedDistance = signedEyeDistance;
            this.clearance = candidateClearance;
            this.maxDepth = Settings.PROJECTION_DEPTH_BLOCKS + candidateClearance;
            this.eyeFrontSide = frontSide;
            this.traversable = canTraverse;
            this.valid = candidateView != null && planeWindow != null;
        }

        private RecursivePortalHit hit(double pointX, double pointY, double pointZ, int remainingDepth) {
            if (!valid || !view.containsPrimitive(pointX, pointY, pointZ)) {
                return null;
            }

            double pointRelX = pointX - originX;
            double pointRelY = pointY - originY;
            double pointRelZ = pointZ - originZ;
            double pointDot = (pointRelX * normalX) + (pointRelY * normalY) + (pointRelZ * normalZ);
            if (!projectsBehindPortalPlane(pointDot, eyeFrontSide, clearance)) {
                return null;
            }
            if (Math.abs(pointDot) > maxDepth) {
                return null;
            }

            double pointSignedDistance = (pointRelX * projectionNormalX) + (pointRelY * projectionNormalY) + (pointRelZ * projectionNormalZ);
            double rayT = rayPlaneT(eyeSignedDistance, pointSignedDistance);
            if (rayT <= 0.0D) {
                return null;
            }
            if (!planeWindow.containsRayIntersection(eyeX, eyeY, eyeZ, pointX, pointY, pointZ, pointSignedDistance)) {
                return null;
            }
            if (!traversable || remainingDepth <= 0) {
                return RecursivePortalHit.mask(rayT, false);
            }

            double nextPointX = remoteOriginX + (pointRelX * transformXX) + (pointRelY * transformXY) + (pointRelZ * transformXZ);
            double nextPointY = remoteOriginY + (pointRelX * transformYX) + (pointRelY * transformYY) + (pointRelZ * transformYZ);
            double nextPointZ = remoteOriginZ + (pointRelX * transformZX) + (pointRelY * transformZY) + (pointRelZ * transformZZ);
            return new RecursivePortalHit(nestedWorld, nestedDestination, localFrame, remoteFrame,
                nextPointX, nextPointY, nextPointZ,
                transformedEyeX, transformedEyeY, transformedEyeZ,
                rayT, true, false);
        }
    }

    private static final class RecursivePortalHit {
        private final World world;
        private final ILocalPortal destinationPortal;
        private final PortalFrame localFrame;
        private final PortalFrame remoteFrame;
        private final double pointX;
        private final double pointY;
        private final double pointZ;
        private final double eyeX;
        private final double eyeY;
        private final double eyeZ;
        private final double rayT;
        private final boolean traversable;
        private final boolean cycle;

        private RecursivePortalHit(World world,
                                   ILocalPortal destinationPortal,
                                   PortalFrame localFrame,
                                   PortalFrame remoteFrame,
                                   double pointX,
                                   double pointY,
                                   double pointZ,
                                   double eyeX,
                                   double eyeY,
                                   double eyeZ,
                                   double rayT,
                                   boolean traversable,
                                   boolean cycle) {
            this.world = world;
            this.destinationPortal = destinationPortal;
            this.localFrame = localFrame;
            this.remoteFrame = remoteFrame;
            this.pointX = pointX;
            this.pointY = pointY;
            this.pointZ = pointZ;
            this.eyeX = eyeX;
            this.eyeY = eyeY;
            this.eyeZ = eyeZ;
            this.rayT = rayT;
            this.traversable = traversable;
            this.cycle = cycle;
        }

        private static RecursivePortalHit mask(double rayT, boolean cycle) {
            return new RecursivePortalHit(null, null, null, null,
                0.0D, 0.0D, 0.0D,
                0.0D, 0.0D, 0.0D,
                rayT, false, cycle);
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
