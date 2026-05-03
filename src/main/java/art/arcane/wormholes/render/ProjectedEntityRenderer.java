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

import com.destroystokyo.paper.profile.PlayerProfile;
import com.destroystokyo.paper.profile.ProfileProperty;
import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.protocol.entity.data.EntityData;
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
import io.github.retrooper.packetevents.util.SpigotConversionUtil;

import art.arcane.wormholes.Settings;
import art.arcane.wormholes.Wormholes;
import art.arcane.wormholes.portal.ILocalPortal;
import art.arcane.wormholes.portal.PortalFrame;

public final class ProjectedEntityRenderer {
    private static final AtomicInteger NEXT_FAKE_ID = new AtomicInteger(1_900_000_000);
    private static final int METADATA_REFRESH_PASSES = 10;
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

    public ProjectedEntityRenderer() {
        this.spoofed = new HashMap<UUID, SpoofedEntity>(16);
        this.hiddenLocalEntities = new HashMap<UUID, Entity>(16);
        this.entityTypeCache = new HashMap<NamespacedKey, EntityType>(32);
        this.visible = new HashSet<UUID>(16);
        this.visibleLocalHides = new HashSet<UUID>(16);
        this.scratchVisiblePoint = new double[3];
        this.scratchDirection = new double[3];
        this.metadataBridgeFailed = false;
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
        int count = 0;

        for (Entity entity : nearbyRemoteEntities(remotePortal, remoteCenter, range)) {
            if (count >= Settings.MAX_SPOOFED_ENTITIES) {
                break;
            }
            if (!canSpoof(entity)) {
                continue;
            }
            if (!projectEntity(observer, localPortal, remotePortal, localViewFrame, remoteViewFrame, frustum, entity)) {
                continue;
            }
            visible.add(entity.getUniqueId());
            count++;
        }

        destroyHidden(observer);
        restoreLocalEntities(observer);
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
        if (state == null) {
            return;
        }
        PacketEvents.getAPI().getPlayerManager().sendPacket(observer, new WrapperPlayServerEntityAnimation(state.fakeId, type));
    }

    public void sendHurt(Player observer, UUID sourceId, float yaw) {
        if (observer == null || !observer.isOnline() || sourceId == null) {
            return;
        }
        SpoofedEntity state = spoofed.get(sourceId);
        if (state == null) {
            return;
        }
        PacketEvents.getAPI().getPlayerManager().sendPacket(observer, new WrapperPlayServerEntityAnimation(state.fakeId, EntityAnimationType.HURT));
        PacketEvents.getAPI().getPlayerManager().sendPacket(observer, new WrapperPlayServerHurtAnimation(state.fakeId, yaw));
    }

    private boolean projectEntity(Player observer,
                                  ILocalPortal localPortal,
                                  ILocalPortal remotePortal,
                                  PortalFrame localViewFrame,
                                  PortalFrame remoteViewFrame,
                                  Frustum4D frustum,
                                  Entity entity) {
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
        if (state == null) {
            boolean playerEntity = entity instanceof Player;
            state = new SpoofedEntity(NEXT_FAKE_ID.getAndIncrement(), UUID.randomUUID(), playerEntity);
            spoofed.put(entity.getUniqueId(), state);
            if (playerEntity) {
                sendPlayerInfo(observer, (Player) entity, state);
            }
            WrapperPlayServerSpawnEntity spawn = new WrapperPlayServerSpawnEntity(state.fakeId, Optional.of(state.fakeUuid),
                packetType, position, pitch, yaw, yaw, 0, Optional.of(velocity));
            PacketEvents.getAPI().getPlayerManager().sendPacket(observer, spawn);
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
            PacketEvents.getAPI().getPlayerManager().sendPacket(observer, new WrapperPlayServerEntityVelocity(state.fakeId, velocity));
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
                    PacketEvents.getAPI().getPlayerManager().sendPacket(observer,
                        new WrapperPlayServerEntityRelativeMoveAndRotation(state.fakeId, move.deltaX, move.deltaY, move.deltaZ, yaw, pitch, onGround));
                } else {
                    PacketEvents.getAPI().getPlayerManager().sendPacket(observer,
                        new WrapperPlayServerEntityRelativeMove(state.fakeId, move.deltaX, move.deltaY, move.deltaZ, onGround));
                }
                return;
            }
            PacketEvents.getAPI().getPlayerManager().sendPacket(observer,
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
        PacketEvents.getAPI().getPlayerManager().sendPacket(observer, new WrapperPlayServerEntityHeadLook(state.fakeId, yaw));
    }

    private void sendEntityRotation(Player observer, SpoofedEntity state, float yaw, float pitch, boolean onGround) {
        PacketEvents.getAPI().getPlayerManager().sendPacket(observer, new WrapperPlayServerEntityRotation(state.fakeId, yaw, pitch, onGround));
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
        PacketEvents.getAPI().getPlayerManager().sendPacket(observer, new WrapperPlayServerDestroyEntities(trimmed));
        if (!playerInfos.isEmpty()) {
            PacketEvents.getAPI().getPlayerManager().sendPacket(observer, new WrapperPlayServerPlayerInfoRemove(playerInfos));
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
            PacketEvents.getAPI().getPlayerManager().sendPacket(observer, new WrapperPlayServerDestroyEntities(ids));
        }
        if (!players.isEmpty()) {
            PacketEvents.getAPI().getPlayerManager().sendPacket(observer, new WrapperPlayServerPlayerInfoRemove(players));
        }
    }

    private void sendEntityState(Player observer, Entity entity, SpoofedEntity state) {
        sendEntityMetadata(observer, entity, state.fakeId);
        sendEntityEquipment(observer, entity, state.fakeId);
    }

