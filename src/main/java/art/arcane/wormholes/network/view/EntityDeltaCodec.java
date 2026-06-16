package art.arcane.wormholes.network.view;

import java.util.Arrays;
import java.util.Objects;
import java.util.UUID;

public final class EntityDeltaCodec {
    private EntityDeltaCodec() {
    }

    public static EntityVisual buildDelta(EntityVisual current, EntityVisual previous, int sequence, double velocityThreshold) {
        Objects.requireNonNull(current, "current");
        if (previous == null) {
            return EntityVisual.full(
                current.id(),
                current.typeKey(),
                current.x(), current.y(), current.z(),
                current.height(),
                current.lookX(), current.lookY(), current.lookZ(),
                current.yaw(), current.pitch(),
                current.velocityX(), current.velocityY(), current.velocityZ(),
                current.onGround(),
                current.playerName(),
                current.textureValue(),
                current.textureSignature(),
                current.passengerOf(),
                current.metadata(),
                current.equipment(),
                sequence
            );
        }
        int mask = 0;
        if (positionChanged(current, previous)) {
            mask |= EntityVisual.FIELD_POSITION;
        }
        if (current.yaw() != previous.yaw() || current.pitch() != previous.pitch()) {
            mask |= EntityVisual.FIELD_YAW_PITCH;
        }
        if (velocitySignificant(current, velocityThreshold) || velocityChanged(current, previous)) {
            mask |= EntityVisual.FIELD_VELOCITY;
        }
        if (lookVecChanged(current, previous)) {
            mask |= EntityVisual.FIELD_LOOK_VEC;
        }
        if (!Arrays.equals(safe(current.equipment()), safe(previous.equipment()))) {
            mask |= EntityVisual.FIELD_EQUIPMENT;
        }
        if (!Arrays.equals(safe(current.metadata()), safe(previous.metadata()))) {
            mask |= EntityVisual.FIELD_METADATA;
        }
        if (!Objects.equals(current.passengerOf(), previous.passengerOf())) {
            mask |= EntityVisual.FIELD_PASSENGER;
        }
        if (current.onGround() != previous.onGround()) {
            mask |= EntityVisual.FIELD_ON_GROUND;
        }
        if (current.height() != previous.height()) {
            mask |= EntityVisual.FIELD_HEIGHT;
        }
        if (profileChanged(current, previous)) {
            mask |= EntityVisual.FIELD_PROFILE;
        }
        return new EntityVisual(
            EntityVisual.MODE_DELTA,
            sequence,
            mask,
            current.id(),
            "",
            current.x(), current.y(), current.z(),
            current.height(),
            current.lookX(), current.lookY(), current.lookZ(),
            current.yaw(), current.pitch(),
            current.velocityX(), current.velocityY(), current.velocityZ(),
            current.onGround(),
            current.playerName() == null ? "" : current.playerName(),
            current.textureValue() == null ? "" : current.textureValue(),
            current.textureSignature() == null ? "" : current.textureSignature(),
            current.passengerOf(),
            current.metadata() == null ? PacketBlobs.EMPTY : current.metadata(),
            current.equipment() == null ? PacketBlobs.EMPTY : current.equipment()
        );
    }

