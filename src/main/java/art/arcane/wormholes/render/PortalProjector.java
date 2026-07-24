package art.arcane.wormholes.render;

import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.BooleanSupplier;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Player;

import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityAnimation.EntityAnimationType;

import art.arcane.wormholes.Settings;
import art.arcane.wormholes.Wormholes;
import art.arcane.wormholes.service.WormholesTelemetry;
import art.arcane.wormholes.network.view.RemoteViewCache;
import art.arcane.wormholes.network.view.ViewSubscriptionManager;
import art.arcane.wormholes.portal.BlackoutColor;
import art.arcane.wormholes.portal.ILocalPortal;
import art.arcane.wormholes.portal.IPortal;
import art.arcane.wormholes.portal.NetworkViewQuality;
import art.arcane.wormholes.portal.PortalFrame;
import art.arcane.wormholes.portal.PortalStructure;
import art.arcane.wormholes.portal.RemotePortal;
import art.arcane.wormholes.portal.UniversalTunnel;
import art.arcane.wormholes.portal.rtp.RtpProjectionView;
import art.arcane.wormholes.render.view.ProjectionEntityView;
import art.arcane.wormholes.render.view.ProjectionWorldView;
import art.arcane.wormholes.render.view.ProjectionWorldViewProvider;
import art.arcane.wormholes.render.view.RemoteWorldView;
import art.arcane.wormholes.util.AxisAlignedBB;
import art.arcane.wormholes.util.Direction;

public final class PortalProjector {
    private static final long NO_REMOTE_KEY = Long.MIN_VALUE;
    private static final int RECURSIVE_BUCKET_SHIFT = 3;
    private static final int MAX_RECURSIVE_INDEXES_PER_PASS = 256;
    private static final int STABLE_RESAMPLE_BACKSTOP_TICKS = 40;
    private static final double BLACKOUT_PERPENDICULAR_BAND_MARGIN = 1.0D;

    private final ILocalPortal portal;
    private final Player observer;
    private final UUID observerId;
    private final World localWorld;
    private final UUID localWorldId;
    private final ProjectionClaimArbiter claimArbiter;
    private final ProjectionWorldViewProvider viewProvider;
    private final BooleanSupplier activeGuard;
    private Long2ObjectOpenHashMap<ProjectedBlockClaim> projected;
    private Long2ObjectOpenHashMap<ProjectedBlockClaim> nextProjected;
    private final HashMap<BlockData, BlockData> transformedBlockCache;
    private final HashMap<World, List<ILocalPortal>> recursivePortalCandidates;
    private final HashMap<ProjectionWorldView, Long2ObjectOpenHashMap<ProjectedSample>> remoteSampleCache;
    private final HashMap<World, ProjectionWorldView> liveViews;
    private final ArrayList<RecursivePortalIndex> recursivePortalIndexes;
    private final BlockData airBlockData;
    private final ProjectedSample maskAirSample;
    private final double[] scratchRot = new double[3];
    private final double[] scratchRemotePoint = new double[3];
    private final double[] scratchRemoteEye = new double[3];
    private final int[] scratchAxisMin = new int[3];
    private final int[] scratchAxisMax = new int[3];
    private final double[] scratchAxisOrigin = new double[3];
    private final double[] scratchSlabWindowBounds = new double[4];
    private final int[] scratchCellCoords = new int[3];
    private final HoistedFrameTransform cellTransform = new HoistedFrameTransform();

    private final ProjectedEntityRenderer entityRenderer = new ProjectedEntityRenderer();

    private boolean firstProjectionDone;
    private volatile boolean closed;
    private volatile boolean discardRequested;
    private long projectCallCount;
    private long entityPassCount;
    private long lastDiagLogCall;
    private long lastProjectNanos;
    private int lastPlaneRejected;
    private int lastWindowRejected;
    private int lastFrustumRejected;
    private int lastRemoteSamples;
    private int lastBlockChanges;
    private volatile int lastRenderedCells;
    private int lastReuseSkips;
    private int lastClaimConflicts;
    private int lastWinnerChanges;
    private int lastClaimReverts;
    private int lastMaskedCells;
    private int initialFullSendPassesRemaining;
    private double lastEyeX;
    private double lastEyeY;
    private double lastEyeZ;
    private boolean hasCameraSnapshot;
    private Direction cachedFromNormal;
    private Direction cachedFromRight;
    private Direction cachedFromUp;
    private Direction cachedToNormal;
    private Direction cachedToRight;
    private Direction cachedToUp;
    private boolean cachedMirrorTransform;
    private int cachedMirrorRotationQuarterTurns;
    private BlockData remoteFallback;
    private String remoteFallbackState;
    private BlockData blackoutData;
    private BlackoutColor blackoutColorCache;
    private RemoteWorldView cachedRemoteWorldView;
    private RemoteViewCache.RemoteView cachedRemoteViewSource;
    private long lastRemoteRevision = -1L;
    private boolean pendingRemoteResample;
    private int remoteResendStage;
    private long lastResampleVersion = -1L;
    private long lastSourceViewRevision = -1L;
    private Frustum4D cachedFrustum;
    private PortalStructure cachedFrustumStructure;
    private long cachedFrustumStructureRevision = Long.MIN_VALUE;
    private double cachedFrustumEyeX;
    private double cachedFrustumEyeY;
    private double cachedFrustumEyeZ;
    private double cachedFrustumRange;
    private double cachedFrustumNearPlanePadding;
    private double cachedFrustumCullingRatio;
    private double cachedFrustumAperturePadding;
    private RtpProjectionTarget rtpProjectionTarget;

    public PortalProjector(ILocalPortal portal, Player observer, ProjectionClaimArbiter claimArbiter,
                           ProjectionWorldViewProvider viewProvider, BooleanSupplier activeGuard) {
        this.portal = portal;
        this.observer = observer;
        this.observerId = observer.getUniqueId();
        this.localWorld = portal.getWorld();
        this.localWorldId = localWorld == null ? null : localWorld.getUID();
        this.claimArbiter = claimArbiter;
        this.viewProvider = viewProvider;
        this.activeGuard = activeGuard;
        this.projected = new Long2ObjectOpenHashMap<ProjectedBlockClaim>(256);
        this.nextProjected = new Long2ObjectOpenHashMap<ProjectedBlockClaim>(256);
        this.transformedBlockCache = new HashMap<BlockData, BlockData>(128);
        this.recursivePortalCandidates = new HashMap<World, List<ILocalPortal>>(4);
        this.remoteSampleCache = new HashMap<ProjectionWorldView, Long2ObjectOpenHashMap<ProjectedSample>>(4);
        this.liveViews = new HashMap<World, ProjectionWorldView>(4);
        this.recursivePortalIndexes = new ArrayList<RecursivePortalIndex>(4);
        this.airBlockData = Material.AIR.createBlockData();
        this.maskAirSample = ProjectedSample.maskAir(airBlockData);
        this.firstProjectionDone = false;
        this.closed = false;
        this.discardRequested = false;
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
        this.hasCameraSnapshot = false;
        this.cachedFromNormal = null;
        this.cachedFromRight = null;
        this.cachedFromUp = null;
        this.cachedToNormal = null;
        this.cachedToRight = null;
        this.cachedToUp = null;
        this.cachedMirrorTransform = false;
        this.cachedMirrorRotationQuarterTurns = 0;
    }

    public ILocalPortal getPortal() {
        return portal;
    }

    public Player getObserver() {
        return observer;
    }

    public void setRtpProjectionTarget(RtpProjectionTarget target) {
        RtpProjectionTarget previous = rtpProjectionTarget;
        rtpProjectionTarget = target;
        if (target == null) {
            if (previous != null) {
                invalidateRtpDestinationState();
            }
            return;
        }
        if (target.requiresDestinationInvalidation(previous)) {
            invalidateRtpDestinationState();
        }
    }

