package art.arcane.wormholes.render;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.protocol.entity.type.EntityType;
import com.github.retrooper.packetevents.protocol.entity.type.EntityTypes;
import com.github.retrooper.packetevents.util.Vector3d;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerDestroyEntities;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityTeleport;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityVelocity;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSpawnEntity;

import art.arcane.wormholes.Settings;
import art.arcane.wormholes.portal.ILocalPortal;
import art.arcane.wormholes.portal.PortalFrame;

public final class ProjectedEntityRenderer {
    private static final AtomicInteger NEXT_FAKE_ID = new AtomicInteger(1_900_000_000);

    private final Map<UUID, SpoofedEntity> spoofed;
    private final Set<UUID> visible;
    private final double[] scratchPoint;
    private final double[] scratchDirection;

    public ProjectedEntityRenderer() {
        this.spoofed = new HashMap<UUID, SpoofedEntity>(16);
        this.visible = new HashSet<UUID>(16);
        this.scratchPoint = new double[3];
        this.scratchDirection = new double[3];
    }

    public void apply(Player observer, ILocalPortal localPortal, ILocalPortal remotePortal, Frustum4D frustum, double projectionDepth) {
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
        int count = 0;

        for (Entity entity : remoteWorld.getNearbyEntities(remoteCenter, range, range, range)) {
            if (count >= Settings.MAX_SPOOFED_ENTITIES) {
                break;
            }
            if (!canSpoof(observer, entity)) {
                continue;
            }
            if (!projectEntity(observer, localPortal, remotePortal, frustum, entity)) {
                continue;
            }
            visible.add(entity.getUniqueId());
            count++;
        }

        destroyHidden(observer);
    }

    public void close(Player observer) {
        if (spoofed.isEmpty()) {
            return;
        }
        if (observer != null && observer.isOnline()) {
            int[] ids = new int[spoofed.size()];
            int index = 0;
            for (SpoofedEntity state : spoofed.values()) {
                ids[index] = state.fakeId;
                index++;
            }
            PacketEvents.getAPI().getPlayerManager().sendPacket(observer, new WrapperPlayServerDestroyEntities(ids));
        }
        spoofed.clear();
        visible.clear();
    }

    private boolean projectEntity(Player observer, ILocalPortal localPortal, ILocalPortal remotePortal, Frustum4D frustum, Entity entity) {
        EntityType packetType = packetEntityType(entity);
        if (packetType == null) {
            return false;
        }

        Location remoteLocation = entity.getLocation();
        PortalFrame remoteFrame = remotePortal.getFrame();
        PortalFrame localFrame = localPortal.getFrame();
        PortalCoordMap.transformPointInto(remoteLocation.getX(), remoteLocation.getY(), remoteLocation.getZ(),
            remotePortal.getOrigin().getX(), remotePortal.getOrigin().getY(), remotePortal.getOrigin().getZ(),
            localPortal.getOrigin().getX(), localPortal.getOrigin().getY(), localPortal.getOrigin().getZ(),
            remoteFrame, localFrame, scratchPoint);

        if (!frustum.containsPrimitive(scratchPoint[0], scratchPoint[1], scratchPoint[2])) {
            return false;
        }

        Vector direction = remoteLocation.getDirection();
        remoteFrame.transformVectorInto(direction.getX(), direction.getY(), direction.getZ(), localFrame, scratchDirection);
        float yaw = yaw(scratchDirection[0], scratchDirection[2]);
        float pitch = pitch(scratchDirection[0], scratchDirection[1], scratchDirection[2]);
        Vector3d position = new Vector3d(scratchPoint[0], scratchPoint[1], scratchPoint[2]);
        Vector3d velocity = transformedVelocity(entity, remoteFrame, localFrame);

        SpoofedEntity state = spoofed.get(entity.getUniqueId());
        if (state == null) {
            state = new SpoofedEntity(NEXT_FAKE_ID.getAndIncrement(), UUID.randomUUID());
            spoofed.put(entity.getUniqueId(), state);
            WrapperPlayServerSpawnEntity spawn = new WrapperPlayServerSpawnEntity(state.fakeId, Optional.of(state.fakeUuid),
                packetType, position, pitch, yaw, yaw, 0, Optional.of(velocity));
            PacketEvents.getAPI().getPlayerManager().sendPacket(observer, spawn);
            return true;
        }

        WrapperPlayServerEntityTeleport teleport = new WrapperPlayServerEntityTeleport(state.fakeId, position, yaw, pitch, entity.isOnGround());
        PacketEvents.getAPI().getPlayerManager().sendPacket(observer, teleport);
        PacketEvents.getAPI().getPlayerManager().sendPacket(observer, new WrapperPlayServerEntityVelocity(state.fakeId, velocity));
        return true;
    }

    private void destroyHidden(Player observer) {
        if (spoofed.isEmpty()) {
            return;
        }

        int[] ids = new int[spoofed.size()];
        int count = 0;
        Iterator<Map.Entry<UUID, SpoofedEntity>> iterator = spoofed.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<UUID, SpoofedEntity> entry = iterator.next();
            if (visible.contains(entry.getKey())) {
                continue;
            }
            ids[count] = entry.getValue().fakeId;
            count++;
            iterator.remove();
        }

        if (count == 0) {
            return;
        }

        int[] trimmed = new int[count];
        System.arraycopy(ids, 0, trimmed, 0, count);
        PacketEvents.getAPI().getPlayerManager().sendPacket(observer, new WrapperPlayServerDestroyEntities(trimmed));
    }

    private boolean canSpoof(Player observer, Entity entity) {
        if (entity == null || entity.isDead()) {
            return false;
        }
        if (entity instanceof Player) {
            return false;
        }
        return !entity.getUniqueId().equals(observer.getUniqueId());
    }

    private EntityType packetEntityType(Entity entity) {
        NamespacedKey key = entity.getType().getKey();
        if (key == null || "unknown".equals(key.getKey())) {
            return null;
        }
        return EntityTypes.getByName(key.getNamespace() + ":" + key.getKey());
    }

    private Vector3d transformedVelocity(Entity entity, PortalFrame fromFrame, PortalFrame toFrame) {
        Vector velocity = entity.getVelocity();
        fromFrame.transformVectorInto(velocity.getX(), velocity.getY(), velocity.getZ(), toFrame, scratchDirection);
        return new Vector3d(scratchDirection[0], scratchDirection[1], scratchDirection[2]);
    }

    private static float yaw(double x, double z) {
        return (float) Math.toDegrees(Math.atan2(-x, z));
    }

    private static float pitch(double x, double y, double z) {
        double horizontal = Math.sqrt(x * x + z * z);
        return (float) Math.toDegrees(-Math.atan2(y, horizontal));
    }

    private static final class SpoofedEntity {
        private final int fakeId;
        private final UUID fakeUuid;

        private SpoofedEntity(int fakeId, UUID fakeUuid) {
            this.fakeId = fakeId;
            this.fakeUuid = fakeUuid;
        }
    }
}
