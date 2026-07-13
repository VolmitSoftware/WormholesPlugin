package art.arcane.wormholes.render;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Predicate;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.Vector;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.protocol.entity.data.EntityData;
import com.github.retrooper.packetevents.protocol.entity.data.EntityDataTypes;
import com.github.retrooper.packetevents.protocol.entity.type.EntityType;
import com.github.retrooper.packetevents.protocol.entity.type.EntityTypes;
import com.github.retrooper.packetevents.protocol.player.Equipment;
import com.github.retrooper.packetevents.protocol.player.GameMode;
import com.github.retrooper.packetevents.protocol.player.TextureProperty;
import com.github.retrooper.packetevents.protocol.player.User;
import com.github.retrooper.packetevents.protocol.player.UserProfile;
import com.github.retrooper.packetevents.util.Vector3d;
import com.github.retrooper.packetevents.wrapper.PacketWrapper;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerDestroyEntities;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityAnimation;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityAnimation.EntityAnimationType;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityEquipment;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityHeadLook;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityMetadata;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityRelativeMove;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityRelativeMoveAndRotation;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityRotation;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerAttachEntity;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityTeleport;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityVelocity;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerHurtAnimation;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSetPassengers;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerPlayerInfoRemove;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerPlayerInfoUpdate;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSpawnEntity;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerTeams;
import io.github.retrooper.packetevents.util.SpigotConversionUtil;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;

import art.arcane.volmlib.util.scheduling.FoliaScheduler;
import art.arcane.volmlib.util.bukkit.WorldIdentity;
import art.arcane.wormholes.EffectManager;
import art.arcane.wormholes.Settings;
import art.arcane.wormholes.Wormholes;
import art.arcane.wormholes.network.view.EntityVisual;
import art.arcane.wormholes.network.view.PacketBlobs;
import art.arcane.wormholes.network.view.RemoteViewCache;
import art.arcane.wormholes.platform.WormholesPlatform;
import art.arcane.wormholes.portal.ILocalPortal;
import art.arcane.wormholes.portal.IPortal;
import art.arcane.wormholes.portal.PortalFrame;
import art.arcane.wormholes.render.view.ProjectionEntityView;
import art.arcane.wormholes.render.view.RemoteWorldView;
import art.arcane.wormholes.service.WormholesTelemetry;

public final class ProjectedEntityRenderer {
    private static final AtomicInteger NEXT_FAKE_ID = new AtomicInteger(1_900_000_000);
    private static final int METADATA_REFRESH_PASSES = 10;
    private static final String FLIP_NAME = "Dinnerbone";
    private static final String FLIP_NAME_ALT = "Grumm";
    private static final String FLIP_TEAM_NAME = "wh_flip";
    private static final String NEUTRAL_PROFILE_NAME = "PortalPlayer";
    private static final int CUSTOM_NAME_INDEX = 2;
    private static final int CUSTOM_NAME_VISIBLE_INDEX = 3;
    private static final int PLAYER_SKIN_PARTS_INDEX = 17;
    private static final byte CAPE_PART_BIT = 0x01;
    private static final double MIN_POSITION_DELTA_SQUARED = 1.0E-6D;
    private static final double MAX_RELATIVE_MOVE_DELTA = 7.75D;
    private static final long ENTITY_STATE_REFRESH_MILLIS = 500L;
    private static final long STATIC_CACHE_EVICT_MILLIS = 10_000L;
    private static final Map<UUID, EntityCandidateSnapshot> REMOTE_ENTITY_CACHE = new ConcurrentHashMap<UUID, EntityCandidateSnapshot>();
    private static final Map<UUID, EntityCandidateSnapshot> LOCAL_ENTITY_CACHE = new ConcurrentHashMap<UUID, EntityCandidateSnapshot>();
    private static final Map<UUID, EntityStateSnapshot> ENTITY_STATE_CACHE = new ConcurrentHashMap<UUID, EntityStateSnapshot>();
    private static final AtomicLong STATIC_CACHE_SWEEP_DUE = new AtomicLong(0L);

    private final Map<UUID, SpoofedEntity> spoofed;
    private final Map<UUID, Entity> hiddenLocalEntities;
    private final Map<NamespacedKey, EntityType> entityTypeCache;
    private final Set<UUID> visible;
    private final Set<UUID> visibleLocalHides;
    private final double[] scratchVisiblePoint;
    private final double[] scratchDirection;
    private final double[] scratchLook;
    private final double[] scratchEntityPosition;
    private final AtomicBoolean localRestoreRetryScheduled;
    private boolean metadataBridgeFailed;
    private boolean localHideOwnershipWarningSent;
    private volatile boolean restoreAllRequested;
    private boolean flipTeamSent;
    private final Set<String> flipTeamMembers;
    private User batchUser;
    private volatile int publishedSpoofedCount;

    public ProjectedEntityRenderer() {
        this.spoofed = new HashMap<UUID, SpoofedEntity>(16);
        this.hiddenLocalEntities = new HashMap<UUID, Entity>(16);
        this.entityTypeCache = new HashMap<NamespacedKey, EntityType>(32);
        this.visible = new HashSet<UUID>(16);
        this.visibleLocalHides = new HashSet<UUID>(16);
        this.scratchVisiblePoint = new double[3];
        this.scratchDirection = new double[3];
        this.scratchLook = new double[3];
        this.scratchEntityPosition = new double[5];
        this.localRestoreRetryScheduled = new AtomicBoolean(false);
        this.metadataBridgeFailed = false;
        this.localHideOwnershipWarningSent = false;
        this.restoreAllRequested = false;
        this.flipTeamSent = false;
        this.flipTeamMembers = new HashSet<String>(4);
        this.batchUser = null;
    }

    private void sendCounted(Player observer, PacketWrapper<?> packet) {
        WormholesTelemetry.countPacket();
        if (batchUser != null) {
            batchUser.writePacket(packet);
            return;
        }
        PacketEvents.getAPI().getPlayerManager().sendPacket(observer, packet);
    }

    private void beginBatch(Player observer) {
        batchUser = observer == null ? null : PacketEvents.getAPI().getPlayerManager().getUser(observer);
    }

    private void endBatch() {
        User user = batchUser;
        batchUser = null;
        if (user != null) {
            user.flushPackets();
        }
    }

    public int getSpoofedCount() {
        return publishedSpoofedCount;
    }

    public void apply(Player observer,
                      ILocalPortal localPortal,
                      ILocalPortal remotePortal,
                      Frustum4D frustum,
                      double projectionDepth,
                      PortalFrame localViewFrame,
                      PortalFrame remoteViewFrame,
                      int mirrorRotationQuarterTurns) {
        if (!Settings.ENTITY_SPOOFING || Settings.MAX_SPOOFED_ENTITIES <= 0) {
            close(observer);
            return;
        }

        Location remoteCenter = remotePortal.getCenter();
        World remoteWorld = remotePortal.getWorld();
        if (observer == null || remoteCenter == null || remoteWorld == null) {
            close(observer);
            return;
        }

        beginBatch(observer);
        try {
            double range = Math.min(Settings.ENTITY_SPOOF_RANGE, projectionDepth);
            visible.clear();
            visibleLocalHides.clear();
            hideLocalEntities(observer, localPortal, frustum, range, projectionDepth);
            boolean upsideDown = remotePortal == localPortal
                ? PortalCoordMap.mirrorTransformFlipsWorldUp(localPortal.getFrame(), mirrorRotationQuarterTurns)
                : PortalCoordMap.transformFlipsWorldUp(remoteViewFrame, localViewFrame);
            int count = 0;

            for (Entity entity : nearbyRemoteEntities(remotePortal, remoteCenter, range)) {
                if (count >= Settings.MAX_SPOOFED_ENTITIES) {
                    break;
                }
                if (!canSpoof(entity)) {
                    continue;
                }
                if (!projectEntity(observer, localPortal, remotePortal, localViewFrame, remoteViewFrame, frustum,
                    entity, upsideDown, mirrorRotationQuarterTurns)) {
                    continue;
                }
                visible.add(entity.getUniqueId());
                count++;
            }

            destroyHidden(observer);
            restoreLocalEntities(observer);
        } finally {
            endBatch();
            publishedSpoofedCount = spoofed.size();
        }
    }

