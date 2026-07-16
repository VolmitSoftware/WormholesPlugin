package art.arcane.wormholes.network;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import art.arcane.wormholes.network.replication.ChunkBulk;
import art.arcane.wormholes.network.replication.ChunkDiffBatch;
import art.arcane.wormholes.network.replication.ChunkHashProbe;
import art.arcane.wormholes.network.replication.ChunkResyncRequest;
import art.arcane.wormholes.network.replication.ReplicationVarint;
import art.arcane.wormholes.network.view.EntityVisual;
import art.arcane.wormholes.network.view.ViewSlice;

public sealed interface WireMessage {
    WireMessageType type();

    void write(DataOutputStream out) throws IOException;

    record Hello(int protocolVersion, String mcVersion, String pluginVersion, String serverName, String advertiseHost, int wormholePort, int gamePort, byte[] nonce, byte[] publicKey, boolean compressionSupported, byte[] currentDictHash, int currentDictVersion) implements WireMessage {
        @Override
        public WireMessageType type() {
            return WireMessageType.HELLO;
        }

        @Override
        public void write(DataOutputStream out) throws IOException {
            out.writeInt(protocolVersion);
            out.writeUTF(mcVersion);
            out.writeUTF(pluginVersion);
            out.writeUTF(serverName);
            out.writeUTF(advertiseHost);
            out.writeShort(wormholePort);
            out.writeShort(gamePort);
            WireCodec.writeFixedBytes(out, nonce, Handshake.NONCE_LENGTH);
            WireCodec.writeByteArray(out, publicKey, Handshake.PUBLIC_KEY_MAX_LENGTH);
            out.writeBoolean(compressionSupported);
            WireCodec.writeFixedBytes(out, currentDictHash, CompressionDictionary.HASH_LENGTH);
            out.writeInt(currentDictVersion);
        }

        public static Hello read(DataInputStream in) throws IOException {
            int protocolVersion = in.readInt();
            String mcVersion = in.readUTF();
            String pluginVersion = in.readUTF();
            String serverName = in.readUTF();
            String advertiseHost = in.readUTF();
            int wormholePort = in.readUnsignedShort();
            int gamePort = in.readUnsignedShort();
            byte[] nonce = WireCodec.readFixedBytes(in, Handshake.NONCE_LENGTH);
            byte[] publicKey = WireCodec.readByteArray(in, Handshake.PUBLIC_KEY_MAX_LENGTH);
            boolean compressionSupported = in.readBoolean();
            byte[] currentDictHash = WireCodec.readFixedBytes(in, CompressionDictionary.HASH_LENGTH);
            int currentDictVersion = in.readInt();
            return new Hello(protocolVersion, mcVersion, pluginVersion, serverName, advertiseHost, wormholePort, gamePort, nonce, publicKey, compressionSupported, currentDictHash, currentDictVersion);
        }
    }

    record Challenge(String serverName, String advertiseHost, int wormholePort, int gamePort, byte[] nonce, byte[] publicKey, byte[] signature, boolean compressionSupported, byte[] currentDictHash, int currentDictVersion) implements WireMessage {
        @Override
        public WireMessageType type() {
            return WireMessageType.CHALLENGE;
        }

        @Override
        public void write(DataOutputStream out) throws IOException {
            out.writeUTF(serverName);
            out.writeUTF(advertiseHost);
            out.writeShort(wormholePort);
            out.writeShort(gamePort);
            WireCodec.writeFixedBytes(out, nonce, Handshake.NONCE_LENGTH);
            WireCodec.writeByteArray(out, publicKey, Handshake.PUBLIC_KEY_MAX_LENGTH);
            WireCodec.writeByteArray(out, signature, Handshake.SIGNATURE_MAX_LENGTH);
            out.writeBoolean(compressionSupported);
            WireCodec.writeFixedBytes(out, currentDictHash, CompressionDictionary.HASH_LENGTH);
            out.writeInt(currentDictVersion);
        }

