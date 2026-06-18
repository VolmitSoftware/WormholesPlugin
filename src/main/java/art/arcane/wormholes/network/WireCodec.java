package art.arcane.wormholes.network;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;

public final class WireCodec {
    public interface PayloadSampler {
        void sample(byte[] payload);
    }

    public static final int PROTOCOL_VERSION = 11;
    public static final int MAX_FRAME_BYTES = 4 * 1024 * 1024;
    private static final int FRAME_PREFIX_OVERHEAD = 4 + 1;
    private static final int MIN_FRAME_BODY_BYTES = 2;

    private WireCodec() {
    }

    public static byte[] encodeFrame(WireMessage message) throws IOException {
        return encodeFrame(message, null, false, null);
    }

    public static byte[] encodeFrame(WireMessage message, WireCompression compression, boolean dictNegotiated) throws IOException {
        return encodeFrame(message, compression, dictNegotiated, null);
    }

    public static byte[] encodeFrame(WireMessage message, WireCompression compression, boolean dictNegotiated, PayloadSampler sampler) throws IOException {
        byte[] payload = encodePayload(message);
        if (sampler != null) {
            sampler.sample(payload);
        }
        byte[] body = compression == null ? prependPlainMode(payload) : compression.encode(payload, dictNegotiated);
        int frameLength = 1 + body.length;
        if (frameLength + 4 > MAX_FRAME_BYTES) {
            throw new IOException("Frame too large: " + (frameLength + 4) + " bytes (" + message.type() + ")");
        }
        ByteArrayOutputStream buffer = new ByteArrayOutputStream(frameLength + 4);
        DataOutputStream out = new DataOutputStream(buffer);
        out.writeInt(frameLength);
        out.writeByte(message.type().id());
        out.write(body);
        out.flush();
        return buffer.toByteArray();
    }

    public static byte[] encodePayload(WireMessage message) throws IOException {
        ByteArrayOutputStream payloadBuffer = new ByteArrayOutputStream(256);
        DataOutputStream payloadOut = new DataOutputStream(payloadBuffer);
        message.write(payloadOut);
        payloadOut.flush();
        return payloadBuffer.toByteArray();
    }

    public static WireMessage readFrame(DataInputStream in) throws IOException {
        return readFrame(in, null);
    }

    public static WireMessage readFrame(DataInputStream in, WireCompression compression) throws IOException {
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

    private static byte[] prependPlainMode(byte[] payload) {
        byte[] body = new byte[payload.length + 1];
        body[0] = WireCompression.MODE_NONE;
        System.arraycopy(payload, 0, body, 1, payload.length);
        return body;
    }

    private static byte[] extractPlainMode(byte[] body) throws IOException {
        if (body[0] != WireCompression.MODE_NONE) {
            throw new IOException("inbound frame uses compression mode " + body[0] + " but no decoder is bound");
        }
        byte[] payload = new byte[body.length - 1];
        System.arraycopy(body, 1, payload, 0, payload.length);
        return payload;
    }
}