    public void applyRemote(Player observer,
                            ILocalPortal localPortal,
                            double remoteOriginX,
                            double remoteOriginY,
                            double remoteOriginZ,
                            RemoteWorldView remoteView,
                            Frustum4D frustum,
                            double projectionDepth,
                            PortalFrame localViewFrame,
                            PortalFrame remoteViewFrame) {
        if (!Settings.ENTITY_SPOOFING || Settings.MAX_SPOOFED_ENTITIES <= 0) {
            close(observer);
            return;
        }
        if (observer == null) {
            close(observer);
            return;
        }

        beginBatch(observer);
        try {
            double range = Math.min(Settings.ENTITY_SPOOF_RANGE, projectionDepth);
            visible.clear();
            visibleLocalHides.clear();
            hideLocalEntities(observer, localPortal, frustum, range, projectionDepth);
            boolean upsideDown = PortalCoordMap.transformFlipsWorldUp(remoteViewFrame, localViewFrame);
            int count = 0;

            for (EntityVisual visual : remoteView.getEntities()) {
                if (count >= Settings.MAX_SPOOFED_ENTITIES) {
                    break;
                }
                if (!projectRemoteVisual(observer, localPortal, remoteOriginX, remoteOriginY, remoteOriginZ, localViewFrame, remoteViewFrame, frustum, remoteView, visual, upsideDown)) {
                    continue;
                }
                visible.add(visual.id());
                count++;
            }

            applyEntityRelationships(observer, remoteView.getEntities());
            destroyHidden(observer);
            restoreLocalEntities(observer);
        } finally {
            endBatch();
            publishedSpoofedCount = spoofed.size();
        }
    }

    public void applySnapshot(Player observer,
                              ILocalPortal localPortal,
                              IPortal remotePortal,
                              boolean mirror,
                              int mirrorRotationQuarterTurns,
                              ProjectionEntityView entityView,
                              Frustum4D frustum,
                              double projectionDepth,
                              PortalFrame localViewFrame,
                              PortalFrame remoteViewFrame) {
        if (!Settings.ENTITY_SPOOFING || Settings.MAX_SPOOFED_ENTITIES <= 0) {
            close(observer);
            return;
        }
        if (observer == null || remotePortal == null || entityView == null) {
            close(observer);
            return;
        }

        double remoteOriginX = remotePortal.getOrigin().getX();
        double remoteOriginY = remotePortal.getOrigin().getY();
        double remoteOriginZ = remotePortal.getOrigin().getZ();
        beginBatch(observer);
        try {
            double range = Math.min(Settings.ENTITY_SPOOF_RANGE, projectionDepth);
            visible.clear();
            visibleLocalHides.clear();
            hideLocalEntities(observer, localPortal, frustum, range, projectionDepth);
            boolean upsideDown = mirror
                ? PortalCoordMap.mirrorTransformFlipsWorldUp(localPortal.getFrame(), mirrorRotationQuarterTurns)
                : PortalCoordMap.transformFlipsWorldUp(remoteViewFrame, localViewFrame);
            int count = 0;
            List<EntityVisual> visuals = entityView.getEntities(remoteOriginX, remoteOriginY, remoteOriginZ, range);
            for (EntityVisual visual : visuals) {
                if (count >= Settings.MAX_SPOOFED_ENTITIES) {
                    break;
                }
                if (!projectSnapshotVisual(observer, localPortal, remoteOriginX, remoteOriginY, remoteOriginZ,
                    localViewFrame, remoteViewFrame, frustum, entityView, visual, upsideDown, mirror,
                    mirrorRotationQuarterTurns)) {
                    continue;
                }
                visible.add(visual.id());
                count++;
            }
            applyEntityRelationships(observer, visuals);
            destroyHidden(observer);
            restoreLocalEntities(observer);
        } finally {
            endBatch();
            publishedSpoofedCount = spoofed.size();
        }
    }

    private static final int[] NO_PASSENGERS = new int[0];

    private void applyEntityRelationships(Player observer, List<EntityVisual> visuals) {
        if (!hasRelationshipWork(visuals)) {
            return;
        }
        Map<UUID, List<Integer>> ridersByVehicle = new HashMap<UUID, List<Integer>>();
        for (EntityVisual visual : visuals) {
            UUID vehicle = visual.passengerOf();
            if (vehicle == null) {
                continue;
            }
            SpoofedEntity rider = spoofed.get(visual.id());
            if (rider == null) {
                continue;
            }
            ridersByVehicle.computeIfAbsent(vehicle, ignored -> new ArrayList<Integer>()).add(rider.fakeId);
        }
        for (Map.Entry<UUID, SpoofedEntity> entry : spoofed.entrySet()) {
            SpoofedEntity vehicleState = entry.getValue();
            List<Integer> riders = ridersByVehicle.get(entry.getKey());
            if (riders == null) {
                if (vehicleState.lastPassengers != null && vehicleState.lastPassengers.length > 0) {
                    vehicleState.lastPassengers = NO_PASSENGERS;
                    sendCounted(observer, new WrapperPlayServerSetPassengers(vehicleState.fakeId, NO_PASSENGERS));
                }
                continue;
            }
            int[] passengers = new int[riders.size()];
            for (int i = 0; i < passengers.length; i++) {
                passengers[i] = riders.get(i).intValue();
            }
            if (!Arrays.equals(passengers, vehicleState.lastPassengers)) {
                vehicleState.lastPassengers = passengers;
                sendCounted(observer, new WrapperPlayServerSetPassengers(vehicleState.fakeId, passengers));
            }
        }
        for (EntityVisual visual : visuals) {
            SpoofedEntity mob = spoofed.get(visual.id());
            if (mob == null) {
                continue;
            }
            int holderFakeId = -1;
            UUID holderUuid = visual.leashHolder();
            if (holderUuid != null) {
                SpoofedEntity holder = spoofed.get(holderUuid);
                if (holder != null) {
                    holderFakeId = holder.fakeId;
                }
            }
            if (holderFakeId != mob.leashedToFakeId) {
                mob.leashedToFakeId = holderFakeId;
                sendCounted(observer, new WrapperPlayServerAttachEntity(mob.fakeId, holderFakeId, true));
            }
        }
    }

    private boolean hasRelationshipWork(List<EntityVisual> visuals) {
        for (EntityVisual visual : visuals) {
            if (visual.passengerOf() != null || visual.leashHolder() != null) {
                return true;
            }
        }
        for (SpoofedEntity state : spoofed.values()) {
            if (state.leashedToFakeId >= 0) {
                return true;
            }
            int[] lastPassengers = state.lastPassengers;
            if (lastPassengers != null && lastPassengers.length > 0) {
                return true;
            }
        }
        return false;
    }