    private void sendEntityMetadata(Player observer, Entity entity, int fakeId) {
        if (metadataBridgeFailed) {
            return;
        }
        try {
            List<EntityData<?>> metadata = SpigotConversionUtil.getEntityMetadata(entity);
            if (!metadata.isEmpty()) {
                PacketEvents.getAPI().getPlayerManager().sendPacket(observer, new WrapperPlayServerEntityMetadata(fakeId, metadata));
            }
        } catch (RuntimeException ex) {
            metadataBridgeFailed = true;
            Wormholes.w("[ProjectedEntityRenderer] disabled entity metadata bridge after failure for " + entity.getType() + " " + entity.getUniqueId());
            ex.printStackTrace();
        }
    }

    private void sendEntityEquipment(Player observer, Entity entity, int fakeId) {
        if (!(entity instanceof LivingEntity)) {
            return;
        }
        LivingEntity living = (LivingEntity) entity;
        EntityEquipment equipment = living.getEquipment();
        if (equipment == null) {
            return;
        }
        List<Equipment> packetEquipment = new ArrayList<Equipment>(8);
        addEquipment(packetEquipment, living, equipment, org.bukkit.inventory.EquipmentSlot.HAND, com.github.retrooper.packetevents.protocol.player.EquipmentSlot.MAIN_HAND);
        addEquipment(packetEquipment, living, equipment, org.bukkit.inventory.EquipmentSlot.OFF_HAND, com.github.retrooper.packetevents.protocol.player.EquipmentSlot.OFF_HAND);
        addEquipment(packetEquipment, living, equipment, org.bukkit.inventory.EquipmentSlot.FEET, com.github.retrooper.packetevents.protocol.player.EquipmentSlot.BOOTS);
        addEquipment(packetEquipment, living, equipment, org.bukkit.inventory.EquipmentSlot.LEGS, com.github.retrooper.packetevents.protocol.player.EquipmentSlot.LEGGINGS);
        addEquipment(packetEquipment, living, equipment, org.bukkit.inventory.EquipmentSlot.CHEST, com.github.retrooper.packetevents.protocol.player.EquipmentSlot.CHEST_PLATE);
        addEquipment(packetEquipment, living, equipment, org.bukkit.inventory.EquipmentSlot.HEAD, com.github.retrooper.packetevents.protocol.player.EquipmentSlot.HELMET);
        addEquipment(packetEquipment, living, equipment, org.bukkit.inventory.EquipmentSlot.BODY, com.github.retrooper.packetevents.protocol.player.EquipmentSlot.BODY);
        addEquipment(packetEquipment, living, equipment, org.bukkit.inventory.EquipmentSlot.SADDLE, com.github.retrooper.packetevents.protocol.player.EquipmentSlot.SADDLE);
        if (!packetEquipment.isEmpty()) {
            PacketEvents.getAPI().getPlayerManager().sendPacket(observer, new WrapperPlayServerEntityEquipment(fakeId, packetEquipment));
        }
    }

    private void addEquipment(List<Equipment> packetEquipment,
                              LivingEntity living,
                              EntityEquipment equipment,
                              org.bukkit.inventory.EquipmentSlot bukkitSlot,
                              com.github.retrooper.packetevents.protocol.player.EquipmentSlot packetSlot) {
        if (!living.canUseEquipmentSlot(bukkitSlot)) {
            return;
        }
        ItemStack item = equipment.getItem(bukkitSlot);
        if (item == null) {
            item = new ItemStack(Material.AIR);
        }
        com.github.retrooper.packetevents.protocol.item.ItemStack packetItem = SpigotConversionUtil.fromBukkitItemStack(item);
        if (packetItem != null) {
            packetEquipment.add(new Equipment(packetSlot, packetItem));
        }
    }

    private void sendPlayerInfo(Player observer, Player player, SpoofedEntity state) {
        UserProfile userProfile = new UserProfile(state.fakeUuid, limitedProfileName(player.getName()));
        PlayerProfile playerProfile = player.getPlayerProfile();
        if (playerProfile != null) {
            for (ProfileProperty property : playerProfile.getProperties()) {
                userProfile.getTextureProperties().add(new TextureProperty(property.getName(), property.getValue(), property.getSignature()));
            }
        }
        GameMode gameMode = SpigotConversionUtil.fromBukkitGameMode(player.getGameMode());
        WrapperPlayServerPlayerInfoUpdate.PlayerInfo info = new WrapperPlayServerPlayerInfoUpdate.PlayerInfo(
            userProfile, false, player.getPing(), gameMode, null, null, 0, true);
        PacketEvents.getAPI().getPlayerManager().sendPacket(observer, new WrapperPlayServerPlayerInfoUpdate(
            EnumSet.of(WrapperPlayServerPlayerInfoUpdate.Action.ADD_PLAYER,
                WrapperPlayServerPlayerInfoUpdate.Action.UPDATE_GAME_MODE,
                WrapperPlayServerPlayerInfoUpdate.Action.UPDATE_LISTED,
                WrapperPlayServerPlayerInfoUpdate.Action.UPDATE_LATENCY,
                WrapperPlayServerPlayerInfoUpdate.Action.UPDATE_HAT),
            info));
    }

    private String limitedProfileName(String name) {
        String safe = name == null || name.isBlank() ? "PortalPlayer" : name;
        if (safe.length() <= 16) {
            return safe;
        }
        return safe.substring(0, 16);
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

        private SpoofedEntity(int fakeId, UUID fakeUuid, boolean playerEntry) {
            this.fakeId = fakeId;
            this.fakeUuid = fakeUuid;
            this.playerEntry = playerEntry;
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