        public static Challenge read(DataInputStream in) throws IOException {
            String serverName = in.readUTF();
            String advertiseHost = in.readUTF();
            int wormholePort = in.readUnsignedShort();
            int gamePort = in.readUnsignedShort();
            byte[] nonce = WireCodec.readFixedBytes(in, Handshake.NONCE_LENGTH);
            byte[] publicKey = WireCodec.readByteArray(in, Handshake.PUBLIC_KEY_MAX_LENGTH);
            byte[] signature = WireCodec.readByteArray(in, Handshake.SIGNATURE_MAX_LENGTH);
            boolean compressionSupported = in.readBoolean();
            byte[] currentDictHash = WireCodec.readFixedBytes(in, CompressionDictionary.HASH_LENGTH);
            int currentDictVersion = in.readInt();
            return new Challenge(serverName, advertiseHost, wormholePort, gamePort, nonce, publicKey, signature, compressionSupported, currentDictHash, currentDictVersion);
        }
    }

    record Auth(byte[] signature) implements WireMessage {
        @Override
        public WireMessageType type() {
            return WireMessageType.AUTH;
        }

        @Override
        public void write(DataOutputStream out) throws IOException {
            WireCodec.writeByteArray(out, signature, Handshake.SIGNATURE_MAX_LENGTH);
        }

        public static Auth read(DataInputStream in) throws IOException {
            return new Auth(WireCodec.readByteArray(in, Handshake.SIGNATURE_MAX_LENGTH));
        }
    }

    record Ready() implements WireMessage {
        @Override
        public WireMessageType type() {
            return WireMessageType.READY;
        }

        @Override
        public void write(DataOutputStream out) {
        }

        public static Ready read(DataInputStream in) {
            return new Ready();
        }
    }

    record Ping(long sentAtMillis) implements WireMessage {
        @Override
        public WireMessageType type() {
            return WireMessageType.PING;
        }

        @Override
        public void write(DataOutputStream out) throws IOException {
            out.writeLong(sentAtMillis);
        }

        public static Ping read(DataInputStream in) throws IOException {
            return new Ping(in.readLong());
        }
    }

    record Pong(long echoMillis) implements WireMessage {
        @Override
        public WireMessageType type() {
            return WireMessageType.PONG;
        }

        @Override
        public void write(DataOutputStream out) throws IOException {
            out.writeLong(echoMillis);
        }

        public static Pong read(DataInputStream in) throws IOException {
            return new Pong(in.readLong());
        }
    }

    record Routed(String sourceServer, String targetServer, int ttl, WireMessageType innerType, byte[] payload) implements WireMessage {
        @Override
        public WireMessageType type() {
            return WireMessageType.ROUTED;
        }

        @Override
        public void write(DataOutputStream out) throws IOException {
            out.writeUTF(sourceServer);
            out.writeUTF(targetServer);
            out.writeByte(ttl);
            out.writeByte(innerType.id());
            WireCodec.writeByteArray(out, payload, WireCodec.MAX_FRAME_BYTES);
        }

        public static Routed read(DataInputStream in) throws IOException {
            String sourceServer = in.readUTF();
            String targetServer = in.readUTF();
            int ttl = in.readUnsignedByte();
            WireMessageType innerType = WireMessageType.byId(in.readByte());
            if (innerType == null || innerType == WireMessageType.ROUTED) {
                throw new IOException("Invalid routed message type");
            }
            byte[] payload = WireCodec.readByteArray(in, WireCodec.MAX_FRAME_BYTES);
            return new Routed(sourceServer, targetServer, ttl, innerType, payload);
        }
    }

    record DictOffer(int version, byte[] hash, int sizeBytes) implements WireMessage {
        @Override
        public WireMessageType type() {
            return WireMessageType.DICT_OFFER;
        }

        @Override
        public void write(DataOutputStream out) throws IOException {
            out.writeInt(version);
            WireCodec.writeFixedBytes(out, hash, CompressionDictionary.HASH_LENGTH);
            out.writeInt(sizeBytes);
        }

        public static DictOffer read(DataInputStream in) throws IOException {
            int version = in.readInt();
            byte[] hash = WireCodec.readFixedBytes(in, CompressionDictionary.HASH_LENGTH);
            int sizeBytes = in.readInt();
            return new DictOffer(version, hash, sizeBytes);
        }
    }

    record DictRequest(int version) implements WireMessage {
        @Override
        public WireMessageType type() {
            return WireMessageType.DICT_REQUEST;
        }

        @Override
        public void write(DataOutputStream out) throws IOException {
            out.writeInt(version);
        }