    private boolean projectRemoteVisual(Player observer,
                                        ILocalPortal localPortal,
                                        double remoteOriginX,
                                        double remoteOriginY,
                                        double remoteOriginZ,
                                        PortalFrame localViewFrame,
                                        PortalFrame remoteViewFrame,
                                        Frustum4D frustum,
                                        ProjectionEntityView remoteView,
                                        EntityVisual visual,
                                        boolean upsideDown) {
        EntityType packetType = packetEntityTypeByKey(visual.typeKey());
        if (packetType == null) {
            return false;
        }

        double visibleY = visual.y() + (visual.height() * 0.5D);
        PortalCoordMap.transformPointInto(visual.x(), visibleY, visual.z(),
            remoteOriginX, remoteOriginY, remoteOriginZ,
            localPortal.getOrigin().getX(), localPortal.getOrigin().getY(), localPortal.getOrigin().getZ(),
            remoteViewFrame, localViewFrame, scratchVisiblePoint);

        if (!frustum.containsPrimitive(scratchVisiblePoint[0], scratchVisiblePoint[1], scratchVisiblePoint[2])) {
            return false;
        }

        remoteViewFrame.transformVectorInto(visual.lookX(), visual.lookY(), visual.lookZ(), localViewFrame, scratchDirection);
        float yaw = yaw(scratchDirection[0], scratchDirection[2]);
        float pitch = pitch(scratchDirection[0], scratchDirection[1], scratchDirection[2]);
        double visualBaseY = scratchVisiblePoint[1] - (visual.height() * 0.5D);
        Vector3d position = new Vector3d(scratchVisiblePoint[0], visualBaseY, scratchVisiblePoint[2]);
        remoteViewFrame.transformVectorInto(visual.velocityX(), visual.velocityY(), visual.velocityZ(), localViewFrame, scratchDirection);
        Vector3d velocity = new Vector3d(scratchDirection[0], scratchDirection[1], scratchDirection[2]);

        SpoofedEntity state = spoofed.get(visual.id());
        if (state != null && state.upsideDown != upsideDown) {
            destroySingleSpoof(observer, state);
            spoofed.remove(visual.id());
            state = null;
        }
        if (state == null) {
            state = new SpoofedEntity(NEXT_FAKE_ID.getAndIncrement(), UUID.randomUUID(), visual.isPlayer(), upsideDown,
                visual.isPlayer() || isLivingType(visual.typeKey()));
            spoofed.put(visual.id(), state);
            if (visual.isPlayer()) {
                sendRemotePlayerInfo(observer, remoteView.getProfile(visual.id()), state, upsideDown);
            }
            WrapperPlayServerSpawnEntity spawn = new WrapperPlayServerSpawnEntity(state.fakeId, Optional.of(state.fakeUuid),
                packetType, position, pitch, yaw, yaw, 0, Optional.of(velocity));
            sendCounted(observer, spawn);
            state.updateRotation(yaw, pitch);
            state.rememberPosition(position);
            sendHeadLook(observer, state, yaw);
            state.remoteStateVersion = remoteView.getStateVersion(visual.id());
            sendRemoteEntityState(observer, remoteView, visual, state);
            if (Settings.DEBUG) {
                Wormholes.v("[spoof] SPAWN " + (visual.isPlayer() ? "player" : "entity") + " src=" + visual.id() + " type=" + visual.typeKey() + " fakeId=" + state.fakeId + " -> " + observer.getName());
            }
            return true;
        }

        EntityMove move = state.updatePosition(position);
        boolean rotationChanged = state.updateRotation(yaw, pitch);
        sendEntityMovement(observer, state, move, rotationChanged, position, yaw, pitch, visual.onGround());
        if (rotationChanged) {
            sendHeadLook(observer, state, yaw);
        }
        if (state.updateVelocity(velocity)) {
            sendCounted(observer, new WrapperPlayServerEntityVelocity(state.fakeId, velocity));
        }
        int stateVersion = remoteView.getStateVersion(visual.id());
        if (stateVersion != state.remoteStateVersion) {
            state.remoteStateVersion = stateVersion;
            sendRemoteEntityState(observer, remoteView, visual, state);
        }
        return true;
    }

    private void sendRemoteEntityState(Player observer, ProjectionEntityView remoteView, EntityVisual visual, SpoofedEntity state) {
        List<EntityData<?>> metadata = remoteView.getMetadata(visual.id());
        if (metadata != null && !metadata.isEmpty()) {
            List<EntityData<?>> patched = state.upsideDown ? withUpsideDownMetadataRemote(visual.isPlayer(), metadata) : metadata;
            sendCounted(observer, new WrapperPlayServerEntityMetadata(state.fakeId, patched));
        }
        List<com.github.retrooper.packetevents.protocol.player.Equipment> equipment = remoteView.getEquipment(visual.id());
        if (equipment != null && !equipment.isEmpty()) {
            sendCounted(observer, new WrapperPlayServerEntityEquipment(state.fakeId, equipment));
        }
    }

    private boolean projectSnapshotVisual(Player observer,
                                          ILocalPortal localPortal,
                                          double remoteOriginX,
                                          double remoteOriginY,
                                          double remoteOriginZ,
                                          PortalFrame localViewFrame,
                                          PortalFrame remoteViewFrame,
                                          Frustum4D frustum,
                                          ProjectionEntityView entityView,
                                          EntityVisual visual,
                                          boolean upsideDown,
                                          boolean mirror,
                                          int mirrorRotationQuarterTurns) {
        EntityType packetType = packetEntityTypeByKey(visual.typeKey());
        if (packetType == null) {
            return false;
        }

        double visibleY = visual.y() + (visual.height() * 0.5D);
        if (mirror) {
            PortalCoordMap.mirrorSourceToDisplayPointInto(visual.x(), visibleY, visual.z(),
                remoteOriginX, remoteOriginY, remoteOriginZ, localPortal.getFrame(), mirrorRotationQuarterTurns,
                scratchVisiblePoint);
        } else {
            PortalCoordMap.transformPointInto(visual.x(), visibleY, visual.z(),
                remoteOriginX, remoteOriginY, remoteOriginZ,
                localPortal.getOrigin().getX(), localPortal.getOrigin().getY(), localPortal.getOrigin().getZ(),
                remoteViewFrame, localViewFrame, scratchVisiblePoint);
        }
        if (!frustum.containsPrimitive(scratchVisiblePoint[0], scratchVisiblePoint[1], scratchVisiblePoint[2])) {
            return false;
        }

        if (mirror) {
            PortalCoordMap.mirrorSourceToDisplayVectorInto(visual.lookX(), visual.lookY(), visual.lookZ(),
                localPortal.getFrame(), mirrorRotationQuarterTurns, scratchDirection);
        } else {
            remoteViewFrame.transformVectorInto(visual.lookX(), visual.lookY(), visual.lookZ(), localViewFrame, scratchDirection);
        }
        float yaw = yaw(scratchDirection[0], scratchDirection[2]);
        float pitch = pitch(scratchDirection[0], scratchDirection[1], scratchDirection[2]);
        double visualBaseY = scratchVisiblePoint[1] - (visual.height() * 0.5D);
        Vector3d position = new Vector3d(scratchVisiblePoint[0], visualBaseY, scratchVisiblePoint[2]);
        if (mirror) {
            PortalCoordMap.mirrorSourceToDisplayVectorInto(visual.velocityX(), visual.velocityY(), visual.velocityZ(),
                localPortal.getFrame(), mirrorRotationQuarterTurns, scratchDirection);
        } else {
            remoteViewFrame.transformVectorInto(visual.velocityX(), visual.velocityY(), visual.velocityZ(), localViewFrame, scratchDirection);
        }
        Vector3d velocity = new Vector3d(scratchDirection[0], scratchDirection[1], scratchDirection[2]);

        SpoofedEntity state = spoofed.get(visual.id());
        if (state != null && state.upsideDown != upsideDown) {
            destroySingleSpoof(observer, state);
            spoofed.remove(visual.id());
            state = null;
        }
        if (state == null) {
            state = new SpoofedEntity(NEXT_FAKE_ID.getAndIncrement(), UUID.randomUUID(), visual.isPlayer(), upsideDown,
                visual.isPlayer() || isLivingType(visual.typeKey()));
            spoofed.put(visual.id(), state);
            if (visual.isPlayer()) {
                sendRemotePlayerInfo(observer, entityView.getProfile(visual.id()), state, upsideDown);
            }
            WrapperPlayServerSpawnEntity spawn = new WrapperPlayServerSpawnEntity(state.fakeId, Optional.of(state.fakeUuid),
                packetType, position, pitch, yaw, yaw, 0, Optional.of(velocity));
            sendCounted(observer, spawn);
            state.updateRotation(yaw, pitch);
            state.rememberPosition(position);
            sendHeadLook(observer, state, yaw);
            state.remoteStateVersion = entityView.getStateVersion(visual.id());
            sendRemoteEntityState(observer, entityView, visual, state);
            return true;
        }

        EntityMove move = state.updatePosition(position);
        boolean rotationChanged = state.updateRotation(yaw, pitch);
        sendEntityMovement(observer, state, move, rotationChanged, position, yaw, pitch, visual.onGround());
        if (rotationChanged) {
            sendHeadLook(observer, state, yaw);
        }
        if (state.updateVelocity(velocity)) {
            sendCounted(observer, new WrapperPlayServerEntityVelocity(state.fakeId, velocity));
        }
        int stateVersion = entityView.getStateVersion(visual.id());
        if (stateVersion != state.remoteStateVersion) {
            state.remoteStateVersion = stateVersion;
            sendRemoteEntityState(observer, entityView, visual, state);
        }
        return true;
    }

