package art.arcane.wormholes.network;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.Arrays;

public final class WireCodec {
    public interface PayloadSampler {
        void sample(WireMessageType type, byte[] payload);
    }

    public static final int PROTOCOL_VERSION = 12;
    public static final int MAX_FRAME_BYTES = 4 * 1024 * 1024;
    private static final int MIN_FRAME_BODY_BYTES = 2;
    private static final int PAYLOAD_SCRATCH_RETAIN_LIMIT_BYTES = 1024 * 1024;
    private static final ThreadLocal<ExposedByteArrayOutputStream> PAYLOAD_SCRATCH = ThreadLocal.withInitial(ExposedByteArrayOutputStream::new);

    private WireCodec() {
    }

    public static byte[] encodeFrame(WireMessage message) throws IOException {
        return encodeFrame(message, null, 0, null);
    }

    public static byte[] encodeFrame(WireMessage message, WireCompression compression, int negotiatedDictVersion) throws IOException {
        return encodeFrame(message, compression, negotiatedDictVersion, null);
    }

    public static byte[] encodeFrame(WireMessage message, WireCompression compression, int negotiatedDictVersion, PayloadSampler sampler) throws IOException {
        byte[] payload = encodePayload(message);
        if (sampler != null) {
            sampler.sample(message.type(), payload);
        }
        if (compression == null) {
            return buildPlainFrame(message.type(), payload);
        }
        byte[] frame = compression.encodeFramedFrame(message.type().id(), payload, negotiatedDictVersion);
        if (frame.length > MAX_FRAME_BYTES) {
            throw new IOException("Frame too large: " + frame.length + " bytes (" + message.type() + ")");
        }
        return frame;
    }

    public static byte[] encodePayload(WireMessage message) throws IOException {
        ExposedByteArrayOutputStream scratch = PAYLOAD_SCRATCH.get();
        scratch.reset();
        DataOutputStream payloadOut = new DataOutputStream(scratch);
        message.write(payloadOut);
        payloadOut.flush();
        byte[] payload = Arrays.copyOf(scratch.buffer(), scratch.length());
        if (scratch.buffer().length > PAYLOAD_SCRATCH_RETAIN_LIMIT_BYTES) {
            PAYLOAD_SCRATCH.remove();
        }
        return payload;
    }

    public static WireMessage readFrame(DataInputStream in) throws IOException {
        return readFrame(in, null, null);
    }

    public static WireMessage readFrame(DataInputStream in, WireCompression compression) throws IOException {
        return readFrame(in, compression, null);
    }

    public static WireMessage readFrame(DataInputStream in, WireCompression compression, PayloadSampler sampler) throws IOException {
        int frameLength = in.readInt();
        if (frameLength < MIN_FRAME_BODY_BYTES || frameLength > MAX_FRAME_BYTES) {
            throw new IOException("Invalid frame length: " + frameLength);
        }
        byte typeId = in.readByte();
        int bodyLength = frameLength - 1;
        if (bodyLength < 1) {
            throw new IOException("Frame body missing compression byte");
        }
        byte[] body = new byte[bodyLength];
        in.readFully(body);

        WireMessageType type = WireMessageType.byId(typeId);
        if (type == null) {
            throw new IOException("Unknown message type id: " + typeId);
        }
        byte[] payload = compression == null ? extractPlainMode(body) : compression.decode(body).payload();
        if (sampler != null) {
            sampler.sample(type, payload);
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
            case DICT_OFFER -> WireMessage.DictOffer.read(in);
            case DICT_REQUEST -> WireMessage.DictRequest.read(in);
            case DICT_DATA -> WireMessage.DictData.read(in);
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
            case VIEW_ENTITIES -> WireMessage.ViewEntities.read(in);
            case VIEW_ENTITY_ANIMATION -> WireMessage.ViewEntityAnimation.read(in);
            case VIEW_TIME -> WireMessage.ViewTime.read(in);
            case CHUNK_BULK -> WireMessage.ChunkBulkBatch.read(in);
            case CHUNK_DIFF -> WireMessage.ChunkDiff.read(in);
            case CHUNK_HASH_PROBE -> WireMessage.ChunkHashProbeMessage.read(in);
            case CHUNK_RESYNC_REQUEST -> WireMessage.ChunkResyncRequestMessage.read(in);
            case VIEW_BULK_COMPLETE -> WireMessage.ViewBulkComplete.read(in);
            case PORTAL_SETTINGS_UPDATE -> WireMessage.PortalSettingsUpdate.read(in);
            case SIDEBAND_FRAGMENT -> WireMessage.SidebandFragment.read(in);
        };
    }

    public static void writeDirection(DataOutputStream out, String directionName) throws IOException {
        int id = switch (directionName) {
            case "U" -> 0;
            case "D" -> 1;
            case "N" -> 2;
            case "S" -> 3;
            case "E" -> 4;
            case "W" -> 5;
            default -> throw new IOException("Unknown direction: " + directionName);
        };
        out.writeByte(id);
    }

    public static String readDirection(DataInputStream in) throws IOException {
        int id = in.readUnsignedByte();
        return switch (id) {
            case 0 -> "U";
            case 1 -> "D";
            case 2 -> "N";
            case 3 -> "S";
            case 4 -> "E";
            case 5 -> "W";
            default -> throw new IOException("Unknown direction id: " + id);
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

    static byte[] buildPlainFrame(WireMessageType type, byte[] payload) throws IOException {
        int frameLength = 1 + 1 + payload.length;
        if (frameLength + 4 > MAX_FRAME_BYTES) {
            throw new IOException("Frame too large: " + (frameLength + 4) + " bytes (" + type + ")");
        }
        byte[] frame = new byte[4 + frameLength];
        frame[0] = (byte) ((frameLength >>> 24) & 0xFF);
        frame[1] = (byte) ((frameLength >>> 16) & 0xFF);
        frame[2] = (byte) ((frameLength >>> 8) & 0xFF);
        frame[3] = (byte) (frameLength & 0xFF);
        frame[4] = type.id();
        frame[5] = WireCompression.MODE_NONE;
        System.arraycopy(payload, 0, frame, 6, payload.length);
        return frame;
    }

    private static byte[] extractPlainMode(byte[] body) throws IOException {
        if (body[0] != WireCompression.MODE_NONE) {
            throw new IOException("inbound frame uses compression mode " + body[0] + " but no decoder is bound");
        }
        byte[] payload = new byte[body.length - 1];
        System.arraycopy(body, 1, payload, 0, payload.length);
        return payload;
    }

    static final class ExposedByteArrayOutputStream extends ByteArrayOutputStream {
        byte[] buffer() {
            return buf;
        }

        int length() {
            return count;
        }
    }
}