    public static EntityVisual applyDelta(EntityVisual incoming, EntityVisual lastKnown) {
        Objects.requireNonNull(incoming, "incoming");
        if (incoming.isFull()) {
            return new EntityVisual(
                EntityVisual.MODE_FULL,
                incoming.sequence(),
                EntityVisual.FIELD_ALL_FULL,
                incoming.id(),
                incoming.typeKey(),
                incoming.x(), incoming.y(), incoming.z(),
                incoming.height(),
                incoming.lookX(), incoming.lookY(), incoming.lookZ(),
                incoming.yaw(), incoming.pitch(),
                incoming.velocityX(), incoming.velocityY(), incoming.velocityZ(),
                incoming.onGround(),
                incoming.playerName(),
                incoming.textureValue(),
                incoming.textureSignature(),
                incoming.passengerOf(),
                incoming.metadata(),
                incoming.equipment()
            );
        }
        if (lastKnown == null) {
            return incoming;
        }
        int mask = incoming.presentMask();
        UUID passengerOf = (mask & EntityVisual.FIELD_PASSENGER) != 0 ? incoming.passengerOf() : lastKnown.passengerOf();
        double x = (mask & EntityVisual.FIELD_POSITION) != 0 ? incoming.x() : lastKnown.x();
        double y = (mask & EntityVisual.FIELD_POSITION) != 0 ? incoming.y() : lastKnown.y();
        double z = (mask & EntityVisual.FIELD_POSITION) != 0 ? incoming.z() : lastKnown.z();
        double height = (mask & EntityVisual.FIELD_HEIGHT) != 0 ? incoming.height() : lastKnown.height();
        double lookX = (mask & EntityVisual.FIELD_LOOK_VEC) != 0 ? incoming.lookX() : lastKnown.lookX();
        double lookY = (mask & EntityVisual.FIELD_LOOK_VEC) != 0 ? incoming.lookY() : lastKnown.lookY();
        double lookZ = (mask & EntityVisual.FIELD_LOOK_VEC) != 0 ? incoming.lookZ() : lastKnown.lookZ();
        float yaw = (mask & EntityVisual.FIELD_YAW_PITCH) != 0 ? incoming.yaw() : lastKnown.yaw();
        float pitch = (mask & EntityVisual.FIELD_YAW_PITCH) != 0 ? incoming.pitch() : lastKnown.pitch();
        double velocityX = (mask & EntityVisual.FIELD_VELOCITY) != 0 ? incoming.velocityX() : lastKnown.velocityX();
        double velocityY = (mask & EntityVisual.FIELD_VELOCITY) != 0 ? incoming.velocityY() : lastKnown.velocityY();
        double velocityZ = (mask & EntityVisual.FIELD_VELOCITY) != 0 ? incoming.velocityZ() : lastKnown.velocityZ();
        boolean onGround = (mask & EntityVisual.FIELD_ON_GROUND) != 0 ? incoming.onGround() : lastKnown.onGround();
        String playerName = (mask & EntityVisual.FIELD_PROFILE) != 0 ? incoming.playerName() : lastKnown.playerName();
        String textureValue = (mask & EntityVisual.FIELD_PROFILE) != 0 ? incoming.textureValue() : lastKnown.textureValue();
        String textureSignature = (mask & EntityVisual.FIELD_PROFILE) != 0 ? incoming.textureSignature() : lastKnown.textureSignature();
        byte[] metadata = (mask & EntityVisual.FIELD_METADATA) != 0 ? incoming.metadata() : lastKnown.metadata();
        byte[] equipment = (mask & EntityVisual.FIELD_EQUIPMENT) != 0 ? incoming.equipment() : lastKnown.equipment();
        return new EntityVisual(
            EntityVisual.MODE_FULL,
            incoming.sequence(),
            EntityVisual.FIELD_ALL_FULL,
            incoming.id(),
            lastKnown.typeKey(),
            x, y, z,
            height,
            lookX, lookY, lookZ,
            yaw, pitch,
            velocityX, velocityY, velocityZ,
            onGround,
            playerName,
            textureValue,
            textureSignature,
            passengerOf,
            metadata,
            equipment
        );
    }

    private static boolean positionChanged(EntityVisual a, EntityVisual b) {
        return EntityVisual.quantize(a.x()) != EntityVisual.quantize(b.x())
            || EntityVisual.quantize(a.y()) != EntityVisual.quantize(b.y())
            || EntityVisual.quantize(a.z()) != EntityVisual.quantize(b.z());
    }

    private static boolean velocitySignificant(EntityVisual current, double threshold) {
        double magnitudeSquared = (current.velocityX() * current.velocityX())
            + (current.velocityY() * current.velocityY())
            + (current.velocityZ() * current.velocityZ());
        return magnitudeSquared >= threshold * threshold;
    }

    private static boolean velocityChanged(EntityVisual current, EntityVisual previous) {
        return current.velocityX() != previous.velocityX()
            || current.velocityY() != previous.velocityY()
            || current.velocityZ() != previous.velocityZ();
    }

    private static boolean lookVecChanged(EntityVisual current, EntityVisual previous) {
        return current.lookX() != previous.lookX()
            || current.lookY() != previous.lookY()
            || current.lookZ() != previous.lookZ();
    }

    private static boolean profileChanged(EntityVisual current, EntityVisual previous) {
        return !Objects.equals(current.playerName(), previous.playerName())
            || !Objects.equals(current.textureValue(), previous.textureValue())
            || !Objects.equals(current.textureSignature(), previous.textureSignature());
    }

    private static byte[] safe(byte[] data) {
        return data == null ? PacketBlobs.EMPTY : data;
    }
}