    private List<EntityData<?>> withUpsideDownMetadataRemote(boolean isPlayer, List<EntityData<?>> metadata) {
        if (isPlayer) {
            List<EntityData<?>> patched = new ArrayList<EntityData<?>>(metadata.size() + 1);
            byte skinParts = 0;
            for (EntityData<?> data : metadata) {
                if (data.getIndex() == PLAYER_SKIN_PARTS_INDEX && data.getValue() instanceof Byte) {
                    skinParts = ((Byte) data.getValue()).byteValue();
                    continue;
                }
                patched.add(data);
            }
            patched.add(new EntityData<Byte>(PLAYER_SKIN_PARTS_INDEX, EntityDataTypes.BYTE, Byte.valueOf((byte) (skinParts | CAPE_PART_BIT))));
            return patched;
        }
        List<EntityData<?>> patched = new ArrayList<EntityData<?>>(metadata.size() + 2);
        for (EntityData<?> data : metadata) {
            if (data.getIndex() == CUSTOM_NAME_INDEX || data.getIndex() == CUSTOM_NAME_VISIBLE_INDEX) {
                continue;
            }
            patched.add(data);
        }
        patched.add(new EntityData<Optional<Component>>(CUSTOM_NAME_INDEX, EntityDataTypes.OPTIONAL_ADV_COMPONENT, Optional.of(Component.text(FLIP_NAME))));
        patched.add(new EntityData<Boolean>(CUSTOM_NAME_VISIBLE_INDEX, EntityDataTypes.BOOLEAN, Boolean.FALSE));
        return patched;
    }

    private void sendRemotePlayerInfo(Player observer, RemoteViewCache.RemoteProfile profile, SpoofedEntity state, boolean upsideDown) {
        String sourceName = profile == null ? null : profile.name();
        String name = upsideDown
            ? (isFlipName(sourceName) ? NEUTRAL_PROFILE_NAME : FLIP_NAME)
            : limitedProfileName(sourceName);
        if (upsideDown) {
            hideFlipNametag(observer, name);
        }
        UserProfile userProfile = new UserProfile(state.fakeUuid, name);
        if (profile != null && profile.textureValue() != null && !profile.textureValue().isEmpty()) {
            String signature = profile.textureSignature() == null || profile.textureSignature().isEmpty() ? null : profile.textureSignature();
            userProfile.getTextureProperties().add(new TextureProperty("textures", profile.textureValue(), signature));
        }
        WrapperPlayServerPlayerInfoUpdate.PlayerInfo info = new WrapperPlayServerPlayerInfoUpdate.PlayerInfo(
            userProfile, false, 0, GameMode.SURVIVAL, null, null, 0, true);
        sendCounted(observer, new WrapperPlayServerPlayerInfoUpdate(
            EnumSet.of(WrapperPlayServerPlayerInfoUpdate.Action.ADD_PLAYER,
                WrapperPlayServerPlayerInfoUpdate.Action.UPDATE_GAME_MODE,
                WrapperPlayServerPlayerInfoUpdate.Action.UPDATE_LISTED,
                WrapperPlayServerPlayerInfoUpdate.Action.UPDATE_LATENCY,
                WrapperPlayServerPlayerInfoUpdate.Action.UPDATE_HAT),
            info));
    }

    private EntityType packetEntityTypeByKey(String typeKey) {
        if (typeKey == null || typeKey.isBlank()) {
            return null;
        }
        return EntityTypes.getByName(typeKey);
    }

    private static boolean isLivingType(String typeKey) {
        if (typeKey == null || typeKey.isBlank()) {
            return false;
        }
        try {
            NamespacedKey key = NamespacedKey.fromString(typeKey.toLowerCase(java.util.Locale.ROOT));
            if (key == null) {
                return false;
            }
            org.bukkit.entity.EntityType bukkitType = org.bukkit.Registry.ENTITY_TYPE.get(key);
            if (bukkitType == null) {
                return false;
            }
            Class<? extends Entity> entityClass = bukkitType.getEntityClass();
            return entityClass != null && LivingEntity.class.isAssignableFrom(entityClass);
        } catch (Throwable ignored) {
            return false;
        }
    }

    public void close(Player observer) {
        restoreAllRequested = true;
        try {
            if (observer != null && observer.isOnline()) {
                beginBatch(observer);
                try {
                    destroySpoofedEntities(observer, spoofed.values());
                } finally {
                    endBatch();
                }
            }
        } finally {
            restoreAllLocalEntities(observer);
            spoofed.clear();
            visible.clear();
            visibleLocalHides.clear();
            publishedSpoofedCount = 0;
        }
    }

    public void discard(Player observer) {
        restoreAllRequested = true;
        restoreAllLocalEntities(observer);
        spoofed.clear();
        visible.clear();
        visibleLocalHides.clear();
        publishedSpoofedCount = 0;
    }

    public boolean hasProjectedEntity(UUID sourceId) {
        return spoofed.containsKey(sourceId);
    }

    public void sendAnimation(Player observer, UUID sourceId, EntityAnimationType type) {
        if (observer == null || !observer.isOnline() || sourceId == null || type == null) {
            return;
        }
        SpoofedEntity state = spoofed.get(sourceId);
        if (state == null || !state.living) {
            // Swing/hurt animations are LivingEntity-only on the client (handleAnimate casts to
            // LivingEntity); sending one for a projected non-living entity (e.g. an arrow) crashes
            // the viewer with a ClassCastException.
            return;
        }
        sendCounted(observer, new WrapperPlayServerEntityAnimation(state.fakeId, type));
    }

    public void sendHurt(Player observer, UUID sourceId, float yaw) {
        if (observer == null || !observer.isOnline() || sourceId == null) {
            return;
        }
        SpoofedEntity state = spoofed.get(sourceId);
        if (state == null || !state.living) {
            return;
        }
        sendCounted(observer, new WrapperPlayServerEntityAnimation(state.fakeId, EntityAnimationType.HURT));
        sendCounted(observer, new WrapperPlayServerHurtAnimation(state.fakeId, yaw));
    }

    private boolean projectEntity(Player observer,
                                  ILocalPortal localPortal,
                                  ILocalPortal remotePortal,
                                  PortalFrame localViewFrame,
                                  PortalFrame remoteViewFrame,
                                  Frustum4D frustum,
                                  Entity entity,
                                  boolean upsideDown,
                                  int mirrorRotationQuarterTurns) {
        EntityType packetType = packetEntityType(entity);
        if (packetType == null) {
            return false;
        }

        boolean mirror = remotePortal == localPortal;
        PortalFrame mirrorPlaneFrame = mirror ? localPortal.getFrame() : null;
        Vector mirrorPlaneOrigin = mirror ? localPortal.getOrigin() : null;

        WormholesPlatform.entityPosition(entity, scratchEntityPosition);
        double entityX = scratchEntityPosition[0];
        double entityZ = scratchEntityPosition[2];
        double halfHeight = entity.getHeight() * 0.5D;
        double visibleY = scratchEntityPosition[1] + halfHeight;
        if (mirror) {
            PortalCoordMap.mirrorSourceToDisplayPointInto(entityX, visibleY, entityZ,
                mirrorPlaneOrigin.getX(), mirrorPlaneOrigin.getY(), mirrorPlaneOrigin.getZ(),
                mirrorPlaneFrame, mirrorRotationQuarterTurns, scratchVisiblePoint);
        } else {
            PortalCoordMap.transformPointInto(entityX, visibleY, entityZ,
                remotePortal.getOrigin().getX(), remotePortal.getOrigin().getY(), remotePortal.getOrigin().getZ(),
                localPortal.getOrigin().getX(), localPortal.getOrigin().getY(), localPortal.getOrigin().getZ(),
                remoteViewFrame, localViewFrame, scratchVisiblePoint);
        }

        if (!frustum.containsPrimitive(scratchVisiblePoint[0], scratchVisiblePoint[1], scratchVisiblePoint[2])) {
            return false;
        }

        lookDirectionInto((float) scratchEntityPosition[3], (float) scratchEntityPosition[4], scratchLook);
        if (mirror) {
            PortalCoordMap.mirrorSourceToDisplayVectorInto(scratchLook[0], scratchLook[1], scratchLook[2],
                mirrorPlaneFrame, mirrorRotationQuarterTurns, scratchDirection);
        } else {
            remoteViewFrame.transformVectorInto(scratchLook[0], scratchLook[1], scratchLook[2], localViewFrame, scratchDirection);
        }
        float yaw = yaw(scratchDirection[0], scratchDirection[2]);
        float pitch = pitch(scratchDirection[0], scratchDirection[1], scratchDirection[2]);
        double visualBaseY = scratchVisiblePoint[1] - halfHeight;
        Vector3d position = new Vector3d(scratchVisiblePoint[0], visualBaseY, scratchVisiblePoint[2]);
        Vector3d velocity = mirror
            ? mirroredVelocity(entity, mirrorPlaneFrame, mirrorRotationQuarterTurns)
            : transformedVelocity(entity, remoteViewFrame, localViewFrame);

        SpoofedEntity state = spoofed.get(entity.getUniqueId());
        if (state != null && state.upsideDown != upsideDown) {
            destroySingleSpoof(observer, state);
            spoofed.remove(entity.getUniqueId());
            state = null;
        }
        if (state == null) {
            boolean playerEntity = entity instanceof Player;
            state = new SpoofedEntity(NEXT_FAKE_ID.getAndIncrement(), UUID.randomUUID(), playerEntity, upsideDown,
                entity instanceof LivingEntity);
            spoofed.put(entity.getUniqueId(), state);
            if (playerEntity) {
                sendPlayerInfo(observer, (Player) entity, state, upsideDown);
            }
            WrapperPlayServerSpawnEntity spawn = new WrapperPlayServerSpawnEntity(state.fakeId, Optional.of(state.fakeUuid),
                packetType, position, pitch, yaw, yaw, 0, Optional.of(velocity));
            sendCounted(observer, spawn);
            state.updateRotation(yaw, pitch);
            state.rememberPosition(position);
            sendHeadLook(observer, state, yaw);
            sendEntityState(observer, entity, state, true);
            state.resetMetadataCooldown();
            return true;
        }

        EntityMove move = state.updatePosition(position);
        boolean rotationChanged = state.updateRotation(yaw, pitch);
        sendEntityMovement(observer, state, move, rotationChanged, position, yaw, pitch, entity.isOnGround());
        if (rotationChanged) {
            sendHeadLook(observer, state, yaw);
        }
        if (state.updateVelocity(velocity)) {
            sendCounted(observer, new WrapperPlayServerEntityVelocity(state.fakeId, velocity));
        }
        if (state.shouldRefreshMetadata()) {
            sendEntityState(observer, entity, state, false);
            state.resetMetadataCooldown();
        }
        return true;
    }