        public static DictRequest read(DataInputStream in) throws IOException {
            return new DictRequest(in.readInt());
        }
    }

    record DictData(int version, int chunkIndex, int chunkTotal, byte[] hash, byte[] chunk) implements WireMessage {
        public static final int MAX_CHUNK_BYTES = 64 * 1024;

        @Override
        public WireMessageType type() {
            return WireMessageType.DICT_DATA;
        }

        @Override
        public void write(DataOutputStream out) throws IOException {
            out.writeInt(version);
            out.writeInt(chunkIndex);
            out.writeInt(chunkTotal);
            WireCodec.writeFixedBytes(out, hash, CompressionDictionary.HASH_LENGTH);
            WireCodec.writeByteArray(out, chunk, MAX_CHUNK_BYTES);
        }

        public static DictData read(DataInputStream in) throws IOException {
            int version = in.readInt();
            int chunkIndex = in.readInt();
            int chunkTotal = in.readInt();
            byte[] hash = WireCodec.readFixedBytes(in, CompressionDictionary.HASH_LENGTH);
            byte[] chunk = WireCodec.readByteArray(in, MAX_CHUNK_BYTES);
            return new DictData(version, chunkIndex, chunkTotal, hash, chunk);
        }
    }

    record PortalDirectory(List<PortalInfo> portals) implements WireMessage {
        private static final int MAX_PORTALS = 4096;

        @Override
        public WireMessageType type() {
            return WireMessageType.PORTAL_DIRECTORY;
        }

        @Override
        public void write(DataOutputStream out) throws IOException {
            out.writeInt(portals.size());
            for (PortalInfo portal : portals) {
                portal.write(out);
            }
        }

        public static PortalDirectory read(DataInputStream in) throws IOException {
            int count = in.readInt();
            if (count < 0 || count > MAX_PORTALS) {
                throw new IOException("Invalid portal directory size: " + count);
            }
            List<PortalInfo> portals = new ArrayList<>(count);
            for (int i = 0; i < count; i++) {
                portals.add(PortalInfo.read(in));
            }
            return new PortalDirectory(portals);
        }
    }

    record PortalUpsert(PortalInfo portal) implements WireMessage {
        @Override
        public WireMessageType type() {
            return WireMessageType.PORTAL_UPSERT;
        }

        @Override
        public void write(DataOutputStream out) throws IOException {
            portal.write(out);
        }

        public static PortalUpsert read(DataInputStream in) throws IOException {
            return new PortalUpsert(PortalInfo.read(in));
        }
    }

    record PortalRemove(UUID portalId) implements WireMessage {
        @Override
        public WireMessageType type() {
            return WireMessageType.PORTAL_REMOVE;
        }

        @Override
        public void write(DataOutputStream out) throws IOException {
            out.writeLong(portalId.getMostSignificantBits());
            out.writeLong(portalId.getLeastSignificantBits());
        }

        public static PortalRemove read(DataInputStream in) throws IOException {
            return new PortalRemove(new UUID(in.readLong(), in.readLong()));
        }
    }

    record HandoffRequest(UUID transferId, UUID playerId, String playerName, UUID destPortalId, boolean directTransfer, WireTraversive traversive) implements WireMessage {
        @Override
        public WireMessageType type() {
            return WireMessageType.HANDOFF_REQUEST;
        }

        @Override
        public void write(DataOutputStream out) throws IOException {
            writeUuid(out, transferId);
            writeUuid(out, playerId);
            out.writeUTF(playerName);
            writeUuid(out, destPortalId);
            out.writeBoolean(directTransfer);
            traversive.write(out);
        }

        public static HandoffRequest read(DataInputStream in) throws IOException {
            return new HandoffRequest(readUuid(in), readUuid(in), in.readUTF(), readUuid(in), in.readBoolean(), WireTraversive.read(in));
        }
    }

    record HandoffAck(UUID transferId) implements WireMessage {
        @Override
        public WireMessageType type() {
            return WireMessageType.HANDOFF_ACK;
        }

        @Override
        public void write(DataOutputStream out) throws IOException {
            writeUuid(out, transferId);
        }

        public static HandoffAck read(DataInputStream in) throws IOException {
            return new HandoffAck(readUuid(in));
        }
    }

