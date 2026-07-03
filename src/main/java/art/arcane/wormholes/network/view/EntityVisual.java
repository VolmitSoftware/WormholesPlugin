package art.arcane.wormholes.network.view;

import art.arcane.wormholes.network.WireCodec;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.Objects;
import java.util.UUID;

public record EntityVisual(
    byte mode,
    int sequence,
    int presentMask,
    UUID id,
    String typeKey,
    double x,
    double y,
    double z,
    double height,
    double lookX,
    double lookY,
    double lookZ,
    float yaw,
    float pitch,
    double velocityX,
    double velocityY,
    double velocityZ,
    boolean onGround,
    String playerName,
    String textureValue,
    String textureSignature,
    UUID passengerOf,
    UUID leashHolder,
    byte[] metadata,
    byte[] equipment
) {
    public static final String PLAYER_TYPE_KEY = "minecraft:player";
    public static final byte MODE_FULL = 0;
    public static final byte MODE_DELTA = 1;

    public static final int FIELD_POSITION = 1 << 0;
    public static final int FIELD_YAW_PITCH = 1 << 1;
    public static final int FIELD_VELOCITY = 1 << 2;
    public static final int FIELD_EQUIPMENT = 1 << 3;
    public static final int FIELD_METADATA = 1 << 4;
    public static final int FIELD_TYPE = 1 << 5;
    public static final int FIELD_PASSENGER = 1 << 6;
    public static final int FIELD_ON_GROUND = 1 << 7;
    public static final int FIELD_HEIGHT = 1 << 8;
    public static final int FIELD_PROFILE = 1 << 9;
    public static final int FIELD_LOOK_VEC = 1 << 10;
    public static final int FIELD_LEASH = 1 << 11;
    public static final int FIELD_ALL_FULL = FIELD_POSITION | FIELD_YAW_PITCH | FIELD_VELOCITY | FIELD_EQUIPMENT
        | FIELD_METADATA | FIELD_TYPE | FIELD_PASSENGER | FIELD_ON_GROUND | FIELD_HEIGHT | FIELD_PROFILE | FIELD_LOOK_VEC | FIELD_LEASH;

    public static final double POSITION_QUANTUM = 1.0D / 4096.0D;
    public static final double UNIT_SCALE = 32767.0D;
    public static final double VELOCITY_SCALE = 1024.0D;
    public static final double HEIGHT_SCALE = 100.0D;
    public static final double MAX_HEIGHT = 255.0D / HEIGHT_SCALE;

    private static final int MAX_BLOB_BYTES = 64 * 1024;

    public boolean isPlayer() {
        return PLAYER_TYPE_KEY.equals(typeKey);
    }

    public boolean hasTextures() {
        return textureValue != null && !textureValue.isEmpty();
    }

    public boolean isFull() {
        return mode == MODE_FULL;
    }

    public static EntityVisual full(
        UUID id,
        String typeKey,
        double x,
        double y,
        double z,
        double height,
        double lookX,
        double lookY,
        double lookZ,
        float yaw,
        float pitch,
        double velocityX,
        double velocityY,
        double velocityZ,
        boolean onGround,
        String playerName,
        String textureValue,
        String textureSignature,
        UUID passengerOf,
        UUID leashHolder,
        byte[] metadata,
        byte[] equipment,
        int sequence
    ) {
        return new EntityVisual(
            MODE_FULL,
            sequence,
            FIELD_ALL_FULL,
            id,
            typeKey,
            x, y, z,
            height,
            lookX, lookY, lookZ,
            yaw, pitch,
            velocityX, velocityY, velocityZ,
            onGround,
            playerName == null ? "" : playerName,
            textureValue == null ? "" : textureValue,
            textureSignature == null ? "" : textureSignature,
            passengerOf,
            leashHolder,
            metadata == null ? PacketBlobs.EMPTY : metadata,
            equipment == null ? PacketBlobs.EMPTY : equipment
        );
    }

    public void write(DataOutputStream out) throws IOException {
        int header = ((mode == MODE_DELTA ? 1 : 0) << 15) | (presentMask & 0x7FFF);
        out.writeShort(header);
        out.writeShort(sequence & 0xFFFF);
        out.writeLong(id.getMostSignificantBits());
        out.writeLong(id.getLeastSignificantBits());
        if ((presentMask & FIELD_TYPE) != 0) {
            out.writeUTF(typeKey == null ? "" : typeKey);
        }
        if ((presentMask & FIELD_POSITION) != 0) {
            out.writeInt(quantize(x));
            out.writeInt(quantize(y));
            out.writeInt(quantize(z));
        }
        if ((presentMask & FIELD_HEIGHT) != 0) {
            out.writeByte(quantizeHeight(height));
        }
        if ((presentMask & FIELD_LOOK_VEC) != 0) {
            out.writeShort(quantizeUnit(lookX));
            out.writeShort(quantizeUnit(lookY));
            out.writeShort(quantizeUnit(lookZ));
        }
        if ((presentMask & FIELD_YAW_PITCH) != 0) {
            out.writeByte(quantizeAngle(yaw));
            out.writeByte(quantizeAngle(pitch));
        }
        if ((presentMask & FIELD_VELOCITY) != 0) {
            out.writeShort(quantizeVelocity(velocityX));
            out.writeShort(quantizeVelocity(velocityY));
            out.writeShort(quantizeVelocity(velocityZ));
        }
        if ((presentMask & FIELD_ON_GROUND) != 0) {
            out.writeBoolean(onGround);
        }
        if ((presentMask & FIELD_PROFILE) != 0) {
            out.writeUTF(playerName == null ? "" : playerName);
            out.writeUTF(textureValue == null ? "" : textureValue);
            out.writeUTF(textureSignature == null ? "" : textureSignature);
        }
        if ((presentMask & FIELD_PASSENGER) != 0) {
            boolean hasPassengerOf = passengerOf != null;
            out.writeBoolean(hasPassengerOf);
            if (hasPassengerOf) {
                out.writeLong(passengerOf.getMostSignificantBits());
                out.writeLong(passengerOf.getLeastSignificantBits());
            }
        }
        if ((presentMask & FIELD_LEASH) != 0) {
            boolean hasLeashHolder = leashHolder != null;
            out.writeBoolean(hasLeashHolder);
            if (hasLeashHolder) {
                out.writeLong(leashHolder.getMostSignificantBits());
                out.writeLong(leashHolder.getLeastSignificantBits());
            }
        }
        if ((presentMask & FIELD_METADATA) != 0) {
            WireCodec.writeByteArray(out, metadata == null ? PacketBlobs.EMPTY : metadata, MAX_BLOB_BYTES);
        }
        if ((presentMask & FIELD_EQUIPMENT) != 0) {
            WireCodec.writeByteArray(out, equipment == null ? PacketBlobs.EMPTY : equipment, MAX_BLOB_BYTES);
        }
    }

    public static EntityVisual read(DataInputStream in) throws IOException {
        int header = in.readUnsignedShort();
        byte mode = (header & 0x8000) != 0 ? MODE_DELTA : MODE_FULL;
        int presentMask = header & 0x7FFF;
        int sequence = in.readUnsignedShort();
        UUID id = new UUID(in.readLong(), in.readLong());
        String typeKey = (presentMask & FIELD_TYPE) != 0 ? in.readUTF() : "";
        double x = 0.0D;
        double y = 0.0D;
        double z = 0.0D;
        if ((presentMask & FIELD_POSITION) != 0) {
            x = dequantize(in.readInt());
            y = dequantize(in.readInt());
            z = dequantize(in.readInt());
        }
        double height = 0.0D;
        if ((presentMask & FIELD_HEIGHT) != 0) {
            height = dequantizeHeight(in.readByte());
        }
        double lookX = 0.0D;
        double lookY = 0.0D;
        double lookZ = 0.0D;
        if ((presentMask & FIELD_LOOK_VEC) != 0) {
            lookX = dequantizeUnit(in.readShort());
            lookY = dequantizeUnit(in.readShort());
            lookZ = dequantizeUnit(in.readShort());
        }
        float yaw = 0.0F;
        float pitch = 0.0F;
        if ((presentMask & FIELD_YAW_PITCH) != 0) {
            yaw = dequantizeAngle(in.readByte());
            pitch = dequantizeAngle(in.readByte());
        }
        double velocityX = 0.0D;
        double velocityY = 0.0D;
        double velocityZ = 0.0D;
        if ((presentMask & FIELD_VELOCITY) != 0) {
            velocityX = dequantizeVelocity(in.readShort());
            velocityY = dequantizeVelocity(in.readShort());
            velocityZ = dequantizeVelocity(in.readShort());
        }
        boolean onGround = false;
        if ((presentMask & FIELD_ON_GROUND) != 0) {
            onGround = in.readBoolean();
        }
        String playerName = "";
        String textureValue = "";
        String textureSignature = "";
        if ((presentMask & FIELD_PROFILE) != 0) {
            playerName = in.readUTF();
            textureValue = in.readUTF();
            textureSignature = in.readUTF();
        }
        UUID passengerOf = null;
        if ((presentMask & FIELD_PASSENGER) != 0) {
            boolean hasPassengerOf = in.readBoolean();
            if (hasPassengerOf) {
                passengerOf = new UUID(in.readLong(), in.readLong());
            }
        }
        UUID leashHolder = null;
        if ((presentMask & FIELD_LEASH) != 0) {
            boolean hasLeashHolder = in.readBoolean();
            if (hasLeashHolder) {
                leashHolder = new UUID(in.readLong(), in.readLong());
            }
        }
        byte[] metadata = PacketBlobs.EMPTY;
        if ((presentMask & FIELD_METADATA) != 0) {
            metadata = WireCodec.readByteArray(in, MAX_BLOB_BYTES);
        }
        byte[] equipment = PacketBlobs.EMPTY;
        if ((presentMask & FIELD_EQUIPMENT) != 0) {
            equipment = WireCodec.readByteArray(in, MAX_BLOB_BYTES);
        }
        return new EntityVisual(
            mode,
            sequence,
            presentMask,
            id,
            typeKey,
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
            leashHolder,
            metadata,
            equipment
        );
    }

    public static int quantize(double value) {
        double scaled = value * 4096.0D;
        if (scaled > (double) Integer.MAX_VALUE) {
            return Integer.MAX_VALUE;
        }
        if (scaled < (double) Integer.MIN_VALUE) {
            return Integer.MIN_VALUE;
        }
        return (int) Math.round(scaled);
    }

    public static double dequantize(int quantized) {
        return ((double) quantized) * POSITION_QUANTUM;
    }

    public static short quantizeUnit(double value) {
        double scaled = value * UNIT_SCALE;
        if (scaled > (double) Short.MAX_VALUE) {
            return Short.MAX_VALUE;
        }
        if (scaled < (double) Short.MIN_VALUE) {
            return Short.MIN_VALUE;
        }
        return (short) Math.round(scaled);
    }

    public static double dequantizeUnit(short quantized) {
        return ((double) quantized) / UNIT_SCALE;
    }

    public static short quantizeVelocity(double value) {
        double scaled = value * VELOCITY_SCALE;
        if (scaled > (double) Short.MAX_VALUE) {
            return Short.MAX_VALUE;
        }
        if (scaled < (double) Short.MIN_VALUE) {
            return Short.MIN_VALUE;
        }
        return (short) Math.round(scaled);
    }

    public static double dequantizeVelocity(short quantized) {
        return ((double) quantized) / VELOCITY_SCALE;
    }

    public static byte quantizeHeight(double value) {
        double scaled = value * HEIGHT_SCALE;
        if (scaled > 255.0D) {
            return (byte) 255;
        }
        if (scaled < 0.0D) {
            return (byte) 0;
        }
        return (byte) Math.round(scaled);
    }

    public static double dequantizeHeight(byte quantized) {
        return ((double) (quantized & 0xFF)) / HEIGHT_SCALE;
    }

    public static int quantizeAngle(float degrees) {
        float wrapped = ((degrees % 360.0F) + 360.0F) % 360.0F;
        int scaled = Math.round(wrapped * 256.0F / 360.0F);
        return scaled & 0xFF;
    }

    public static float dequantizeAngle(byte quantized) {
        int unsigned = quantized & 0xFF;
        return ((float) unsigned) * 360.0F / 256.0F;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof EntityVisual visual)) {
            return false;
        }
        return mode == visual.mode
            && sequence == visual.sequence
            && presentMask == visual.presentMask
            && id.equals(visual.id)
            && Objects.equals(typeKey, visual.typeKey)
            && x == visual.x && y == visual.y && z == visual.z
            && height == visual.height
            && lookX == visual.lookX && lookY == visual.lookY && lookZ == visual.lookZ
            && yaw == visual.yaw && pitch == visual.pitch
            && velocityX == visual.velocityX && velocityY == visual.velocityY && velocityZ == visual.velocityZ
            && onGround == visual.onGround
            && Objects.equals(playerName, visual.playerName)
            && Objects.equals(textureValue, visual.textureValue)
            && Objects.equals(textureSignature, visual.textureSignature)
            && Objects.equals(passengerOf, visual.passengerOf)
            && Objects.equals(leashHolder, visual.leashHolder)
            && Arrays.equals(metadata, visual.metadata)
            && Arrays.equals(equipment, visual.equipment);
    }

    @Override
    public int hashCode() {
        int result = Objects.hash(mode, sequence, presentMask, id, typeKey, x, y, z, height, lookX, lookY, lookZ, yaw, pitch, velocityX, velocityY, velocityZ, onGround, playerName, textureValue, textureSignature, passengerOf, leashHolder);
        result = 31 * result + Arrays.hashCode(metadata);
        result = 31 * result + Arrays.hashCode(equipment);
        return result;
    }
}