    private void sendEntityMovement(Player observer,
                                    SpoofedEntity state,
                                    EntityMove move,
                                    boolean rotationChanged,
                                    Vector3d position,
                                    float yaw,
                                    float pitch,
                                    boolean onGround) {
        if (move.moved) {
            if (move.relative) {
                if (rotationChanged) {
                    sendCounted(observer,
                        new WrapperPlayServerEntityRelativeMoveAndRotation(state.fakeId, move.deltaX, move.deltaY, move.deltaZ, yaw, pitch, onGround));
                } else {
                    sendCounted(observer,
                        new WrapperPlayServerEntityRelativeMove(state.fakeId, move.deltaX, move.deltaY, move.deltaZ, onGround));
                }
                return;
            }
            sendCounted(observer,
                new WrapperPlayServerEntityTeleport(state.fakeId, position, yaw, pitch, onGround));
            return;
        }
        if (rotationChanged) {
            sendEntityRotation(observer, state, yaw, pitch, onGround);
        }
    }

    static void lookDirectionInto(float yaw, float pitch, double[] out) {
        double pitchRadians = Math.toRadians(pitch);
        double yawRadians = Math.toRadians(yaw);
        double horizontal = Math.cos(pitchRadians);
        out[0] = -horizontal * Math.sin(yawRadians);
        out[1] = -Math.sin(pitchRadians);
        out[2] = horizontal * Math.cos(yawRadians);
    }

    private void sendHeadLook(Player observer, SpoofedEntity state, float yaw) {
        sendCounted(observer, new WrapperPlayServerEntityHeadLook(state.fakeId, yaw));
    }

    private void sendEntityRotation(Player observer, SpoofedEntity state, float yaw, float pitch, boolean onGround) {
        sendCounted(observer, new WrapperPlayServerEntityRotation(state.fakeId, yaw, pitch, onGround));
    }

    private void destroyHidden(Player observer) {
        if (spoofed.isEmpty()) {
            return;
        }

        int[] ids = null;
        List<UUID> playerInfos = null;
        int count = 0;
        Iterator<Map.Entry<UUID, SpoofedEntity>> iterator = spoofed.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<UUID, SpoofedEntity> entry = iterator.next();
            if (visible.contains(entry.getKey())) {
                continue;
            }
            SpoofedEntity state = entry.getValue();
            if (ids == null) {
                ids = new int[spoofed.size()];
                playerInfos = new ArrayList<UUID>(4);
            }
            ids[count] = state.fakeId;
            count++;
            if (state.playerEntry) {
                playerInfos.add(state.fakeUuid);
            }
            if (Settings.DEBUG) {
                Wormholes.v("[spoof] CULL " + (state.playerEntry ? "player" : "entity") + " src=" + entry.getKey() + " fakeId=" + state.fakeId + " -> " + observer.getName() + " (no longer in view)");
            }
            iterator.remove();
        }

        if (count == 0) {
            return;
        }