    record HandoffDeny(UUID transferId, String reason, long retryAfterMillis) implements WireMessage {
        @Override
        public WireMessageType type() {
            return WireMessageType.HANDOFF_DENY;
        }

        @Override
        public void write(DataOutputStream out) throws IOException {
            writeUuid(out, transferId);
            out.writeUTF(reason);
            out.writeLong(retryAfterMillis);
        }

        public static HandoffDeny read(DataInputStream in) throws IOException {
            return new HandoffDeny(readUuid(in), in.readUTF(), in.readLong());
        }
    }

    record HandoffCancel(UUID transferId, UUID playerId) implements WireMessage {
        @Override
        public WireMessageType type() {
            return WireMessageType.HANDOFF_CANCEL;
        }

        @Override
        public void write(DataOutputStream out) throws IOException {
            writeUuid(out, transferId);
            writeUuid(out, playerId);
        }

        public static HandoffCancel read(DataInputStream in) throws IOException {
            return new HandoffCancel(readUuid(in), readUuid(in));
        }
    }

    record EntityTransfer(UUID transferId, UUID destPortalId, byte[] entitySnapshot, WireTraversive traversive) implements WireMessage {
        public static final int MAX_SNAPSHOT_BYTES = 256 * 1024;

        @Override
        public WireMessageType type() {
            return WireMessageType.ENTITY_TRANSFER;
        }

        @Override
        public void write(DataOutputStream out) throws IOException {
            writeUuid(out, transferId);
            writeUuid(out, destPortalId);
            WireCodec.writeByteArray(out, entitySnapshot, MAX_SNAPSHOT_BYTES);
            traversive.write(out);
        }

        public static EntityTransfer read(DataInputStream in) throws IOException {
            return new EntityTransfer(readUuid(in), readUuid(in), WireCodec.readByteArray(in, MAX_SNAPSHOT_BYTES), WireTraversive.read(in));
        }
    }

    record EntityTransferAck(UUID transferId, boolean accepted) implements WireMessage {
        @Override
        public WireMessageType type() {
            return WireMessageType.ENTITY_TRANSFER_ACK;
        }

        @Override
        public void write(DataOutputStream out) throws IOException {
            writeUuid(out, transferId);
            out.writeBoolean(accepted);
        }

        public static EntityTransferAck read(DataInputStream in) throws IOException {
            return new EntityTransferAck(readUuid(in), in.readBoolean());
        }
    }

    record ViewSubscribe(UUID portalId) implements WireMessage {
        @Override
        public WireMessageType type() {
            return WireMessageType.VIEW_SUBSCRIBE;
        }

        @Override
        public void write(DataOutputStream out) throws IOException {
            writeUuid(out, portalId);
        }

        public static ViewSubscribe read(DataInputStream in) throws IOException {
            return new ViewSubscribe(readUuid(in));
        }
    }

    record ViewUnsubscribe(UUID portalId) implements WireMessage {
        @Override
        public WireMessageType type() {
            return WireMessageType.VIEW_UNSUBSCRIBE;
        }

        @Override
        public void write(DataOutputStream out) throws IOException {
            writeUuid(out, portalId);
        }

        public static ViewUnsubscribe read(DataInputStream in) throws IOException {
            return new ViewUnsubscribe(readUuid(in));
        }
    }

    record ViewEntities(UUID portalId, List<EntityVisual> entities, List<UUID> presentIds) implements WireMessage {
        private static final int MAX_ENTITIES = 64;
        private static final int MAX_PRESENT = 1024;

        @Override
        public WireMessageType type() {
            return WireMessageType.VIEW_ENTITIES;
        }

        @Override
        public void write(DataOutputStream out) throws IOException {
            writeUuid(out, portalId);
            out.writeByte(entities.size());
            for (EntityVisual entity : entities) {
                entity.write(out);
            }
            out.writeShort(presentIds.size());
            for (UUID id : presentIds) {
                writeUuid(out, id);
            }
        }

        public static ViewEntities read(DataInputStream in) throws IOException {
            UUID portalId = readUuid(in);
            int count = in.readUnsignedByte();
            if (count > MAX_ENTITIES) {
                throw new IOException("Invalid view entity count: " + count);
            }
            List<EntityVisual> entities = new ArrayList<>(count);
            for (int i = 0; i < count; i++) {
                entities.add(EntityVisual.read(in));
            }
            int presentCount = in.readUnsignedShort();
            if (presentCount > MAX_PRESENT) {
                throw new IOException("Invalid present-id count: " + presentCount);
            }
            List<UUID> presentIds = new ArrayList<>(presentCount);
            for (int i = 0; i < presentCount; i++) {
                presentIds.add(readUuid(in));
            }
            return new ViewEntities(portalId, entities, presentIds);
        }
    }

