package art.arcane.wormholes.network;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.zip.Deflater;
import java.util.zip.Inflater;

public final class WireCodec {
    public static final int PROTOCOL_VERSION = 6;
    public static final int MAX_FRAME_BYTES = 4 * 1024 * 1024;
    public static final int MAX_INFLATED_BYTES = 16 * 1024 * 1024;
    private static final int COMPRESS_THRESHOLD_BYTES = 1024;
    private static final byte FLAG_DEFLATED = 0x01;

    private WireCodec() {
    }

    public static byte[] encodeFrame(WireMessage message) throws IOException {
        byte[] payload = encodePayload(message);

        byte flags = 0;
        if (payload.length >= COMPRESS_THRESHOLD_BYTES) {
            byte[] deflated = deflate(payload);
            if (deflated.length < payload.length) {
                payload = deflated;
                flags |= FLAG_DEFLATED;
            }
        }

        int frameLength = 2 + payload.length;
        if (frameLength > MAX_FRAME_BYTES) {
            throw new IOException("Frame too large: " + frameLength + " bytes (" + message.type() + ")");
        }

        ByteArrayOutputStream frameBuffer = new ByteArrayOutputStream(frameLength + 4);
        DataOutputStream frameOut = new DataOutputStream(frameBuffer);
        frameOut.writeInt(frameLength);
        frameOut.writeByte(message.type().id());
        frameOut.writeByte(flags);
        frameOut.write(payload);
        frameOut.flush();
        return frameBuffer.toByteArray();
    }

    public static byte[] encodePayload(WireMessage message) throws IOException {
        ByteArrayOutputStream payloadBuffer = new ByteArrayOutputStream(256);
        DataOutputStream payloadOut = new DataOutputStream(payloadBuffer);
        message.write(payloadOut);
        payloadOut.flush();
        return payloadBuffer.toByteArray();
    }

    public static WireMessage readFrame(DataInputStream in) throws IOException {
        int frameLength = in.readInt();
        if (frameLength < 2 || frameLength > MAX_FRAME_BYTES) {
            throw new IOException("Invalid frame length: " + frameLength);
        }
        byte typeId = in.readByte();
        byte flags = in.readByte();
        byte[] payload = new byte[frameLength - 2];
        in.readFully(payload);

        WireMessageType type = WireMessageType.byId(typeId);
        if (type == null) {
            throw new IOException("Unknown message type id: " + typeId);
        }
        if ((flags & FLAG_DEFLATED) != 0) {
            payload = inflate(payload);
        }
        return decodePayload(type, payload);
    }

    public static WireMessage decodePayload(WireMessageType type, byte[] payload) throws IOException {
        DataInputStream in = new DataInputStream(new ByteArrayInputStream(payload));
        return switch (type) {
            case HELLO -> WireMessage.Hello.read(in);
            case CHALLENGE -> WireMessage.Challenge.read(in);
            case AUTH -> WireMessage.Auth.read(in);
            case READY -> WireMessage.Ready.read(in);
            case PING -> WireMessage.Ping.read(in);
            case PONG -> WireMessage.Pong.read(in);
            case ROUTED -> WireMessage.Routed.read(in);
            case PORTAL_DIRECTORY -> WireMessage.PortalDirectory.read(in);
            case PORTAL_UPSERT -> WireMessage.PortalUpsert.read(in);
            case PORTAL_REMOVE -> WireMessage.PortalRemove.read(in);
            case HANDOFF_REQUEST -> WireMessage.HandoffRequest.read(in);
            case HANDOFF_ACK -> WireMessage.HandoffAck.read(in);
            case HANDOFF_DENY -> WireMessage.HandoffDeny.read(in);
            case HANDOFF_CANCEL -> WireMessage.HandoffCancel.read(in);
            case ENTITY_TRANSFER -> WireMessage.EntityTransfer.read(in);
            case ENTITY_TRANSFER_ACK -> WireMessage.EntityTransferAck.read(in);
            case VIEW_SUBSCRIBE -> WireMessage.ViewSubscribe.read(in);
            case VIEW_UNSUBSCRIBE -> WireMessage.ViewUnsubscribe.read(in);
            case VIEW_SNAPSHOT -> WireMessage.ViewSnapshot.read(in);
            case VIEW_DELTA -> WireMessage.ViewDelta.read(in);
            case VIEW_ENTITIES -> WireMessage.ViewEntities.read(in);
            case VIEW_ENTITY_ANIMATION -> WireMessage.ViewEntityAnimation.read(in);
            case VIEW_TIME -> WireMessage.ViewTime.read(in);
            case SIDEBAND_FRAGMENT -> WireMessage.SidebandFragment.read(in);
        };
    }

    public static void writeFixedBytes(DataOutputStream out, byte[] bytes, int expectedLength) throws IOException {
        if (bytes == null || bytes.length != expectedLength) {
            throw new IOException("Expected " + expectedLength + " bytes, got " + (bytes == null ? "null" : bytes.length));
        }
        out.write(bytes);
    }

    public static byte[] readFixedBytes(DataInputStream in, int length) throws IOException {
        byte[] bytes = new byte[length];
        in.readFully(bytes);
        return bytes;
    }

    public static void writeByteArray(DataOutputStream out, byte[] bytes, int maxLength) throws IOException {
        if (bytes.length > maxLength) {
            throw new IOException("Byte array too large: " + bytes.length + " > " + maxLength);
        }
        out.writeInt(bytes.length);
        out.write(bytes);
    }

    public static byte[] readByteArray(DataInputStream in, int maxLength) throws IOException {
        int length = in.readInt();
        if (length < 0 || length > maxLength) {
            throw new IOException("Invalid byte array length: " + length + " (max " + maxLength + ")");
        }
        byte[] bytes = new byte[length];
        in.readFully(bytes);
        return bytes;
    }

    private static byte[] deflate(byte[] data) {
        Deflater deflater = new Deflater(Deflater.BEST_SPEED);
        try {
            deflater.setInput(data);
            deflater.finish();
            ByteArrayOutputStream out = new ByteArrayOutputStream(Math.max(64, data.length / 4));
            byte[] chunk = new byte[8192];
            while (!deflater.finished()) {
                int written = deflater.deflate(chunk);
                out.write(chunk, 0, written);
            }
            return out.toByteArray();
        } finally {
            deflater.end();
        }
    }

    private static byte[] inflate(byte[] data) throws IOException {
        Inflater inflater = new Inflater();
        try {
            inflater.setInput(data);
            ByteArrayOutputStream out = new ByteArrayOutputStream(Math.min(MAX_INFLATED_BYTES, data.length * 4));
            byte[] chunk = new byte[8192];
            while (!inflater.finished()) {
                int written = inflater.inflate(chunk);
                if (written == 0 && inflater.needsInput()) {
                    throw new IOException("Truncated deflated payload");
                }
                out.write(chunk, 0, written);
                if (out.size() > MAX_INFLATED_BYTES) {
                    throw new IOException("Inflated payload exceeds " + MAX_INFLATED_BYTES + " bytes");
                }
            }
            return out.toByteArray();
        } catch (java.util.zip.DataFormatException e) {
            throw new IOException("Corrupt deflated payload", e);
        } finally {
            inflater.end();
        }
    }
}