        int[] trimmed = count == ids.length ? ids : Arrays.copyOf(ids, count);
        sendCounted(observer, new WrapperPlayServerDestroyEntities(trimmed));
        if (!playerInfos.isEmpty()) {
            sendCounted(observer, new WrapperPlayServerPlayerInfoRemove(playerInfos));
        }
    }

    private void destroySpoofedEntities(Player observer, Iterable<SpoofedEntity> states) {
        List<UUID> players = new ArrayList<UUID>();
        int size = spoofed.size();
        if (size > 0) {
            int[] ids = new int[size];
            int index = 0;
            for (SpoofedEntity state : states) {
                ids[index] = state.fakeId;
                index++;
                if (state.playerEntry) {
                    players.add(state.fakeUuid);
                }
            }
            sendCounted(observer, new WrapperPlayServerDestroyEntities(ids));
        }
        if (!players.isEmpty()) {
            sendCounted(observer, new WrapperPlayServerPlayerInfoRemove(players));
        }
    }

    private void sendEntityState(Player observer, Entity entity, SpoofedEntity state, boolean force) {
        EntityStateSnapshot snapshot = entityStateSnapshot(entity);
        sendEntityMetadata(observer, entity, state, snapshot, force);
        sendEntityEquipment(observer, state, snapshot, force);
    }

    private EntityStateSnapshot entityStateSnapshot(Entity entity) {
        long now = System.currentTimeMillis();
        sweepStaticCaches(now);
        UUID entityId = entity.getUniqueId();
        EntityStateSnapshot cached = ENTITY_STATE_CACHE.get(entityId);
        if (cached != null && now - cached.stampMillis <= ENTITY_STATE_REFRESH_MILLIS) {
            return cached;
        }
        List<EntityData<?>> metadata = List.of();
        String metadataSig = "";
        if (!metadataBridgeFailed) {
            try {
                metadata = SpigotConversionUtil.getEntityMetadata(entity);
                metadataSig = metadataSignature(metadata);
            } catch (RuntimeException ex) {
                metadataBridgeFailed = true;
                metadata = List.of();
                metadataSig = "";
                Wormholes.w("[ProjectedEntityRenderer] disabled entity metadata bridge after failure for " + entity.getType() + " " + entity.getUniqueId());
                ex.printStackTrace();
            }
        }
        List<Equipment> equipment = PacketBlobs.collectEquipment(entity);
        String equipmentSig = equipment.isEmpty() ? "" : equipmentSignature(equipment);
        EntityStateSnapshot next = new EntityStateSnapshot(now, metadata, metadataSig, equipment, equipmentSig);
        ENTITY_STATE_CACHE.put(entityId, next);
        return next;
    }

    private void sendEntityMetadata(Player observer, Entity entity, SpoofedEntity state, EntityStateSnapshot snapshot, boolean force) {
        if (metadataBridgeFailed) {
            return;
        }
        List<EntityData<?>> metadata = snapshot.metadata;
        String signature = snapshot.metadataSig;
        if (state.upsideDown) {
            metadata = withUpsideDownMetadata(entity, metadata);
            signature = metadataSignature(metadata);
        }
        if (metadata.isEmpty()) {
            return;
        }
        if (!force && signature.equals(state.lastMetadataSignature)) {
            return;
        }
        state.lastMetadataSignature = signature;
        sendCounted(observer, new WrapperPlayServerEntityMetadata(state.fakeId, metadata));
    }

    private static String metadataSignature(List<EntityData<?>> metadata) {
        StringBuilder builder = new StringBuilder(metadata.size() * 8);
        for (EntityData<?> data : metadata) {
            builder.append(data.getIndex()).append('=').append(String.valueOf(data.getValue())).append(';');
        }
        return builder.toString();
    }

    private List<EntityData<?>> withUpsideDownMetadata(Entity entity, List<EntityData<?>> metadata) {
        if (entity instanceof Player) {
            List<EntityData<?>> patched = new ArrayList<EntityData<?>>(metadata.size() + 1);
            byte skinParts = 0;
            for (EntityData<?> data : metadata) {
                if (data.getIndex() == PLAYER_SKIN_PARTS_INDEX && data.getValue() instanceof Byte) {
                    skinParts = ((Byte) data.getValue()).byteValue();
                    continue;
                }
                patched.add(data);
            }
            patched.add(new EntityData<Byte>(PLAYER_SKIN_PARTS_INDEX, EntityDataTypes.BYTE, Byte.valueOf((byte) (skinParts | CAPE_PART_BIT))));
            return patched;
        }
        if (!(entity instanceof LivingEntity)) {
            return metadata;
        }
        List<EntityData<?>> patched = new ArrayList<EntityData<?>>(metadata.size() + 2);
        for (EntityData<?> data : metadata) {
            if (data.getIndex() == CUSTOM_NAME_INDEX || data.getIndex() == CUSTOM_NAME_VISIBLE_INDEX) {
                continue;
            }
            patched.add(data);
        }
        if (!isFlipName(entity.getCustomName())) {
            patched.add(new EntityData<Optional<Component>>(CUSTOM_NAME_INDEX, EntityDataTypes.OPTIONAL_ADV_COMPONENT, Optional.of(Component.text(FLIP_NAME))));
            patched.add(new EntityData<Boolean>(CUSTOM_NAME_VISIBLE_INDEX, EntityDataTypes.BOOLEAN, Boolean.FALSE));
        }
        return patched;
    }

    private static boolean isFlipName(String name) {
        return FLIP_NAME.equals(name) || FLIP_NAME_ALT.equals(name);
    }

    private void sendEntityEquipment(Player observer, SpoofedEntity state, EntityStateSnapshot snapshot, boolean force) {
        if (snapshot.equipment.isEmpty()) {
            return;
        }
        if (!force && snapshot.equipmentSig.equals(state.lastEquipmentSignature)) {
            return;
        }
        state.lastEquipmentSignature = snapshot.equipmentSig;
        sendCounted(observer, new WrapperPlayServerEntityEquipment(state.fakeId, snapshot.equipment));
    }

    private static String equipmentSignature(List<Equipment> equipment) {
        StringBuilder builder = new StringBuilder(equipment.size() * 8);
        for (Equipment item : equipment) {
            builder.append(item.getSlot()).append('=').append(String.valueOf(item.getItem())).append(';');
        }
        return builder.toString();
    }

    private void sendPlayerInfo(Player observer, Player player, SpoofedEntity state, boolean upsideDown) {
        String name = profileName(player, upsideDown);
        if (upsideDown) {
            hideFlipNametag(observer, name);
        }
        UserProfile userProfile = new UserProfile(state.fakeUuid, name);
        try {
            UserProfile sourceProfile = PacketEvents.getAPI().getPlayerManager().getUser(player).getProfile();
            if (sourceProfile != null) {
                for (TextureProperty property : sourceProfile.getTextureProperties()) {
                    userProfile.getTextureProperties().add(new TextureProperty(property.getName(), property.getValue(), property.getSignature()));
                }
            }
        } catch (Throwable ignored) {
        }
        GameMode gameMode = SpigotConversionUtil.fromBukkitGameMode(player.getGameMode());
        WrapperPlayServerPlayerInfoUpdate.PlayerInfo info = new WrapperPlayServerPlayerInfoUpdate.PlayerInfo(
            userProfile, false, player.getPing(), gameMode, null, null, 0, true);
        sendCounted(observer, new WrapperPlayServerPlayerInfoUpdate(
            EnumSet.of(WrapperPlayServerPlayerInfoUpdate.Action.ADD_PLAYER,
                WrapperPlayServerPlayerInfoUpdate.Action.UPDATE_GAME_MODE,
                WrapperPlayServerPlayerInfoUpdate.Action.UPDATE_LISTED,
                WrapperPlayServerPlayerInfoUpdate.Action.UPDATE_LATENCY,
                WrapperPlayServerPlayerInfoUpdate.Action.UPDATE_HAT),
            info));
    }

    private String profileName(Player player, boolean upsideDown) {
        String name = player.getName();
        if (upsideDown) {
            return isFlipName(name) ? NEUTRAL_PROFILE_NAME : FLIP_NAME;
        }
        return limitedProfileName(name);
    }

    private String limitedProfileName(String name) {
        String safe = name == null || name.isBlank() ? NEUTRAL_PROFILE_NAME : name;
        if (safe.length() <= 16) {
            return safe;
        }
        return safe.substring(0, 16);
    }

    private void hideFlipNametag(Player observer, String name) {
        ensureFlipTeam(observer);
        if (name == null || name.isEmpty() || !flipTeamMembers.add(name)) {
            return;
        }
        sendCounted(observer, new WrapperPlayServerTeams(FLIP_TEAM_NAME,
            WrapperPlayServerTeams.TeamMode.ADD_ENTITIES, Optional.<WrapperPlayServerTeams.ScoreBoardTeamInfo>empty(), name));
    }

    private void ensureFlipTeam(Player observer) {
        if (flipTeamSent) {
            return;
        }
        WrapperPlayServerTeams.ScoreBoardTeamInfo info = new WrapperPlayServerTeams.ScoreBoardTeamInfo(
            Component.empty(), Component.empty(), Component.empty(),
            WrapperPlayServerTeams.NameTagVisibility.NEVER,
            WrapperPlayServerTeams.CollisionRule.NEVER,
            NamedTextColor.WHITE,
            WrapperPlayServerTeams.OptionData.NONE);
        sendCounted(observer, new WrapperPlayServerTeams(FLIP_TEAM_NAME, WrapperPlayServerTeams.TeamMode.CREATE, info, FLIP_NAME, FLIP_NAME_ALT));
        flipTeamMembers.add(FLIP_NAME);
        flipTeamMembers.add(FLIP_NAME_ALT);
        flipTeamSent = true;
    }

    private void destroySingleSpoof(Player observer, SpoofedEntity state) {
        sendCounted(observer, new WrapperPlayServerDestroyEntities(state.fakeId));
        if (state.playerEntry) {
            sendCounted(observer, new WrapperPlayServerPlayerInfoRemove(List.of(state.fakeUuid)));
        }
    }

    private void hideLocalEntities(Player observer, ILocalPortal localPortal, Frustum4D frustum, double range, double projectionDepth) {
        restoreAllRequested = false;
        if (Wormholes.instance == null || observer == null || !observer.isOnline()) {
            return;
        }
        Location localCenter = localPortal.getCenter();
        World localWorld = localPortal.getWorld();
        if (localCenter == null || localWorld == null || !localWorld.equals(observer.getWorld())) {
            return;
        }
        PortalFrame frame = localPortal.getFrame();
        Vector origin = localPortal.getOrigin();
        WormholesPlatform.entityPosition(observer, scratchEntityPosition);
        double eyeX = scratchEntityPosition[0];
        double eyeY = scratchEntityPosition[1] + observer.getEyeHeight();
        double eyeZ = scratchEntityPosition[2];
        double eyeDot = dot(eyeX - origin.getX(), eyeY - origin.getY(), eyeZ - origin.getZ(), frame);
        boolean eyeFrontSide = eyeDot >= 0.0D;
        double clearance = PortalProjector.portalPlaneClearance(localPortal.getStructure().getArea(), frame);
        double maxDepth = projectionDepth + clearance;
        double ownedRange = largestOwnedLocalEntityRange(localWorld, localCenter, range);
        if (ownedRange <= 0.0D) {
            return;
        }
        Collection<Entity> candidates;
        try {
            candidates = nearbyLocalEntities(localPortal, localCenter, ownedRange);
        } catch (IllegalStateException error) {
            reportLocalHideOwnershipFailure(error);
            return;
        }
        for (Entity entity : candidates) {
            try {
                if (!shouldHideLocalEntity(observer, entity, origin, frame, frustum, eyeFrontSide, clearance, maxDepth)) {
                    continue;
                }
                visibleLocalHides.add(entity.getUniqueId());
                if (hiddenLocalEntities.containsKey(entity.getUniqueId())) {
                    hiddenLocalEntities.put(entity.getUniqueId(), entity);
                    continue;
                }
                observer.hideEntity(Wormholes.instance, entity);
                hiddenLocalEntities.put(entity.getUniqueId(), entity);
            } catch (IllegalStateException error) {
                reportLocalHideOwnershipFailure(error);
            }
        }
    }

    private static double largestOwnedLocalEntityRange(World world, Location center, double requestedRange) {
        int radius = Math.max(1, (int) Math.ceil(requestedRange));
        while (radius >= 1) {
            int minChunkX = ((int) Math.floor(center.getX() - radius)) >> 4;
            int minChunkZ = ((int) Math.floor(center.getZ() - radius)) >> 4;
            int maxChunkX = ((int) Math.floor(center.getX() + radius)) >> 4;
            int maxChunkZ = ((int) Math.floor(center.getZ() + radius)) >> 4;
            if (WormholesPlatform.isOwnedByCurrentRegion(world, minChunkX, minChunkZ, maxChunkX, maxChunkZ)) {
                return Math.min(requestedRange, radius);
            }
            if (radius == 1) {
                return 0.0D;
            }
            radius = Math.max(1, radius / 2);
        }
        return 0.0D;
    }

    private void reportLocalHideOwnershipFailure(IllegalStateException error) {
        if (localHideOwnershipWarningSent) {
            return;
        }
        localHideOwnershipWarningSent = true;
        Wormholes.w("[spoof] Local entity occlusion crossed an unowned Folia region; this projection will keep rendering without local occlusion.");
        error.printStackTrace();
    }

    private boolean shouldHideLocalEntity(Player observer,
                                          Entity entity,
                                          Vector origin,
                                          PortalFrame frame,
                                          Frustum4D frustum,
                                          boolean eyeFrontSide,
                                          double clearance,
                                          double maxDepth) {
        if (entity == null || entity.isDead() || !entity.isValid()) {
            return false;
        }
        if (entity.getUniqueId().equals(observer.getUniqueId())) {
            return false;
        }
        WormholesPlatform.entityPosition(entity, scratchEntityPosition);
        double entityX = scratchEntityPosition[0];
        double entityZ = scratchEntityPosition[2];
        double centerY = scratchEntityPosition[1] + (entity.getHeight() * 0.5D);
        double signedDistance = dot(entityX - origin.getX(), centerY - origin.getY(), entityZ - origin.getZ(), frame);
        if (Math.abs(signedDistance) <= clearance || Math.abs(signedDistance) > maxDepth) {
            return false;
        }
        boolean entityFrontSide = signedDistance >= 0.0D;
        if (entityFrontSide == eyeFrontSide) {
            return false;
        }
        return frustum.containsPrimitive(entityX, centerY, entityZ);
    }

    private void restoreLocalEntities(Player observer) {
        if (hiddenLocalEntities.isEmpty() || observer == null || !observer.isOnline() || Wormholes.instance == null) {
            return;
        }
        Iterator<Map.Entry<UUID, Entity>> iterator = hiddenLocalEntities.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<UUID, Entity> entry = iterator.next();
            if (visibleLocalHides.contains(entry.getKey())) {
                continue;
            }
            Entity entity = entry.getValue();
            try {
                if (entity != null && entity.isValid() && !entity.isDead()) {
                    observer.showEntity(Wormholes.instance, entity);
                }
            } catch (IllegalStateException error) {
                reportLocalHideOwnershipFailure(error);
                continue;
            }
            iterator.remove();
        }
    }

    private void restoreAllLocalEntities(Player observer) {
        if (hiddenLocalEntities.isEmpty()) {
            return;
        }
        if (observer == null || !observer.isOnline()) {
            hiddenLocalEntities.clear();
            return;
        }
        if (Wormholes.instance == null || !FoliaScheduler.isOwnedByCurrentRegion(observer)) {
            scheduleLocalEntityRestore(observer);
            return;
        }
        if (!showAllLocalEntities(observer)) {
            scheduleLocalEntityRestore(observer);
        }
    }

    private boolean showAllLocalEntities(Player observer) {
        return removeCompletedRestores(hiddenLocalEntities, entity -> tryShowLocalEntity(observer, entity));
    }

    private boolean tryShowLocalEntity(Player observer, Entity entity) {
        try {
            if (entity != null && entity.isValid() && !entity.isDead()) {
                observer.showEntity(Wormholes.instance, entity);
            }
            return true;
        } catch (IllegalStateException error) {
            reportLocalHideOwnershipFailure(error);
            return false;
        }
    }

    private void scheduleLocalEntityRestore(Player observer) {
        if (hiddenLocalEntities.isEmpty() || observer == null || !observer.isOnline()
            || Wormholes.instance == null || !localRestoreRetryScheduled.compareAndSet(false, true)) {
            return;
        }
        boolean scheduled = FoliaScheduler.runEntity(Wormholes.instance, observer, () -> {
            localRestoreRetryScheduled.set(false);
            if (restoreAllRequested) {
                restoreAllLocalEntities(observer);
            } else {
                restoreLocalEntities(observer);
            }
        }, 1L);
        if (!scheduled) {
            localRestoreRetryScheduled.set(false);
        }
    }

    static <K, V> boolean removeCompletedRestores(Map<K, V> pending, Predicate<V> completed) {
        Iterator<Map.Entry<K, V>> iterator = pending.entrySet().iterator();
        while (iterator.hasNext()) {
            if (completed.test(iterator.next().getValue())) {
                iterator.remove();
            }
        }
        return pending.isEmpty();
    }

    private double dot(double x, double y, double z, PortalFrame frame) {
        return (x * frame.getNormal().x()) + (y * frame.getNormal().y()) + (z * frame.getNormal().z());
    }

	private boolean canSpoof(Entity entity) {
		if (entity == null || entity.isDead() || EffectManager.isPortalEffectEntity(entity)) {
            return false;
        }
        return entity.isValid();
    }

    private static Collection<Entity> nearbyRemoteEntities(ILocalPortal portal, Location center, double range) {
        return nearbyEntities(REMOTE_ENTITY_CACHE, portal, center, range);
    }

    private static Collection<Entity> nearbyLocalEntities(ILocalPortal portal, Location center, double range) {
        return nearbyEntities(LOCAL_ENTITY_CACHE, portal, center, range);
    }

    private static Collection<Entity> nearbyEntities(Map<UUID, EntityCandidateSnapshot> cache, ILocalPortal portal, Location center, double range) {
        if (portal == null || portal.getId() == null || center == null || center.getWorld() == null) {
            return List.of();
        }
        long now = System.currentTimeMillis();
        sweepStaticCaches(now);
        EntityCandidateSnapshot snapshot = cache.get(portal.getId());
        long maxAgeMillis = Math.max(1, Settings.ENTITY_CANDIDATE_CACHE_TICKS) * 50L;
        if (snapshot != null && snapshot.matches(center, range) && now - snapshot.createdAtMillis <= maxAgeMillis) {
            return snapshot.entities;
        }
        Collection<Entity> entities = center.getWorld().getNearbyEntities(center, range, range, range);
        EntityCandidateSnapshot next = new EntityCandidateSnapshot(center, range, now, new ArrayList<Entity>(entities));
        cache.put(portal.getId(), next);
        return next.entities;
    }

    private static void sweepStaticCaches(long now) {
        long due = STATIC_CACHE_SWEEP_DUE.get();
        if (now < due || !STATIC_CACHE_SWEEP_DUE.compareAndSet(due, now + STATIC_CACHE_EVICT_MILLIS)) {
            return;
        }
        REMOTE_ENTITY_CACHE.values().removeIf(candidate -> now - candidate.createdAtMillis > STATIC_CACHE_EVICT_MILLIS);
        LOCAL_ENTITY_CACHE.values().removeIf(candidate -> now - candidate.createdAtMillis > STATIC_CACHE_EVICT_MILLIS);
        ENTITY_STATE_CACHE.values().removeIf(snapshot -> now - snapshot.stampMillis > STATIC_CACHE_EVICT_MILLIS);
    }

    private EntityType packetEntityType(Entity entity) {
        NamespacedKey key = entity.getType().getKey();
        if (key == null || "unknown".equals(key.getKey())) {
            return null;
        }
        EntityType cached = entityTypeCache.get(key);
        if (cached != null) {
            return cached;
        }
        EntityType resolved = EntityTypes.getByName(key.getNamespace() + ":" + key.getKey());
        if (resolved != null) {
            entityTypeCache.put(key, resolved);
        }
        return resolved;
    }

    private Vector3d transformedVelocity(Entity entity, PortalFrame fromFrame, PortalFrame toFrame) {
        Vector velocity = entity.getVelocity();
        fromFrame.transformVectorInto(velocity.getX(), velocity.getY(), velocity.getZ(), toFrame, scratchDirection);
        return new Vector3d(scratchDirection[0], scratchDirection[1], scratchDirection[2]);
    }

    private Vector3d mirroredVelocity(Entity entity, PortalFrame planeFrame, int mirrorRotationQuarterTurns) {
        Vector velocity = entity.getVelocity();
        PortalCoordMap.mirrorSourceToDisplayVectorInto(velocity.getX(), velocity.getY(), velocity.getZ(), planeFrame,
            mirrorRotationQuarterTurns, scratchDirection);
        return new Vector3d(scratchDirection[0], scratchDirection[1], scratchDirection[2]);
    }

    private static float yaw(double x, double z) {
        return (float) Math.toDegrees(Math.atan2(-x, z));
    }

    private static float pitch(double x, double y, double z) {
        double horizontal = Math.sqrt(x * x + z * z);
        return (float) Math.toDegrees(-Math.atan2(y, horizontal));
    }

    private static float angleDelta(float a, float b) {
        float delta = (a - b) % 360.0F;
        if (delta >= 180.0F) {
            delta -= 360.0F;
        }
        if (delta < -180.0F) {
            delta += 360.0F;
        }
        return Math.abs(delta);
    }

    private static final class SpoofedEntity {
        private final int fakeId;
        private final UUID fakeUuid;
        private final boolean playerEntry;
        private final boolean upsideDown;
        private final boolean living;
        private int leashedToFakeId = Integer.MIN_VALUE;
        private int[] lastPassengers;
        private int remoteStateVersion = -1;
        private float yaw;
        private float pitch;
        private double velocityX;
        private double velocityY;
        private double velocityZ;
        private double x;
        private double y;
        private double z;
        private boolean rotationKnown;
        private boolean velocityKnown;
        private boolean positionKnown;
        private int metadataRefreshPasses;
        private String lastMetadataSignature;
        private String lastEquipmentSignature;

        private SpoofedEntity(int fakeId, UUID fakeUuid, boolean playerEntry, boolean upsideDown, boolean living) {
            this.fakeId = fakeId;
            this.fakeUuid = fakeUuid;
            this.playerEntry = playerEntry;
            this.upsideDown = upsideDown;
            this.living = living;
            this.yaw = 0.0F;
            this.pitch = 0.0F;
            this.velocityX = 0.0D;
            this.velocityY = 0.0D;
            this.velocityZ = 0.0D;
            this.x = 0.0D;
            this.y = 0.0D;
            this.z = 0.0D;
            this.rotationKnown = false;
            this.velocityKnown = false;
            this.positionKnown = false;
            this.metadataRefreshPasses = METADATA_REFRESH_PASSES;
        }

        private void rememberPosition(Vector3d position) {
            x = position.getX();
            y = position.getY();
            z = position.getZ();
            positionKnown = true;
        }

        private EntityMove updatePosition(Vector3d position) {
            double nextX = position.getX();
            double nextY = position.getY();
            double nextZ = position.getZ();
            if (!positionKnown) {
                x = nextX;
                y = nextY;
                z = nextZ;
                positionKnown = true;
                return EntityMove.teleport();
            }

            double deltaX = nextX - x;
            double deltaY = nextY - y;
            double deltaZ = nextZ - z;
            double distanceSquared = (deltaX * deltaX) + (deltaY * deltaY) + (deltaZ * deltaZ);
            x = nextX;
            y = nextY;
            z = nextZ;
            if (distanceSquared <= MIN_POSITION_DELTA_SQUARED) {
                return EntityMove.none();
            }
            if (Math.abs(deltaX) > MAX_RELATIVE_MOVE_DELTA || Math.abs(deltaY) > MAX_RELATIVE_MOVE_DELTA || Math.abs(deltaZ) > MAX_RELATIVE_MOVE_DELTA) {
                return EntityMove.teleport();
            }
            return EntityMove.relative(deltaX, deltaY, deltaZ);
        }

        private boolean updateRotation(float yaw, float pitch) {
            if (rotationKnown && angleDelta(yaw, this.yaw) < 0.5F && Math.abs(pitch - this.pitch) < 0.5F) {
                return false;
            }
            this.yaw = yaw;
            this.pitch = pitch;
            rotationKnown = true;
            return true;
        }

        private boolean updateVelocity(Vector3d velocity) {
            double x = velocity.getX();
            double y = velocity.getY();
            double z = velocity.getZ();
            if (velocityKnown && Math.abs(x - velocityX) < 0.001D && Math.abs(y - velocityY) < 0.001D && Math.abs(z - velocityZ) < 0.001D) {
                return false;
            }
            velocityX = x;
            velocityY = y;
            velocityZ = z;
            velocityKnown = true;
            return true;
        }

        private boolean shouldRefreshMetadata() {
            metadataRefreshPasses--;
            return metadataRefreshPasses <= 0;
        }

        private void resetMetadataCooldown() {
            metadataRefreshPasses = METADATA_REFRESH_PASSES;
        }
    }

    private static final class EntityStateSnapshot {
        private final long stampMillis;
        private final List<EntityData<?>> metadata;
        private final String metadataSig;
        private final List<Equipment> equipment;
        private final String equipmentSig;

        private EntityStateSnapshot(long stampMillis, List<EntityData<?>> metadata, String metadataSig, List<Equipment> equipment, String equipmentSig) {
            this.stampMillis = stampMillis;
            this.metadata = metadata;
            this.metadataSig = metadataSig;
            this.equipment = equipment;
            this.equipmentSig = equipmentSig;
        }
    }

    private static final class EntityCandidateSnapshot {
        private final String worldKey;
        private final int centerBlockX;
        private final int centerBlockY;
        private final int centerBlockZ;
        private final double range;
        private final long createdAtMillis;
        private final List<Entity> entities;

        private EntityCandidateSnapshot(Location center, double range, long createdAtMillis, List<Entity> entities) {
            this.worldKey = WorldIdentity.serialize(center.getWorld());
            this.centerBlockX = center.getBlockX();
            this.centerBlockY = center.getBlockY();
            this.centerBlockZ = center.getBlockZ();
            this.range = range;
            this.createdAtMillis = createdAtMillis;
            this.entities = entities;
        }

        private boolean matches(Location center, double range) {
            return worldKey.equals(WorldIdentity.serialize(center.getWorld()))
                && centerBlockX == center.getBlockX()
                && centerBlockY == center.getBlockY()
                && centerBlockZ == center.getBlockZ()
                && Math.abs(this.range - range) < 0.001D;
        }
    }

    private static final class EntityMove {
        private static final EntityMove NONE = new EntityMove(false, false, 0.0D, 0.0D, 0.0D);
        private static final EntityMove TELEPORT = new EntityMove(true, false, 0.0D, 0.0D, 0.0D);

        private final boolean moved;
        private final boolean relative;
        private final double deltaX;
        private final double deltaY;
        private final double deltaZ;

        private EntityMove(boolean moved, boolean relative, double deltaX, double deltaY, double deltaZ) {
            this.moved = moved;
            this.relative = relative;
            this.deltaX = deltaX;
            this.deltaY = deltaY;
            this.deltaZ = deltaZ;
        }

        private static EntityMove none() {
            return NONE;
        }

        private static EntityMove teleport() {
            return TELEPORT;
        }

        private static EntityMove relative(double deltaX, double deltaY, double deltaZ) {
            return new EntityMove(true, true, deltaX, deltaY, deltaZ);
        }
    }
}