    record ViewEntityAnimation(UUID portalId, UUID entityId, boolean hurt, int animationOrdinal, float yaw) implements WireMessage {
        @Override
        public WireMessageType type() {
            return WireMessageType.VIEW_ENTITY_ANIMATION;
        }

        @Override
        public void write(DataOutputStream out) throws IOException {
            writeUuid(out, portalId);
            writeUuid(out, entityId);
            out.writeBoolean(hurt);
            out.writeByte(animationOrdinal);
            out.writeFloat(yaw);
        }

        public static ViewEntityAnimation read(DataInputStream in) throws IOException {
            return new ViewEntityAnimation(readUuid(in), readUuid(in), in.readBoolean(), in.readUnsignedByte(), in.readFloat());
        }
    }

    record ViewTime(UUID portalId, int skyDarken) implements WireMessage {
        @Override
        public WireMessageType type() {
            return WireMessageType.VIEW_TIME;
        }

        @Override
        public void write(DataOutputStream out) throws IOException {
            writeUuid(out, portalId);
            out.writeByte(skyDarken);
        }

        public static ViewTime read(DataInputStream in) throws IOException {
            return new ViewTime(readUuid(in), in.readUnsignedByte());
        }
    }

    record ChunkBulkBatch(List<ChunkBulk> chunks) implements WireMessage {
        private static final int MAX_CHUNKS = 1024;

        @Override
        public WireMessageType type() {
            return WireMessageType.CHUNK_BULK;
        }

        @Override
        public void write(DataOutputStream out) throws IOException {
            ReplicationVarint.writeUInt(out, chunks.size());
            for (ChunkBulk chunk : chunks) {
                chunk.writeTo(out);
            }
        }

        public static ChunkBulkBatch read(DataInputStream in) throws IOException {
            int count = ReplicationVarint.readUInt(in);
            if (count < 0 || count > MAX_CHUNKS) {
                throw new IOException("Invalid chunk bulk batch count: " + count);
            }
            List<ChunkBulk> chunks = new ArrayList<>(count);
            for (int i = 0; i < count; i++) {
                chunks.add(ChunkBulk.read(in));
            }
            return new ChunkBulkBatch(chunks);
        }
    }

    record ChunkDiff(List<ChunkDiffBatch> batches) implements WireMessage {
        private static final int MAX_BATCHES = 8192;

        @Override
        public WireMessageType type() {
            return WireMessageType.CHUNK_DIFF;
        }

        @Override
        public void write(DataOutputStream out) throws IOException {
            ReplicationVarint.writeUInt(out, batches.size());
            for (ChunkDiffBatch batch : batches) {
                batch.writeTo(out);
            }
        }

        public static ChunkDiff read(DataInputStream in) throws IOException {
            int count = ReplicationVarint.readUInt(in);
            if (count < 0 || count > MAX_BATCHES) {
                throw new IOException("Invalid chunk diff batch count: " + count);
            }
            List<ChunkDiffBatch> batches = new ArrayList<>(count);
            for (int i = 0; i < count; i++) {
                batches.add(ChunkDiffBatch.read(in));
            }
            return new ChunkDiff(batches);
        }
    }

    record ChunkHashProbeMessage(ChunkHashProbe probe) implements WireMessage {
        @Override
        public WireMessageType type() {
            return WireMessageType.CHUNK_HASH_PROBE;
        }

        @Override
        public void write(DataOutputStream out) throws IOException {
            probe.writeTo(out);
        }

        public static ChunkHashProbeMessage read(DataInputStream in) throws IOException {
            return new ChunkHashProbeMessage(ChunkHashProbe.read(in));
        }
    }

    record ViewBulkComplete(UUID portalId) implements WireMessage {
        @Override
        public WireMessageType type() {
            return WireMessageType.VIEW_BULK_COMPLETE;
        }

        @Override
        public void write(DataOutputStream out) throws IOException {
            writeUuid(out, portalId);
        }

