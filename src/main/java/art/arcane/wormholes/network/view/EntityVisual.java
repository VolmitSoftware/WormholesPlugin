package art.arcane.wormholes.network.view;

import art.arcane.wormholes.network.WireCodec;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.Objects;
import java.util.UUID;

public record EntityVisual(
    UUID id,
    String typeKey,
    double x,
    double y,
    double z,
    double height,
    double lookX,
    double lookY,
    double lookZ,
    double velocityX,
    double velocityY,
    double velocityZ,
    boolean onGround,
    String playerName,
    String textureValue,
    String textureSignature,
    byte[] metadata,
    byte[] equipment
) {
    public static final String PLAYER_TYPE_KEY = "minecraft:player";
    private static final int MAX_BLOB_BYTES = 64 * 1024;

    public boolean isPlayer() {
        return PLAYER_TYPE_KEY.equals(typeKey);
    }

    public boolean hasTextures() {
        return textureValue != null && !textureValue.isEmpty();
    }

    public void write(DataOutputStream out) throws IOException {
        out.writeLong(id.getMostSignificantBits());
        out.writeLong(id.getLeastSignificantBits());
        out.writeUTF(typeKey);
        out.writeDouble(x);
        out.writeDouble(y);
        out.writeDouble(z);
        out.writeDouble(height);
        out.writeDouble(lookX);
        out.writeDouble(lookY);
        out.writeDouble(lookZ);
        out.writeDouble(velocityX);
        out.writeDouble(velocityY);
        out.writeDouble(velocityZ);
        out.writeBoolean(onGround);
        out.writeUTF(playerName == null ? "" : playerName);
        out.writeUTF(textureValue == null ? "" : textureValue);
        out.writeUTF(textureSignature == null ? "" : textureSignature);
        WireCodec.writeByteArray(out, metadata == null ? PacketBlobs.EMPTY : metadata, MAX_BLOB_BYTES);
        WireCodec.writeByteArray(out, equipment == null ? PacketBlobs.EMPTY : equipment, MAX_BLOB_BYTES);
    }

    public static EntityVisual read(DataInputStream in) throws IOException {
        UUID id = new UUID(in.readLong(), in.readLong());
        String typeKey = in.readUTF();
        double x = in.readDouble();
        double y = in.readDouble();
        double z = in.readDouble();
        double height = in.readDouble();
        double lookX = in.readDouble();
        double lookY = in.readDouble();
        double lookZ = in.readDouble();
        double velocityX = in.readDouble();
        double velocityY = in.readDouble();
        double velocityZ = in.readDouble();
        boolean onGround = in.readBoolean();
        String playerName = in.readUTF();
        String textureValue = in.readUTF();
        String textureSignature = in.readUTF();
        byte[] metadata = WireCodec.readByteArray(in, MAX_BLOB_BYTES);
        byte[] equipment = WireCodec.readByteArray(in, MAX_BLOB_BYTES);
        return new EntityVisual(id, typeKey, x, y, z, height, lookX, lookY, lookZ, velocityX, velocityY, velocityZ, onGround, playerName, textureValue, textureSignature, metadata, equipment);
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof EntityVisual visual)) {
            return false;
        }
        return id.equals(visual.id)
            && typeKey.equals(visual.typeKey)
            && x == visual.x && y == visual.y && z == visual.z
            && height == visual.height
            && lookX == visual.lookX && lookY == visual.lookY && lookZ == visual.lookZ
            && velocityX == visual.velocityX && velocityY == visual.velocityY && velocityZ == visual.velocityZ
            && onGround == visual.onGround
            && Objects.equals(playerName, visual.playerName)
            && Objects.equals(textureValue, visual.textureValue)
            && Objects.equals(textureSignature, visual.textureSignature)
            && Arrays.equals(metadata, visual.metadata)
            && Arrays.equals(equipment, visual.equipment);
    }

    @Override
    public int hashCode() {
        int result = Objects.hash(id, typeKey, x, y, z, height, lookX, lookY, lookZ, velocityX, velocityY, velocityZ, onGround, playerName, textureValue, textureSignature);
        result = 31 * result + Arrays.hashCode(metadata);
        result = 31 * result + Arrays.hashCode(equipment);
        return result;
    }
}
