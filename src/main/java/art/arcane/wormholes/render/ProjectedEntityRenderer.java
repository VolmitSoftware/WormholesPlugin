package art.arcane.wormholes.render;

import java.util.ArrayList;
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
import java.util.concurrent.atomic.AtomicInteger;

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
import com.github.retrooper.packetevents.protocol.player.UserProfile;
import com.github.retrooper.packetevents.util.Vector3d;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerDestroyEntities;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityAnimation;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityAnimation.EntityAnimationType;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityEquipment;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityHeadLook;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityMetadata;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityRelativeMove;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityRelativeMoveAndRotation;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityRotation;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityTeleport;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityVelocity;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerHurtAnimation;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerPlayerInfoRemove;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerPlayerInfoUpdate;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSpawnEntity;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerTeams;
import io.github.retrooper.packetevents.util.SpigotConversionUtil;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;

import art.arcane.wormholes.Settings;
import art.arcane.wormholes.Wormholes;
import art.arcane.wormholes.network.view.EntityVisual;
import art.arcane.wormholes.network.view.PacketBlobs;
import art.arcane.wormholes.network.view.RemoteViewCache;
import art.arcane.wormholes.portal.ILocalPortal;
import art.arcane.wormholes.portal.PortalFrame;
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
    private static final int PLAYER_SKIN_PARTS_INDEX = 16;
    private static final byte CAPE_PART_BIT = 0x01;
    private static final double MIN_POSITION_DELTA_SQUARED = 1.0E-6D;
    private static final double MAX_RELATIVE_MOVE_DELTA = 7.75D;
    private static final Map<UUID, EntityCandidateSnapshot> REMOTE_ENTITY_CACHE = new HashMap<UUID, EntityCandidateSnapshot>();
    private static final Map<UUID, EntityCandidateSnapshot> LOCAL_ENTITY_CACHE = new HashMap<UUID, EntityCandidateSnapshot>();

    private final Map<UUID, SpoofedEntity> spoofed;
    private final Map<UUID, Entity> hiddenLocalEntities;
    private final Map<NamespacedKey, EntityType> entityTypeCache;
    private final Set<UUID> visible;
    private final Set<UUID> visibleLocalHides;
    private final double[] scratchVisiblePoint;
    private final double[] scratchDirection;
    private boolean metadataBridgeFailed;
    private boolean flipTeamSent;

    public ProjectedEntityRenderer() {
        this.spoofed = new HashMap<UUID, SpoofedEntity>(16);
        this.hiddenLocalEntities = new HashMap<UUID, Entity>(16);
        this.entityTypeCache = new HashMap<NamespacedKey, EntityType>(32);
        this.visible = new HashSet<UUID>(16);
        this.visibleLocalHides = new HashSet<UUID>(16);
        this.scratchVisiblePoint = new double[3];
        this.scratchDirection = new double[3];
        this.metadataBridgeFailed = false;
        this.flipTeamSent = false;
    }

    private static void sendCounted(Player observer, com.github.retrooper.packetevents.wrapper.PacketWrapper<?> packet) {
        WormholesTelemetry.countPacket();
        PacketEvents.getAPI().getPlayerManager().sendPacket(observer, packet);
    }

    public int getSpoofedCount() {
        return spoofed.size();
    }

    public void apply(Player observer,
                      ILocalPortal localPortal,
                      ILocalPortal remotePortal,
                      Frustum4D frustum,
                      double projectionDepth,
                      PortalFrame localViewFrame,
                      PortalFrame remoteViewFrame) {
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

        double range = Math.min(Settings.ENTITY_SPOOF_RANGE, projectionDepth);
        visible.clear();
        visibleLocalHides.clear();
        hideLocalEntities(observer, localPortal, frustum, range, projectionDepth);
        boolean upsideDown = remotePortal == localPortal
            ? PortalCoordMap.reflectionFlipsWorldUp(localPortal.getFrame())
            : PortalCoordMap.transformFlipsWorldUp(remoteViewFrame, localViewFrame);
        int count = 0;

        for (Entity entity : nearbyRemoteEntities(remotePortal, remoteCenter, range)) {
            if (count >= Settings.MAX_SPOOFED_ENTITIES) {
                break;
            }
            if (!canSpoof(entity)) {
                continue;
            }
            if (!projectEntity(observer, localPortal, remotePortal, localViewFrame, remoteViewFrame, frustum, entity, upsideDown)) {
                continue;
            }
            visible.add(entity.getUniqueId());
            count++;
        }

        destroyHidden(observer);
        restoreLocalEntities(observer);
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

        destroyHidden(observer);
        restoreLocalEntities(observer);
    }

    private boolean projectRemoteVisual(Player observer,
                                        ILocalPortal localPortal,
                                        double remoteOriginX,
                                        double remoteOriginY,
                                        double remoteOriginZ,
                                        PortalFrame localViewFrame,
                                        PortalFrame remoteViewFrame,
                                        Frustum4D frustum,
                                        RemoteWorldView remoteView,
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
            state.resetMetadataCooldown();
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
        if (stateVersion != state.remoteStateVersion || state.shouldRefreshMetadata()) {
            state.remoteStateVersion = stateVersion;
            sendRemoteEntityState(observer, remoteView, visual, state);
            state.resetMetadataCooldown();
        }
        return true;
    }

    private void sendRemoteEntityState(Player observer, RemoteWorldView remoteView, EntityVisual visual, SpoofedEntity state) {
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
        if (upsideDown) {
            ensureFlipTeam(observer);
        }
        String sourceName = profile == null ? null : profile.name();
        String name = upsideDown
            ? (isFlipName(sourceName) ? NEUTRAL_PROFILE_NAME : FLIP_NAME)
            : limitedProfileName(sourceName);
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
        if (observer != null && observer.isOnline()) {
            destroySpoofedEntities(observer, spoofed.values());
            showAllLocalEntities(observer);
        }
        spoofed.clear();
        hiddenLocalEntities.clear();
        visible.clear();
        visibleLocalHides.clear();
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
                                  boolean upsideDown) {
        EntityType packetType = packetEntityType(entity);
        if (packetType == null) {
            return false;
        }

        boolean mirror = remotePortal == localPortal;
        PortalFrame mirrorPlaneFrame = mirror ? localPortal.getFrame() : null;
        Vector mirrorPlaneOrigin = mirror ? localPortal.getOrigin() : null;

        Location remoteLocation = entity.getLocation();
        double visibleY = remoteLocation.getY() + (entity.getHeight() * 0.5D);
        if (mirror) {
            PortalCoordMap.reflectPointAcrossPlaneInto(remoteLocation.getX(), visibleY, remoteLocation.getZ(),
                mirrorPlaneOrigin.getX(), mirrorPlaneOrigin.getY(), mirrorPlaneOrigin.getZ(),
                mirrorPlaneFrame, scratchVisiblePoint);
        } else {
            PortalCoordMap.transformPointInto(remoteLocation.getX(), visibleY, remoteLocation.getZ(),
                remotePortal.getOrigin().getX(), remotePortal.getOrigin().getY(), remotePortal.getOrigin().getZ(),
                localPortal.getOrigin().getX(), localPortal.getOrigin().getY(), localPortal.getOrigin().getZ(),
                remoteViewFrame, localViewFrame, scratchVisiblePoint);
        }

        if (!frustum.containsPrimitive(scratchVisiblePoint[0], scratchVisiblePoint[1], scratchVisiblePoint[2])) {
            return false;
        }

        Vector direction = lookDirection(entity, remoteLocation);
        if (mirror) {
            PortalCoordMap.reflectVectorAcrossPlaneInto(direction.getX(), direction.getY(), direction.getZ(),
                mirrorPlaneFrame, scratchDirection);
        } else {
            remoteViewFrame.transformVectorInto(direction.getX(), direction.getY(), direction.getZ(), localViewFrame, scratchDirection);
        }
        float yaw = yaw(scratchDirection[0], scratchDirection[2]);
        float pitch = pitch(scratchDirection[0], scratchDirection[1], scratchDirection[2]);
        double visualBaseY = scratchVisiblePoint[1] - (entity.getHeight() * 0.5D);
        Vector3d position = new Vector3d(scratchVisiblePoint[0], visualBaseY, scratchVisiblePoint[2]);
        Vector3d velocity = mirror
            ? mirroredVelocity(entity, mirrorPlaneFrame)
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
            sendEntityState(observer, entity, state);
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
            sendEntityState(observer, entity, state);
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

    private Vector lookDirection(Entity entity, Location fallback) {
        if (entity instanceof LivingEntity) {
            return ((LivingEntity) entity).getEyeLocation().getDirection();
        }
        return fallback.getDirection();
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

        int[] ids = new int[spoofed.size()];
        List<UUID> playerInfos = new ArrayList<UUID>();
        int count = 0;
        Iterator<Map.Entry<UUID, SpoofedEntity>> iterator = spoofed.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<UUID, SpoofedEntity> entry = iterator.next();
            if (visible.contains(entry.getKey())) {
                continue;
            }
            SpoofedEntity state = entry.getValue();
            ids[count] = state.fakeId;
            count++;
            if (state.playerEntry) {
                playerInfos.add(state.fakeUuid);
            }
            iterator.remove();
        }

        if (count == 0) {
            return;
        }

        int[] trimmed = new int[count];
        System.arraycopy(ids, 0, trimmed, 0, count);
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

    private void sendEntityState(Player observer, Entity entity, SpoofedEntity state) {
        sendEntityMetadata(observer, entity, state.fakeId, state.upsideDown);
        sendEntityEquipment(observer, entity, state.fakeId);
    }

    private void sendEntityMetadata(Player observer, Entity entity, int fakeId, boolean upsideDown) {
        if (metadataBridgeFailed) {
            return;
        }
        try {
            List<EntityData<?>> metadata = SpigotConversionUtil.getEntityMetadata(entity);
            if (upsideDown) {
                metadata = withUpsideDownMetadata(entity, metadata);
            }
            if (!metadata.isEmpty()) {
                sendCounted(observer, new WrapperPlayServerEntityMetadata(fakeId, metadata));
            }
        } catch (RuntimeException ex) {
            metadataBridgeFailed = true;
            Wormholes.w("[ProjectedEntityRenderer] disabled entity metadata bridge after failure for " + entity.getType() + " " + entity.getUniqueId());
            ex.printStackTrace();
        }
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

    private void sendEntityEquipment(Player observer, Entity entity, int fakeId) {
        List<Equipment> packetEquipment = PacketBlobs.collectEquipment(entity);
        if (!packetEquipment.isEmpty()) {
            sendCounted(observer, new WrapperPlayServerEntityEquipment(fakeId, packetEquipment));
        }
    }

    private void sendPlayerInfo(Player observer, Player player, SpoofedEntity state, boolean upsideDown) {
        if (upsideDown) {
            ensureFlipTeam(observer);
        }
        UserProfile userProfile = new UserProfile(state.fakeUuid, profileName(player, upsideDown));
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
        sendCounted(observer, new WrapperPlayServerTeams(FLIP_TEAM_NAME, WrapperPlayServerTeams.TeamMode.CREATE, info, FLIP_NAME));
        flipTeamSent = true;
    }

    private void destroySingleSpoof(Player observer, SpoofedEntity state) {
        sendCounted(observer, new WrapperPlayServerDestroyEntities(state.fakeId));
        if (state.playerEntry) {
            sendCounted(observer, new WrapperPlayServerPlayerInfoRemove(List.of(state.fakeUuid)));
        }
    }

    private void hideLocalEntities(Player observer, ILocalPortal localPortal, Frustum4D frustum, double range, double projectionDepth) {
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
        Location eye = observer.getEyeLocation();
        double eyeDot = dot(eye.getX() - origin.getX(), eye.getY() - origin.getY(), eye.getZ() - origin.getZ(), frame);
        boolean eyeFrontSide = eyeDot >= 0.0D;
        double clearance = PortalProjector.portalPlaneClearance(localPortal.getStructure().getArea(), frame);
        double maxDepth = projectionDepth + clearance;
        for (Entity entity : nearbyLocalEntities(localPortal, localCenter, range)) {
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
        }
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
        Location location = entity.getLocation();
        double centerY = location.getY() + (entity.getHeight() * 0.5D);
        double signedDistance = dot(location.getX() - origin.getX(), centerY - origin.getY(), location.getZ() - origin.getZ(), frame);
        if (Math.abs(signedDistance) <= clearance || Math.abs(signedDistance) > maxDepth) {
            return false;
        }
        boolean entityFrontSide = signedDistance >= 0.0D;
        if (entityFrontSide == eyeFrontSide) {
            return false;
        }
        return frustum.containsPrimitive(location.getX(), centerY, location.getZ());
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
            if (entity != null && entity.isValid() && !entity.isDead()) {
                observer.showEntity(Wormholes.instance, entity);
            }
            iterator.remove();
        }
    }

    private void showAllLocalEntities(Player observer) {
        if (hiddenLocalEntities.isEmpty() || observer == null || !observer.isOnline() || Wormholes.instance == null) {
            return;
        }
        for (Entity entity : hiddenLocalEntities.values()) {
            if (entity != null && entity.isValid() && !entity.isDead()) {
                observer.showEntity(Wormholes.instance, entity);
            }
        }
    }

    private double dot(double x, double y, double z, PortalFrame frame) {
        return (x * frame.getNormal().x()) + (y * frame.getNormal().y()) + (z * frame.getNormal().z());
    }

    private boolean canSpoof(Entity entity) {
        if (entity == null || entity.isDead()) {
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

    private Vector3d mirroredVelocity(Entity entity, PortalFrame planeFrame) {
        Vector velocity = entity.getVelocity();
        PortalCoordMap.reflectVectorAcrossPlaneInto(velocity.getX(), velocity.getY(), velocity.getZ(), planeFrame, scratchDirection);
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

    private static final class EntityCandidateSnapshot {
        private final String worldName;
        private final int centerBlockX;
        private final int centerBlockY;
        private final int centerBlockZ;
        private final double range;
        private final long createdAtMillis;
        private final List<Entity> entities;

        private EntityCandidateSnapshot(Location center, double range, long createdAtMillis, List<Entity> entities) {
            this.worldName = center.getWorld().getName();
            this.centerBlockX = center.getBlockX();
            this.centerBlockY = center.getBlockY();
            this.centerBlockZ = center.getBlockZ();
            this.range = range;
            this.createdAtMillis = createdAtMillis;
            this.entities = entities;
        }

        private boolean matches(Location center, double range) {
            return worldName.equals(center.getWorld().getName())
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