        public static ViewBulkComplete read(DataInputStream in) throws IOException {
            return new ViewBulkComplete(readUuid(in));
        }
    }

    record PortalSettingsUpdate(UUID portalId, Map<String, String> settings) implements WireMessage {
        private static final int MAX_ENTRIES = 64;
        private static final int MAX_KEY_BYTES = 64;
        private static final int MAX_VALUE_BYTES = 1024;

        @Override
        public WireMessageType type() {
            return WireMessageType.PORTAL_SETTINGS_UPDATE;
        }

        @Override
        public void write(DataOutputStream out) throws IOException {
            writeUuid(out, portalId);
            if (settings.size() > MAX_ENTRIES) {
                throw new IOException("Portal settings update too large: " + settings.size() + " > " + MAX_ENTRIES);
            }
            out.writeInt(settings.size());
            for (Map.Entry<String, String> entry : settings.entrySet()) {
                String key = entry.getKey();
                String value = entry.getValue() == null ? "" : entry.getValue();
                if (key == null || key.isEmpty()) {
                    throw new IOException("Portal settings key must be non-empty");
                }
                if (key.length() > MAX_KEY_BYTES) {
                    throw new IOException("Portal settings key too long: " + key.length() + " > " + MAX_KEY_BYTES);
                }
                if (value.length() > MAX_VALUE_BYTES) {
                    throw new IOException("Portal settings value too long: " + value.length() + " > " + MAX_VALUE_BYTES);
                }
                out.writeUTF(key);
                out.writeUTF(value);
            }
        }

        public static PortalSettingsUpdate read(DataInputStream in) throws IOException {
            UUID portalId = readUuid(in);
            int count = in.readInt();
            if (count < 0 || count > MAX_ENTRIES) {
                throw new IOException("Invalid portal settings entry count: " + count);
            }
            Map<String, String> settings = new LinkedHashMap<>(count);
            for (int i = 0; i < count; i++) {
                String key = in.readUTF();
                String value = in.readUTF();
                if (key.length() > MAX_KEY_BYTES) {
                    throw new IOException("Portal settings key too long: " + key.length() + " > " + MAX_KEY_BYTES);
                }
                if (value.length() > MAX_VALUE_BYTES) {
                    throw new IOException("Portal settings value too long: " + value.length() + " > " + MAX_VALUE_BYTES);
                }
                settings.put(key, value);
            }
            return new PortalSettingsUpdate(portalId, settings);
        }
    }

    record ChunkResyncRequestMessage(ChunkResyncRequest request) implements WireMessage {
        @Override
        public WireMessageType type() {
            return WireMessageType.CHUNK_RESYNC_REQUEST;
        }

        @Override
        public void write(DataOutputStream out) throws IOException {
            request.writeTo(out);
        }

        public static ChunkResyncRequestMessage read(DataInputStream in) throws IOException {
            return new ChunkResyncRequestMessage(ChunkResyncRequest.read(in));
        }
    }

    record SidebandFragment(long messageId, int index, int total, int frameLength, byte[] chunk) implements WireMessage {
        private static final int MAX_CHUNK_BYTES = 16 * 1024;

        @Override
        public WireMessageType type() {
            return WireMessageType.SIDEBAND_FRAGMENT;
        }

        @Override
        public void write(DataOutputStream out) throws IOException {
            out.writeLong(messageId);
            out.writeInt(index);
            out.writeInt(total);
            out.writeInt(frameLength);
            WireCodec.writeByteArray(out, chunk, MAX_CHUNK_BYTES);
        }

        public static SidebandFragment read(DataInputStream in) throws IOException {
            long messageId = in.readLong();
            int index = in.readInt();
            int total = in.readInt();
            int frameLength = in.readInt();
            byte[] chunk = WireCodec.readByteArray(in, MAX_CHUNK_BYTES);
            return new SidebandFragment(messageId, index, total, frameLength, chunk);
        }
    }

    private static void writeUuid(DataOutputStream out, UUID id) throws IOException {
        out.writeLong(id.getMostSignificantBits());
        out.writeLong(id.getLeastSignificantBits());
    }

    private static UUID readUuid(DataInputStream in) throws IOException {
        return new UUID(in.readLong(), in.readLong());
    }
}