    public boolean isClosed() {
        return closed;
    }

    public int getProjectedCount() {
        return lastRenderedCells;
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
        if (!activeGuard.getAsBoolean()) {
            close();
            return;
        }
        if (discardRequested) {
            discard();
            return;
        }
        if (closed) {
            return;
        }
        if (updateEntities) {
            updateEntities = entityUpdateDue();
            entityPassCount++;
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

        RtpProjectionTarget rtpTarget = rtpProjectionTarget;
        boolean rtpMode = rtpTarget != null;
        boolean mirrorMode = !rtpMode && portal.isMirrorMode();
        int mirrorRotationQuarterTurns = mirrorMode ? portal.getMirrorRotation().getQuarterTurns() : 0;
        if (!rtpMode && !mirrorMode && !portal.hasTunnel()) {
            Wormholes.v("[Projector] portal " + portal.getName() + " no longer linked, closing projector");
            close();
            return;
        }

        ILocalPortal dest;
        IPortal destAnchor;
        ProjectionWorldView remoteView = null;
        World localWorld = portal.getWorld();
        World destWorld;
        if (rtpMode) {
            dest = null;
            destAnchor = null;
            destWorld = rtpTarget.world();
        } else if (mirrorMode) {
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
        ProjectionWorldView localView = liveView(localWorld);
        if (destView == null || localView == null) {
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

        double destinationOriginX = rtpMode ? rtpTarget.originX() : destAnchor.getOrigin().getX();
        double destinationOriginY = rtpMode ? rtpTarget.originY() : destAnchor.getOrigin().getY();
        double destinationOriginZ = rtpMode ? rtpTarget.originZ() : destAnchor.getOrigin().getZ();
        int localOriginBlockX = (int) Math.floor(portal.getOrigin().getX());
        int localOriginBlockZ = (int) Math.floor(portal.getOrigin().getZ());
        if (!localView.isChunkReady(localOriginBlockX, localOriginBlockZ)) {
            localView.requestChunk(localOriginBlockX, localOriginBlockZ);
            return;
        }
        if (destView.getWorld() != null) {
            int remoteOriginBlockX = (int) Math.floor(destinationOriginX);
            int remoteOriginBlockZ = (int) Math.floor(destinationOriginZ);
            if (!destView.isChunkReady(remoteOriginBlockX, remoteOriginBlockZ)) {
                destView.requestChunk(remoteOriginBlockX, remoteOriginBlockZ);
                return;
            }
        }

        Location eye = observer.getEyeLocation();
        if (!updateBlocks) {
            updateEntitiesOnly(startNanos, dest, destAnchor, destView, mirrorMode, mirrorRotationQuarterTurns, eye);
            return;
        }

        projectCallCount++;
        if (remoteView instanceof RemoteWorldView remoteResendView) {
            maybeForceRemoteResend(remoteResendView);
        }
        boolean stableResample = computeStableResample(destWorld, destinationOriginX, destinationOriginZ, destView);
        if (canReuseProjection(eye, stableResample, remoteView == null)) {
            lastReuseSkips++;
            lastBlockChanges = 0;
            lastProjectNanos = System.nanoTime() - startNanos;
            WormholesTelemetry.addRenderNanos(lastProjectNanos);
            if (updateEntities) {
                updateEntitiesOnly(startNanos, dest, destAnchor, destView, mirrorMode, mirrorRotationQuarterTurns, eye);
            }
            return;
        }

        double portalDepth = portal.getNetworkViewDepth();
        double range = capProjectionDistance(observer, portalDepth);
        double depthBlocks = range;
        boolean blackoutEnabled = portal.isBlackoutBackground();
        if (blackoutEnabled) {
            BlackoutColor blackoutColor = portal.getBlackoutColor();
            if (blackoutData == null || blackoutColor != blackoutColorCache) {
                blackoutData = parseBlackout(blackoutColor);
                blackoutColorCache = blackoutColor;
            }
        }
        Frustum4D next;
        try {
            next = frustumFor(eye, portal.getStructure(), range);
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
        for (Long2ObjectOpenHashMap<ProjectedSample> viewSamples : remoteSampleCache.values()) {
            viewSamples.clear();
        }
        recursivePortalIndexes.clear();

        int enterCount = 0;
        int exitCount = 0;
        int keptCount = 0;

        int localMinY = localView.getMinHeight();
        int localMaxY = localView.getMaxHeight() - 1;
        AxisAlignedBB area = next.getRegion();
        int xa = (int) Math.floor(area.getXa());
        int ya = Math.max((int) Math.floor(area.getYa()), localMinY);
        int za = (int) Math.floor(area.getZa());
        int xb = (int) Math.floor(area.getXb());
        int yb = Math.min((int) Math.floor(area.getYb()), localMaxY);
        int zb = (int) Math.floor(area.getZb());

        PortalFrame localFrame = portal.getFrame();
        PortalFrame remoteFrame = rtpMode ? rtpTarget.frame() : mirrorMode ? localFrame.flipNormal() : destAnchor.getFrame();
        double localOriginX = portal.getOrigin().getX();
        double localOriginY = portal.getOrigin().getY();
        double localOriginZ = portal.getOrigin().getZ();
        double remoteOriginX = mirrorMode ? localOriginX : destinationOriginX;
        double remoteOriginY = mirrorMode ? localOriginY : destinationOriginY;
        double remoteOriginZ = mirrorMode ? localOriginZ : destinationOriginZ;

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
        if (mirrorMode) {
            PortalCoordMap.mirrorDisplayToSourcePointInto(eyeX, eyeY, eyeZ,
                localOriginX, localOriginY, localOriginZ, localFrame, mirrorRotationQuarterTurns, scratchRemoteEye);
            cellTransform.configureMirror(localFrame, mirrorRotationQuarterTurns,
                localOriginX, localOriginY, localOriginZ, scratchRot);
        } else {
            projectionLocalFrame.transformPointInto(eyeX, eyeY, eyeZ,
                localOriginX, localOriginY, localOriginZ,
                remoteOriginX, remoteOriginY, remoteOriginZ,
                projectionRemoteFrame, scratchRemoteEye);
            cellTransform.configure(projectionLocalFrame, projectionRemoteFrame,
                localOriginX, localOriginY, localOriginZ,
                remoteOriginX, remoteOriginY, remoteOriginZ);
        }
        prepareTransformCache(projectionRemoteFrame, projectionLocalFrame, mirrorMode, mirrorRotationQuarterTurns);
        double projectionFacingX = projectionLocalFrame.getNormal().x();
        double projectionFacingY = projectionLocalFrame.getNormal().y();
        double projectionFacingZ = projectionLocalFrame.getNormal().z();
        double projectionEyeDot = (eyeRelX * projectionFacingX) + (eyeRelY * projectionFacingY) + (eyeRelZ * projectionFacingZ);
        double portalPlaneClearance = portalPlaneClearance(portal.getStructure().getArea(), localFrame);
        double maxProjectionDepth = depthBlocks + portalPlaneClearance;
        double signedMinDistance = eyeFrontSide ? -maxProjectionDepth : portalPlaneClearance;
        double signedMaxDistance = eyeFrontSide ? -portalPlaneClearance : maxProjectionDepth;
        boolean forceStableCellResample = stableResample || pendingRemoteResample;
        pendingRemoteResample = false;
        if (forceStableCellResample && Wormholes.projectionChangeTracker != null) {
            lastResampleVersion = Wormholes.projectionChangeTracker.currentVersion();
        }
        boolean forceFullSend = initialFullSendPassesRemaining > 0;
        PortalPlaneWindow planeWindow = PortalPlaneWindow.create(portal.getStructure(), portal.getStructure().getArea(), projectionLocalFrame,
            localOriginX, localOriginY, localOriginZ, Settings.PROJECTION_APERTURE_PADDING_BLOCKS,
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

        Direction projectionNormalDirection = projectionLocalFrame.getNormal();
        Direction projectionRightDirection = projectionLocalFrame.getRight();
        Direction projectionUpDirection = projectionLocalFrame.getUp();
        int normalAxis = projectionNormalDirection.x() != 0 ? 0 : (projectionNormalDirection.y() != 0 ? 1 : 2);
        int rightAxis = projectionRightDirection.x() != 0 ? 0 : (projectionRightDirection.y() != 0 ? 1 : 2);
        int rightSign = projectionRightDirection.x() + projectionRightDirection.y() + projectionRightDirection.z();
        int upAxis = projectionUpDirection.x() != 0 ? 0 : (projectionUpDirection.y() != 0 ? 1 : 2);
        int upSign = projectionUpDirection.x() + projectionUpDirection.y() + projectionUpDirection.z();
        int[] axisMin = scratchAxisMin;
        axisMin[0] = xa;
        axisMin[1] = ya;
        axisMin[2] = za;
        int[] axisMax = scratchAxisMax;
        axisMax[0] = xb;
        axisMax[1] = yb;
        axisMax[2] = zb;
        double[] axisOrigin = scratchAxisOrigin;
        axisOrigin[0] = localOriginX;
        axisOrigin[1] = localOriginY;
        axisOrigin[2] = localOriginZ;
        double projectionFacingNormal = normalAxis == 0 ? projectionFacingX : (normalAxis == 1 ? projectionFacingY : projectionFacingZ);
        double[] slabWindowBounds = scratchSlabWindowBounds;
        int[] cellCoords = scratchCellCoords;
        double blackoutEyeNormal = normalAxis == 0 ? eyeX : (normalAxis == 1 ? eyeY : eyeZ);
        double blackoutFacePlane = axisOrigin[normalAxis];
        double blackoutFarSign = 1.0D;
        double blackoutDepthSealThreshold = depthBlocks - BLACKOUT_PERPENDICULAR_BAND_MARGIN;
        double blackoutRightSealLow = Double.NEGATIVE_INFINITY;
        double blackoutRightSealHigh = Double.POSITIVE_INFINITY;
        double blackoutUpSealLow = Double.NEGATIVE_INFINITY;
        double blackoutUpSealHigh = Double.POSITIVE_INFINITY;
        if (blackoutEnabled) {
            AxisAlignedBB blackoutArea = portal.getStructure().getArea();
            double blackoutAreaMinNormal = normalAxis == 0 ? blackoutArea.getXa() : (normalAxis == 1 ? blackoutArea.getYa() : blackoutArea.getZa());
            double blackoutAreaMaxNormal = normalAxis == 0 ? blackoutArea.getXb() : (normalAxis == 1 ? blackoutArea.getYb() : blackoutArea.getZb());
            blackoutFacePlane = eyeSideFacePlane(blackoutEyeNormal, axisOrigin[normalAxis], blackoutAreaMinNormal, blackoutAreaMaxNormal);
            blackoutFarSign = blackoutEyeNormal >= axisOrigin[normalAxis] ? -1.0D : 1.0D;
            double blackoutLateralMargin = BLACKOUT_PERPENDICULAR_BAND_MARGIN + Settings.PROJECTION_APERTURE_PADDING_BLOCKS;
            double blackoutAreaMinRight = rightAxis == 0 ? blackoutArea.getXa() : (rightAxis == 1 ? blackoutArea.getYa() : blackoutArea.getZa());
            double blackoutAreaMaxRight = rightAxis == 0 ? blackoutArea.getXb() : (rightAxis == 1 ? blackoutArea.getYb() : blackoutArea.getZb());
            double blackoutAreaMinUp = upAxis == 0 ? blackoutArea.getXa() : (upAxis == 1 ? blackoutArea.getYa() : blackoutArea.getZa());
            double blackoutAreaMaxUp = upAxis == 0 ? blackoutArea.getXb() : (upAxis == 1 ? blackoutArea.getYb() : blackoutArea.getZb());
            blackoutRightSealLow = blackoutAreaMinRight - depthBlocks + blackoutLateralMargin;
            blackoutRightSealHigh = blackoutAreaMaxRight + depthBlocks - blackoutLateralMargin;
            blackoutUpSealLow = blackoutAreaMinUp - depthBlocks + blackoutLateralMargin;
            blackoutUpSealHigh = blackoutAreaMaxUp + depthBlocks - blackoutLateralMargin;
        }

        for (int n = axisMin[normalAxis]; n <= axisMax[normalAxis]; n++) {
            double slabSignedDistance = projectionFacingNormal * ((n + 0.5D) - axisOrigin[normalAxis]);
            if (!planeWindow.slabWindow(eyeX, eyeY, eyeZ, slabSignedDistance, slabWindowBounds)) {
                continue;
            }
            int rightBlockMin = PortalPlaneWindow.slabBlockMin(slabWindowBounds[0], slabWindowBounds[1], rightSign, axisOrigin[rightAxis], axisMin[rightAxis]);
            int rightBlockMax = PortalPlaneWindow.slabBlockMax(slabWindowBounds[0], slabWindowBounds[1], rightSign, axisOrigin[rightAxis], axisMax[rightAxis]);
            int upBlockMin = PortalPlaneWindow.slabBlockMin(slabWindowBounds[2], slabWindowBounds[3], upSign, axisOrigin[upAxis], axisMin[upAxis]);
            int upBlockMax = PortalPlaneWindow.slabBlockMax(slabWindowBounds[2], slabWindowBounds[3], upSign, axisOrigin[upAxis], axisMax[upAxis]);
            cellCoords[normalAxis] = n;
            for (int r = rightBlockMin; r <= rightBlockMax; r++) {
                cellCoords[rightAxis] = r;
                for (int u = upBlockMin; u <= upBlockMax; u++) {
                    cellCoords[upAxis] = u;
                    int x = cellCoords[0];
                    int y = cellCoords[1];
                    int z = cellCoords[2];
                    double cx = x + 0.5D;
                    double cy = y + 0.5D;
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
                    cellTransform.apply(cx, cy, cz, scratchRemotePoint);

                    int rx = (int) Math.floor(scratchRemotePoint[0]);
                    int ry = (int) Math.floor(scratchRemotePoint[1]);
                    int rz = (int) Math.floor(scratchRemotePoint[2]);
                    long remoteKey = packKey(rx, ry, rz);
                    ProjectedBlockClaim previousCell = projected.get(key);
                    BlockData previousData = previousCell == null ? null : previousCell.getData();
                    long previousRemoteKey = previousCell == null ? NO_REMOTE_KEY : previousCell.getLightRemoteKey();
                    if (!localView.isChunkReady(x, z)) {
                        localView.requestChunk(x, z);
                        if (previousCell != null) {
                            nextProjected.put(key, previousCell);
                            keptCount++;
                        }
                        continue;
                    }
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
                    if (blackoutEnabled) {
                        boolean blackoutOccluding = sample.kind == ProjectedSampleKind.BLOCK && sample.data.getMaterial().isOccluding();
                        if (shouldBlackoutSample(sample.kind, blackoutOccluding)) {
                            double blackoutCellNormal = normalAxis == 0 ? cx : (normalAxis == 1 ? cy : cz);
                            double blackoutDepth = blackoutFarSign * (blackoutCellNormal - blackoutFacePlane);
                            double blackoutCellRight = rightAxis == 0 ? cx : (rightAxis == 1 ? cy : cz);
                            double blackoutCellUp = upAxis == 0 ? cx : (upAxis == 1 ? cy : cz);
                            if (isFarPlaneCell(blackoutDepth, blackoutDepthSealThreshold,
                                blackoutCellRight, blackoutRightSealLow, blackoutRightSealHigh,
                                blackoutCellUp, blackoutUpSealLow, blackoutUpSealHigh)) {
                                ProjectedBlockClaim blackoutClaim = (sample.kind == ProjectedSampleKind.REMOTE_AIR || sample.kind == ProjectedSampleKind.BLOCK)
                                    ? sample.asClaim(blackoutData)
                                    : new ProjectedBlockClaim(blackoutData, null, remoteKey, false);
                                if (forceFullSend || !blackoutData.equals(previousData)) {
                                    enterCount++;
                                } else {
                                    keptCount++;
                                }
                                nextProjected.put(key, blackoutClaim);
                                continue;
                            }
                        }
                    }
                    if (sample.kind == ProjectedSampleKind.NO_SAMPLE) {
                        if (!destView.isChunkReady(rx, rz) && previousCell != null && previousRemoteKey == remoteKey) {
                            nextProjected.put(key, previousCell);
                            keptCount++;
                        }
                        continue;
                    }

                    BlockData projectedHit;
                    boolean maskAir = sample.kind == ProjectedSampleKind.MASK_AIR;
                    boolean remoteAir = sample.kind == ProjectedSampleKind.REMOTE_AIR;
                    if (maskAir || remoteAir) {
                        boolean localAir = remoteAir && isLocalAir(localView, x, y, z);
                        if (!shouldProjectAirSample(sample.kind, localAir)) {
                            continue;
                        }
                        if (maskAir) {
                            lastMaskedCells++;
                        }
                        projectedHit = airBlockData;
                    } else {
                        projectedHit = transformProjectedBlockData(sample.data, projectionRemoteFrame, projectionLocalFrame,
                            mirrorMode, localFrame, mirrorRotationQuarterTurns);
                    }

                    if (forceFullSend || !projectedHit.equals(previousData)) {
                        enterCount++;
                    } else {
                        keptCount++;
                    }
                    ProjectedBlockClaim nextCell = sample.matchesClaim(previousCell, projectedHit, maskAir)
                        ? previousCell
                        : sample.asClaim(projectedHit);
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

        if (!activeGuard.getAsBoolean()) {
            close();
            return;
        }
        if (discardRequested) {
            discard();
            return;
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

        if (updateEntities) {
            updateProjectedEntities(dest, destAnchor, destView, mirrorMode, mirrorRotationQuarterTurns, next, depthBlocks,
                !nextProjected.isEmpty(), projectionLocalFrame, projectionRemoteFrame);
        }
        lastSourceViewRevision = destView.getRevision();
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
                                    IPortal destAnchor,
                                    ProjectionWorldView destView,
                                    boolean mirrorMode,
                                    int mirrorRotationQuarterTurns,
                                    Location eye) {
        if (destAnchor == null || !firstProjectionDone || projected.isEmpty()) {
            lastProjectNanos = System.nanoTime() - startNanos;
            WormholesTelemetry.addRenderNanos(lastProjectNanos);
            return;
        }

        double portalDepth = portal.getNetworkViewDepth();
        double range = capProjectionDistance(observer, portalDepth);
        double depthBlocks = range;
        Frustum4D frustum;
        try {
            frustum = frustumFor(eye, portal.getStructure(), range);
        } catch (RuntimeException ex) {
            Wormholes.w("[Projector] failed to build entity frustum for portal " + portal.getName() + " observer " + observer.getName() + ": " + ex);
            ex.printStackTrace();
            return;
        }

        PortalFrame localFrame = portal.getFrame();
        PortalFrame remoteFrame = mirrorMode ? localFrame.flipNormal() : destAnchor.getFrame();
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
        updateProjectedEntities(dest, destAnchor, destView, mirrorMode, mirrorRotationQuarterTurns, frustum, depthBlocks, true,
            projectionLocalFrame, projectionRemoteFrame);
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
                return maskAirSample;
            }
            ProjectedSample nested = resolveProjectedSample(liveView(hit.world),
                hit.pointX, hit.pointY, hit.pointZ,
                hit.eyeX, hit.eyeY, hit.eyeZ,
                hit.destinationPortal,
                remainingDepth - 1);
            if (nested.kind == ProjectedSampleKind.NO_SAMPLE) {
                return maskAirSample;
            }
            if (nested.kind != ProjectedSampleKind.BLOCK || !ProjectedBlockDataTransformer.requiresTransform(nested.data)) {
                return nested;
            }
            BlockData transformed = hit.mirrorProjection
                ? ProjectedBlockDataTransformer.mirror(nested.data, hit.mirrorFrame, hit.mirrorRotationQuarterTurns, scratchRot)
                : ProjectedBlockDataTransformer.transform(nested.data, hit.remoteFrame, hit.localFrame, scratchRot);
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

    private ProjectionWorldView liveView(World world) {
        ProjectionWorldView view = liveViews.get(world);
        if (view == null) {
            view = viewProvider.view(world);
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
    }

    private ProjectionWorldView remoteWorldView(String peerName, UUID portalId) {
        ViewSubscriptionManager subscriptions = Wormholes.viewSubscriptions;
        if (subscriptions == null || peerName == null || portalId == null) {
            return null;
        }
        RemoteViewCache.RemoteView view = subscriptions.touch(peerName, portalId, portal.getNetworkViewUnsubscribeGraceSeconds());
        String fallbackState = portal.getNetworkViewFallbackBlock();
        boolean fallbackChanged = remoteFallback == null || !fallbackState.equals(remoteFallbackState);
        if (fallbackChanged) {
            remoteFallback = parseRemoteFallback(fallbackState);
            remoteFallbackState = fallbackState;
        }
        if (!fallbackChanged && cachedRemoteViewSource == view && cachedRemoteWorldView != null) {
            return cachedRemoteWorldView;
        }
        cachedRemoteViewSource = view;
        cachedRemoteWorldView = new RemoteWorldView(view, remoteFallback);
        return cachedRemoteWorldView;
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

    private static boolean isLocalAir(ProjectionWorldView view, int x, int y, int z) {
        BlockData data = view.sampleBlockData(x, y, z);
        return data != null && isAir(data.getMaterial());
    }

    static boolean shouldMaskRecursivePortalAperture(boolean traversable, boolean cycle, int remainingDepth) {
        return !traversable || cycle || remainingDepth <= 0;
    }

    static boolean shouldProjectAirSample(ProjectedSampleKind kind, boolean localAir) {
        return kind == ProjectedSampleKind.MASK_AIR || (kind == ProjectedSampleKind.REMOTE_AIR && !localAir);
    }

    static boolean shouldBlackoutSample(ProjectedSampleKind kind, boolean occluding) {
        return switch (kind) {
            case NO_SAMPLE, MASK_AIR, REMOTE_AIR -> true;
            case BLOCK -> !occluding;
        };
    }

    static boolean isFarPlaneCell(double depthBeyondFacePlane, double depthSealThreshold,
                                  double rightCoord, double rightSealLow, double rightSealHigh,
                                  double upCoord, double upSealLow, double upSealHigh) {
        if (depthBeyondFacePlane >= depthSealThreshold) {
            return true;
        }
        return rightCoord <= rightSealLow || rightCoord >= rightSealHigh
            || upCoord <= upSealLow || upCoord >= upSealHigh;
    }

    static double eyeSideFacePlane(double eyeNormal, double originNormal, double areaMinNormal, double areaMaxNormal) {
        return eyeNormal >= originNormal ? areaMaxNormal : areaMinNormal;
    }

    private static BlockData parseBlackout(BlackoutColor color) {
        try {
            return Bukkit.createBlockData(color.blockState());
        } catch (IllegalArgumentException e) {
            return Material.AIR.createBlockData();
        }
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
                                         IPortal destAnchor,
                                         ProjectionWorldView destView,
                                         boolean mirrorMode,
                                         int mirrorRotationQuarterTurns,
                                         Frustum4D frustum,
                                         double depthBlocks,
                                         boolean hasVisibleProjection,
                                         PortalFrame projectionLocalFrame,
                                         PortalFrame projectionRemoteFrame) {
        if (!hasVisibleProjection || destAnchor == null) {
            entityRenderer.close(observer);
            return;
        }
        if (viewProvider.usesRegionSnapshots() && destView instanceof ProjectionEntityView entityView) {
            entityRenderer.applySnapshot(observer, portal, destAnchor, mirrorMode, mirrorRotationQuarterTurns,
                entityView, frustum, depthBlocks,
                projectionLocalFrame, projectionRemoteFrame);
            return;
        }
        if (dest != null) {
            entityRenderer.apply(observer, portal, dest, frustum, depthBlocks, projectionLocalFrame,
                projectionRemoteFrame, mirrorRotationQuarterTurns);
            return;
        }
        if (destView instanceof RemoteWorldView remoteWorldView) {
            double remoteOriginX = destAnchor.getOrigin().getX();
            double remoteOriginY = destAnchor.getOrigin().getY();
            double remoteOriginZ = destAnchor.getOrigin().getZ();
            entityRenderer.applyRemote(observer, portal, remoteOriginX, remoteOriginY, remoteOriginZ,
                remoteWorldView, frustum, depthBlocks, projectionLocalFrame, projectionRemoteFrame);
        }
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

    private boolean canReuseProjection(Location eye, boolean stableResample, boolean localDestination) {
        boolean lightingBlocked = claimArbiter.hasPendingLighting(observer) && isLightingUpdatePass();
        return canReuseProjection(firstProjectionDone, !projected.isEmpty(), hasCameraSnapshot, localDestination,
            initialFullSendPassesRemaining, stableResample, pendingRemoteResample, lightingBlocked,
            eye.getX(), eye.getY(), eye.getZ(), lastEyeX, lastEyeY, lastEyeZ);
    }

    static boolean canReuseProjection(boolean firstProjectionDone,
                                      boolean hasProjection,
                                      boolean hasCameraSnapshot,
                                      boolean localDestination,
                                      int initialFullSendPassesRemaining,
                                      boolean stableResample,
                                      boolean pendingRemoteResample,
                                      boolean lightingBlocked,
                                      double eyeX,
                                      double eyeY,
                                      double eyeZ,
                                      double lastEyeX,
                                      double lastEyeY,
                                      double lastEyeZ) {
        if (!firstProjectionDone || !hasProjection || !hasCameraSnapshot || !localDestination) {
            return false;
        }
        if (initialFullSendPassesRemaining > 0 || stableResample || pendingRemoteResample || lightingBlocked) {
            return false;
        }
        return eyeX == lastEyeX && eyeY == lastEyeY && eyeZ == lastEyeZ;
    }

    private void rememberCamera(Location eye) {
        lastEyeX = eye.getX();
        lastEyeY = eye.getY();
        lastEyeZ = eye.getZ();
        hasCameraSnapshot = true;
    }

    private Frustum4D frustumFor(Location eye, PortalStructure structure, double range) {
        long structureRevision = structure.getRevision();
        double nearPlanePadding = Settings.NEAR_PLANE_PADDING;
        double cullingRatio = Settings.FRUSTUM_CULLING_RATIO;
        double aperturePadding = Settings.PROJECTION_APERTURE_PADDING_BLOCKS;
        Frustum4D cached = cachedFrustum;
        if (cached != null
            && cachedFrustumStructure == structure
            && cachedFrustumStructureRevision == structureRevision
            && cachedFrustumEyeX == eye.getX()
            && cachedFrustumEyeY == eye.getY()
            && cachedFrustumEyeZ == eye.getZ()
            && cachedFrustumRange == range
            && cachedFrustumNearPlanePadding == nearPlanePadding
            && cachedFrustumCullingRatio == cullingRatio
            && cachedFrustumAperturePadding == aperturePadding) {
            return cached;
        }
        Frustum4D built = new Frustum4D(eye, structure, range);
        cachedFrustum = built;
        cachedFrustumStructure = structure;
        cachedFrustumStructureRevision = structureRevision;
        cachedFrustumEyeX = eye.getX();
        cachedFrustumEyeY = eye.getY();
        cachedFrustumEyeZ = eye.getZ();
        cachedFrustumRange = range;
        cachedFrustumNearPlanePadding = nearPlanePadding;
        cachedFrustumCullingRatio = cullingRatio;
        cachedFrustumAperturePadding = aperturePadding;
        return built;
    }

    private boolean computeStableResample(World destWorld, double destinationOriginX, double destinationOriginZ,
                                          ProjectionWorldView sourceView) {
        if (!firstProjectionDone) {
            return true;
        }
        int cadence = stablePassInterval(stableResampleCadenceTicks());
        if (sourceView instanceof RemoteWorldView) {
            return (projectCallCount % cadence) == 0L;
        }
        if (sourceView.getRevision() != lastSourceViewRevision) {
            return true;
        }
        int backstop = stablePassInterval(fullRefreshBackstopTicks());
        if ((projectCallCount % backstop) == 0L) {
            return true;
        }
        if ((projectCallCount % cadence) != 0L) {
            return false;
        }
        return destinationDirty(destWorld, destinationOriginX, destinationOriginZ);
    }

    private boolean destinationDirty(World destWorld, double originX, double originZ) {
        if (destWorld == null || Wormholes.projectionChangeTracker == null) {
            return true;
        }
        double depth = portal.getNetworkViewDepth() + 2.0D;
        int minChunkX = ((int) Math.floor(originX - depth)) >> 4;
        int maxChunkX = ((int) Math.floor(originX + depth)) >> 4;
        int minChunkZ = ((int) Math.floor(originZ - depth)) >> 4;
        int maxChunkZ = ((int) Math.floor(originZ + depth)) >> 4;
        return Wormholes.projectionChangeTracker.dirtySince(destWorld.getUID(), minChunkX, minChunkZ, maxChunkX, maxChunkZ, lastResampleVersion);
    }

    private static int stablePassInterval(int intervalTicks) {
        int projectionInterval = Math.max(1, Settings.PROJECTION_REFRESH_INTERVAL_TICKS);
        int resampleInterval = Math.max(1, intervalTicks);
        return Math.max(1, (resampleInterval + projectionInterval - 1) / projectionInterval);
    }

    private boolean usesStandardViewQuality() {
        return NetworkViewQuality.from(
            portal.getNetworkViewDepth(),
            portal.getNetworkViewHeartbeatTicks(),
            portal.getNetworkViewEntityIntervalTicks(),
            portal.getNetworkViewUnsubscribeGraceSeconds()) == NetworkViewQuality.STANDARD;
    }

    private int stableResampleCadenceTicks() {
        if (usesStandardViewQuality()) {
            return Settings.PROJECTION_STABLE_CELL_RESAMPLE_INTERVAL_TICKS;
        }
        return Math.max(1, portal.getNetworkViewHeartbeatTicks());
    }

    private int fullRefreshBackstopTicks() {
        if (usesStandardViewQuality()) {
            return STABLE_RESAMPLE_BACKSTOP_TICKS;
        }
        return Math.max(1, portal.getNetworkViewHeartbeatTicks());
    }

    private boolean entityUpdateDue() {
        if (usesStandardViewQuality()) {
            return true;
        }
        int intervalTicks = Math.max(1, portal.getNetworkViewEntityIntervalTicks());
        int globalTicks = Math.max(1, Settings.ENTITY_UPDATE_INTERVAL_TICKS);
        int passInterval = Math.max(1, (intervalTicks + globalTicks - 1) / globalTicks);
        return (entityPassCount % passInterval) == 0L;
    }

    private void prepareTransformCache(PortalFrame fromFrame, PortalFrame toFrame,
                                       boolean mirrorTransform, int mirrorRotationQuarterTurns) {
        if (cachedFromNormal == fromFrame.getNormal()
            && cachedFromRight == fromFrame.getRight()
            && cachedFromUp == fromFrame.getUp()
            && cachedToNormal == toFrame.getNormal()
            && cachedToRight == toFrame.getRight()
            && cachedToUp == toFrame.getUp()
            && cachedMirrorTransform == mirrorTransform
            && cachedMirrorRotationQuarterTurns == mirrorRotationQuarterTurns) {
            return;
        }
        cachedFromNormal = fromFrame.getNormal();
        cachedFromRight = fromFrame.getRight();
        cachedFromUp = fromFrame.getUp();
        cachedToNormal = toFrame.getNormal();
        cachedToRight = toFrame.getRight();
        cachedToUp = toFrame.getUp();
        cachedMirrorTransform = mirrorTransform;
        cachedMirrorRotationQuarterTurns = mirrorRotationQuarterTurns;
        transformedBlockCache.clear();
    }

    private BlockData transformProjectedBlockData(BlockData source, PortalFrame fromFrame, PortalFrame toFrame,
                                                  boolean mirrorMode, PortalFrame mirrorFrame,
                                                  int mirrorRotationQuarterTurns) {
        if (!ProjectedBlockDataTransformer.requiresTransform(source)) {
            return source;
        }

        BlockData cached = transformedBlockCache.get(source);
        if (cached != null) {
            return cached;
        }

        BlockData transformed = mirrorMode
            ? ProjectedBlockDataTransformer.mirror(source, mirrorFrame, mirrorRotationQuarterTurns, scratchRot)
            : ProjectedBlockDataTransformer.transform(source, fromFrame, toFrame, scratchRot);
        if (transformedBlockCache.size() >= 4096) {
            transformedBlockCache.clear();
        }
        transformedBlockCache.put(source, transformed);
        return transformed;
    }

    private void invalidateRtpDestinationState() {
        pendingRemoteResample = true;
        lastSourceViewRevision = -1L;
        lastResampleVersion = -1L;
        remoteSampleCache.clear();
        recursivePortalCandidates.clear();
        recursivePortalIndexes.clear();
        entityRenderer.close(observer);
    }

    public synchronized void close() {
        if (closed) {
            return;
        }
        closed = true;

        if (observer == null || !observer.isOnline()) {
            claimArbiter.discardObserver(observerId, localWorldId);
            projected.clear();
            nextProjected.clear();
            lastRenderedCells = 0;
            entityRenderer.discard(observer);
            return;
        }

        World observerWorld = observer.getWorld();
        if (localWorld == null || localWorldId == null || observerWorld == null
            || !localWorldId.equals(observerWorld.getUID())) {
            claimArbiter.discardObserver(observer.getUniqueId(), localWorldId);
            projected.clear();
            nextProjected.clear();
            entityRenderer.discard(observer);
            lastRenderedCells = 0;
            return;
        }

        ProjectionClaimArbiter.ClaimUpdateResult result = claimArbiter.release(observer, portal, localWorld, true);
        if (result.getBlockChanges() > 0) {
            Wormholes.v("[Projector] portal=" + portal.getName() + " observer=" + observer.getName()
                + " close: reverted=" + result.getBlockChanges());
        }
        entityRenderer.close(observer);

        projected.clear();
        nextProjected.clear();
        lastRenderedCells = 0;
    }

    public synchronized void discard() {
        if (closed) {
            return;
        }
        closed = true;
        if (observer != null) {
            claimArbiter.discardObserver(observer.getUniqueId(), localWorldId);
        }
        projected.clear();
        nextProjected.clear();
        lastRenderedCells = 0;
        entityRenderer.discard(observer);
    }

    public void requestDiscard() {
        discardRequested = true;
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

    public record RtpProjectionTarget(World world, double originX, double originY, double originZ,
                                      PortalFrame frame, long routeRevision) {
        public RtpProjectionTarget {
            Objects.requireNonNull(world, "world");
            Objects.requireNonNull(frame, "frame");
            if (!Double.isFinite(originX) || !Double.isFinite(originY) || !Double.isFinite(originZ)) {
                throw new IllegalArgumentException("RTP projection target coordinates must be finite");
            }
            if (routeRevision < 0L) {
                throw new IllegalArgumentException("routeRevision must be non-negative");
            }
        }

        public static RtpProjectionTarget from(RtpProjectionView.ReadyData readyData, World world) {
            RtpProjectionView.ReadyData requiredReadyData = Objects.requireNonNull(readyData, "readyData");
            RtpProjectionView.Target target = requiredReadyData.target();
            Direction normal = direction(target.forward(), "forward").reverse();
            Direction right = direction(target.right(), "right");
            Direction up = direction(target.up(), "up");
            PortalFrame frame = new PortalFrame(normal, right, up);
            RtpProjectionView.Point3 safeFeet = target.safeFeet();
            return new RtpProjectionTarget(world, safeFeet.x(), safeFeet.y(), safeFeet.z(), frame,
                    requiredReadyData.routeRevision());
        }

        public boolean requiresDestinationInvalidation(RtpProjectionTarget previous) {
            return previous == null || routeRevision != previous.routeRevision;
        }

        private static Direction direction(RtpProjectionView.Vector3 vector, String name) {
            RtpProjectionView.Vector3 requiredVector = Objects.requireNonNull(vector, name);
            double lengthSquared = requiredVector.x() * requiredVector.x()
                    + requiredVector.y() * requiredVector.y()
                    + requiredVector.z() * requiredVector.z();
            if (lengthSquared <= 1.0E-12D) {
                throw new IllegalArgumentException(name + " must not be zero");
            }
            return Direction.closest(requiredVector.x(), requiredVector.y(), requiredVector.z());
        }
    }

    enum ProjectedSampleKind {
        BLOCK,
        REMOTE_AIR,
        MASK_AIR,
        NO_SAMPLE
    }

    private static final class ProjectedSample {
        private static final ProjectedSample NO_SAMPLE = new ProjectedSample(
            ProjectedSampleKind.NO_SAMPLE, null, null, ProjectedBlockClaim.NO_REMOTE_KEY);

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
            return NO_SAMPLE;
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

        private boolean matchesClaim(ProjectedBlockClaim claim, BlockData projectedData, boolean maskAir) {
            if (claim == null
                || claim.getLightRemoteKey() != remoteKey
                || claim.isMaskAir() != maskAir
                || !claim.getData().equals(projectedData)) {
                return false;
            }
            ProjectionWorldView previousLightView = claim.getLightView();
            return previousLightView == null ? lightView == null : previousLightView.equals(lightView);
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
        private final boolean mirrorProjection;
        private final int mirrorRotationQuarterTurns;
        private final PortalFrame mirrorFrame;
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
                this.mirrorProjection = false;
                this.mirrorRotationQuarterTurns = 0;
                this.mirrorFrame = null;
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
            boolean mirrors;
            int mirrorQuarterTurns;
            if (candidate.isMirrorMode()) {
                destination = candidate;
                destinationWorld = candidate.getWorld();
                destinationFrame = viewFrame(frame.flipNormal(), frontSide);
                destinationOriginX = candidateOriginX;
                destinationOriginY = candidateOriginY;
                destinationOriginZ = candidateOriginZ;
                canTraverse = destinationWorld != null;
                mirrors = true;
                mirrorQuarterTurns = candidate.getMirrorRotation().getQuarterTurns();
            } else if (candidate.getTunnel() != null && candidate.getTunnel().getDestination() instanceof ILocalPortal linkedDestination) {
                destination = linkedDestination;
                destinationWorld = linkedDestination.getWorld();
                destinationFrame = linkedDestination.getFrame() == null ? null : viewFrame(linkedDestination.getFrame(), frontSide);
                destinationOriginX = linkedDestination.getOrigin() == null ? 0.0D : linkedDestination.getOrigin().getX();
                destinationOriginY = linkedDestination.getOrigin() == null ? 0.0D : linkedDestination.getOrigin().getY();
                destinationOriginZ = linkedDestination.getOrigin() == null ? 0.0D : linkedDestination.getOrigin().getZ();
                canTraverse = destinationWorld != null && destinationFrame != null && linkedDestination.getOrigin() != null;
                mirrors = false;
                mirrorQuarterTurns = 0;
            } else {
                destination = null;
                destinationWorld = null;
                destinationFrame = null;
                destinationOriginX = 0.0D;
                destinationOriginY = 0.0D;
                destinationOriginZ = 0.0D;
                canTraverse = false;
                mirrors = false;
                mirrorQuarterTurns = 0;
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
                if (mirrors) {
                    double[] matrixScratch = scratchRot;
                    PortalCoordMap.mirrorDisplayToSourceVectorInto(1.0D, 0.0D, 0.0D, frame, mirrorQuarterTurns, matrixScratch);
                    matrixXX = matrixScratch[0];
                    matrixYX = matrixScratch[1];
                    matrixZX = matrixScratch[2];
                    PortalCoordMap.mirrorDisplayToSourceVectorInto(0.0D, 1.0D, 0.0D, frame, mirrorQuarterTurns, matrixScratch);
                    matrixXY = matrixScratch[0];
                    matrixYY = matrixScratch[1];
                    matrixZY = matrixScratch[2];
                    PortalCoordMap.mirrorDisplayToSourceVectorInto(0.0D, 0.0D, 1.0D, frame, mirrorQuarterTurns, matrixScratch);
                    matrixXZ = matrixScratch[0];
                    matrixYZ = matrixScratch[1];
                    matrixZZ = matrixScratch[2];
                } else {
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
                }
                nestedEyeX = destinationOriginX + (eyeRelX * matrixXX) + (eyeRelY * matrixXY) + (eyeRelZ * matrixXZ);
                nestedEyeY = destinationOriginY + (eyeRelX * matrixYX) + (eyeRelY * matrixYY) + (eyeRelZ * matrixYZ);
                nestedEyeZ = destinationOriginZ + (eyeRelX * matrixZX) + (eyeRelY * matrixZY) + (eyeRelZ * matrixZZ);
            }

            this.view = candidateView;
            this.localFrame = candidateLocalFrame;
            this.remoteFrame = destinationFrame;
            this.nestedWorld = destinationWorld;
            this.nestedDestination = destination;
            this.planeWindow = candidateView == null ? null : PortalPlaneWindow.create(candidate.getStructure(), candidate.getStructure().getArea(), candidateLocalFrame,
                candidateOriginX, candidateOriginY, candidateOriginZ, Settings.PROJECTION_APERTURE_PADDING_BLOCKS,
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
            this.mirrorProjection = mirrors;
            this.mirrorRotationQuarterTurns = mirrorQuarterTurns;
            this.mirrorFrame = mirrors ? frame : null;
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
                rayT, true, false, mirrorProjection, mirrorRotationQuarterTurns, mirrorFrame);
        }
    }

    private static final class HoistedFrameTransform {
        private double fromRightX;
        private double fromRightY;
        private double fromRightZ;
        private double fromUpX;
        private double fromUpY;
        private double fromUpZ;
        private double fromNormalX;
        private double fromNormalY;
        private double fromNormalZ;
        private double toRightX;
        private double toRightY;
        private double toRightZ;
        private double toUpX;
        private double toUpY;
        private double toUpZ;
        private double toNormalX;
        private double toNormalY;
        private double toNormalZ;
        private double fromOriginX;
        private double fromOriginY;
        private double fromOriginZ;
        private double toOriginX;
        private double toOriginY;
        private double toOriginZ;
        private boolean mirror;
        private double mirrorXX;
        private double mirrorXY;
        private double mirrorXZ;
        private double mirrorYX;
        private double mirrorYY;
        private double mirrorYZ;
        private double mirrorZX;
        private double mirrorZY;
        private double mirrorZZ;

        private void configure(PortalFrame from, PortalFrame to,
                               double fromOriginX, double fromOriginY, double fromOriginZ,
                               double toOriginX, double toOriginY, double toOriginZ) {
            this.mirror = false;
            this.fromRightX = from.getRight().x();
            this.fromRightY = from.getRight().y();
            this.fromRightZ = from.getRight().z();
            this.fromUpX = from.getUp().x();
            this.fromUpY = from.getUp().y();
            this.fromUpZ = from.getUp().z();
            this.fromNormalX = from.getNormal().x();
            this.fromNormalY = from.getNormal().y();
            this.fromNormalZ = from.getNormal().z();
            this.toRightX = to.getRight().x();
            this.toRightY = to.getRight().y();
            this.toRightZ = to.getRight().z();
            this.toUpX = to.getUp().x();
            this.toUpY = to.getUp().y();
            this.toUpZ = to.getUp().z();
            this.toNormalX = to.getNormal().x();
            this.toNormalY = to.getNormal().y();
            this.toNormalZ = to.getNormal().z();
            this.fromOriginX = fromOriginX;
            this.fromOriginY = fromOriginY;
            this.fromOriginZ = fromOriginZ;
            this.toOriginX = toOriginX;
            this.toOriginY = toOriginY;
            this.toOriginZ = toOriginZ;
        }

        private void configureMirror(PortalFrame frame, int quarterTurns,
                                     double originX, double originY, double originZ,
                                     double[] scratch3) {
            this.mirror = true;
            this.fromOriginX = originX;
            this.fromOriginY = originY;
            this.fromOriginZ = originZ;
            this.toOriginX = originX;
            this.toOriginY = originY;
            this.toOriginZ = originZ;
            PortalCoordMap.mirrorDisplayToSourceVectorInto(1.0D, 0.0D, 0.0D, frame, quarterTurns, scratch3);
            mirrorXX = scratch3[0];
            mirrorYX = scratch3[1];
            mirrorZX = scratch3[2];
            PortalCoordMap.mirrorDisplayToSourceVectorInto(0.0D, 1.0D, 0.0D, frame, quarterTurns, scratch3);
            mirrorXY = scratch3[0];
            mirrorYY = scratch3[1];
            mirrorZY = scratch3[2];
            PortalCoordMap.mirrorDisplayToSourceVectorInto(0.0D, 0.0D, 1.0D, frame, quarterTurns, scratch3);
            mirrorXZ = scratch3[0];
            mirrorYZ = scratch3[1];
            mirrorZZ = scratch3[2];
        }

        private void apply(double x, double y, double z, double[] out3) {
            double offsetX = x - fromOriginX;
            double offsetY = y - fromOriginY;
            double offsetZ = z - fromOriginZ;
            if (mirror) {
                out3[0] = toOriginX + (offsetX * mirrorXX) + (offsetY * mirrorXY) + (offsetZ * mirrorXZ);
                out3[1] = toOriginY + (offsetX * mirrorYX) + (offsetY * mirrorYY) + (offsetZ * mirrorYZ);
                out3[2] = toOriginZ + (offsetX * mirrorZX) + (offsetY * mirrorZY) + (offsetZ * mirrorZZ);
                return;
            }
            double frameRight = offsetX * fromRightX + offsetY * fromRightY + offsetZ * fromRightZ;
            double frameUp = offsetX * fromUpX + offsetY * fromUpY + offsetZ * fromUpZ;
            double frameNormal = offsetX * fromNormalX + offsetY * fromNormalY + offsetZ * fromNormalZ;
            out3[0] = toOriginX + frameRight * toRightX + frameUp * toUpX + frameNormal * toNormalX;
            out3[1] = toOriginY + frameRight * toRightY + frameUp * toUpY + frameNormal * toNormalY;
            out3[2] = toOriginZ + frameRight * toRightZ + frameUp * toUpZ + frameNormal * toNormalZ;
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
        private final boolean mirrorProjection;
        private final int mirrorRotationQuarterTurns;
        private final PortalFrame mirrorFrame;

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
                                   boolean cycle,
                                   boolean mirrorProjection,
                                   int mirrorRotationQuarterTurns,
                                   PortalFrame mirrorFrame) {
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
            this.mirrorProjection = mirrorProjection;
            this.mirrorRotationQuarterTurns = mirrorRotationQuarterTurns;
            this.mirrorFrame = mirrorFrame;
        }

        private static RecursivePortalHit mask(double rayT, boolean cycle) {
            return new RecursivePortalHit(null, null, null, null,
                0.0D, 0.0D, 0.0D,
                0.0D, 0.0D, 0.0D,
                rayT, false, cycle, false, 0, null);
        }
    }

    static final class PortalPlaneWindow {
        private static final double EPSILON = 1.0E-7D;

        private final PortalStructure structure;
        private final boolean perCell;
        private final int normalAxis;
        private final int planeCoord;
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
        private final double cellTolerance;

        private PortalPlaneWindow(PortalStructure structure,
                                  boolean perCell,
                                  int normalAxis,
                                  int planeCoord,
                                  double originX,
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
                                  double upMax,
                                  double cellTolerance) {
            this.structure = structure;
            this.perCell = perCell;
            this.normalAxis = normalAxis;
            this.planeCoord = planeCoord;
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
            this.cellTolerance = cellTolerance;
        }

        static PortalPlaneWindow create(PortalStructure structure,
                                        AxisAlignedBB area,
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

            Direction normal = frame.getNormal();
            int normalAxis = normal.x() != 0 ? 0 : (normal.y() != 0 ? 1 : 2);
            double normalOrigin = normalAxis == 0 ? originX : (normalAxis == 1 ? originY : originZ);
            int planeCoord = (int) Math.floor(normalOrigin);
            boolean perCell = structure != null && !structure.isFullCuboid();

            return new PortalPlaneWindow(structure, perCell, normalAxis, planeCoord,
                originX, originY, originZ,
                frame.getRight().x(), frame.getRight().y(), frame.getRight().z(),
                frame.getUp().x(), frame.getUp().y(), frame.getUp().z(),
                eyeSignedDistance, rightMin - padding, rightMax + padding, upMin - padding, upMax + padding,
                Math.min(padding, 1.0D - EPSILON));
        }

        boolean slabWindow(double eyeX, double eyeY, double eyeZ, double cellSignedDistance, double[] out4) {
            double denom = cellSignedDistance - eyeSignedDistance;
            if (Math.abs(denom) <= EPSILON) {
                return false;
            }
            double t = -eyeSignedDistance / denom;
            if (t < -EPSILON || t > 1.0D + EPSILON) {
                return false;
            }
            if (t <= 1.0E-6D) {
                out4[0] = Double.NEGATIVE_INFINITY;
                out4[1] = Double.POSITIVE_INFINITY;
                out4[2] = Double.NEGATIVE_INFINITY;
                out4[3] = Double.POSITIVE_INFINITY;
                return true;
            }
            double relX = eyeX - originX;
            double relY = eyeY - originY;
            double relZ = eyeZ - originZ;
            double eyeR = (relX * rightX) + (relY * rightY) + (relZ * rightZ);
            double eyeU = (relX * upX) + (relY * upY) + (relZ * upZ);
            out4[0] = eyeR + (((rightMin - EPSILON) - eyeR) / t);
            out4[1] = eyeR + (((rightMax + EPSILON) - eyeR) / t);
            out4[2] = eyeU + (((upMin - EPSILON) - eyeU) / t);
            out4[3] = eyeU + (((upMax + EPSILON) - eyeU) / t);
            return true;
        }

        static int slabBlockMin(double windowLow, double windowHigh, int sign, double axisOrigin, int clampMin) {
            double a = axisOrigin + (sign * windowLow);
            double b = axisOrigin + (sign * windowHigh);
            double lowest = Math.floor(Math.min(a, b)) - 1.0D;
            if (lowest <= clampMin) {
                return clampMin;
            }
            return (int) lowest;
        }

        static int slabBlockMax(double windowLow, double windowHigh, int sign, double axisOrigin, int clampMax) {
            double a = axisOrigin + (sign * windowLow);
            double b = axisOrigin + (sign * windowHigh);
            double highest = Math.ceil(Math.max(a, b)) + 1.0D;
            if (highest >= clampMax) {
                return clampMax;
            }
            return (int) highest;
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
            if (right < rightMin - EPSILON || right > rightMax + EPSILON
                || up < upMin - EPSILON || up > upMax + EPSILON) {
                return false;
            }
            if (!perCell) {
                return true;
            }
            int bx = (int) Math.floor(hitX);
            int by = (int) Math.floor(hitY);
            int bz = (int) Math.floor(hitZ);
            if (normalAxis == 0) {
                bx = planeCoord;
            } else if (normalAxis == 1) {
                by = planeCoord;
            } else {
                bz = planeCoord;
            }
            if (structure.containsBlock(bx, by, bz)) {
                return true;
            }
            if (cellTolerance <= 0.0D) {
                return false;
            }
            double firstLateral = normalAxis == 0 ? hitY : hitX;
            double secondLateral = normalAxis == 2 ? hitY : hitZ;
            int firstLow = lateralLowOffset(firstLateral, cellTolerance);
            int firstHigh = lateralHighOffset(firstLateral, cellTolerance);
            int secondLow = lateralLowOffset(secondLateral, cellTolerance);
            int secondHigh = lateralHighOffset(secondLateral, cellTolerance);
            for (int first = firstLow; first <= firstHigh; first++) {
                for (int second = secondLow; second <= secondHigh; second++) {
                    if (first == 0 && second == 0) {
                        continue;
                    }
                    int nx = bx;
                    int ny = by;
                    int nz = bz;
                    if (normalAxis == 0) {
                        ny = by + first;
                        nz = bz + second;
                    } else if (normalAxis == 1) {
                        nx = bx + first;
                        nz = bz + second;
                    } else {
                        nx = bx + first;
                        ny = by + second;
                    }
                    if (structure.containsBlock(nx, ny, nz)) {
                        return true;
                    }
                }
            }
            return false;
        }

        private static int lateralLowOffset(double coordinate, double tolerance) {
            return coordinate - Math.floor(coordinate) < tolerance ? -1 : 0;
        }

        private static int lateralHighOffset(double coordinate, double tolerance) {
            return coordinate - Math.floor(coordinate) > 1.0D - tolerance ? 1 : 0;
        }

        private static double dot(double x, double y, double z, Direction direction) {
            return (x * direction.x()) + (y * direction.y()) + (z * direction.z());
        }
    }
}
