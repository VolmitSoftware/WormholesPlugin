package art.arcane.wormholes.network;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import art.arcane.wormholes.network.view.EntityVisual;
import art.arcane.wormholes.network.view.ViewBox;
import art.arcane.wormholes.network.view.ViewSlice;

public sealed interface WireMessage {
    WireMessageType type();

    void write(DataOutputStream out) throws IOException;

    record Hello(int protocolVersion, String mcVersion, String pluginVersion, String serverName, String advertiseHost, int wormholePort, int gamePort, byte[] nonce) implements WireMessage {
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
            return new Hello(protocolVersion, mcVersion, pluginVersion, serverName, advertiseHost, wormholePort, gamePort, nonce);
        }
    }

    record Challenge(String serverName, byte[] nonce, byte[] mac) implements WireMessage {
        @Override
        public WireMessageType type() {
            return WireMessageType.CHALLENGE;
        }

        @Override
        public void write(DataOutputStream out) throws IOException {
            out.writeUTF(serverName);
            WireCodec.writeFixedBytes(out, nonce, Handshake.NONCE_LENGTH);
            WireCodec.writeFixedBytes(out, mac, Handshake.MAC_LENGTH);
        }

        public static Challenge read(DataInputStream in) throws IOException {
            String serverName = in.readUTF();
            byte[] nonce = WireCodec.readFixedBytes(in, Handshake.NONCE_LENGTH);
            byte[] mac = WireCodec.readFixedBytes(in, Handshake.MAC_LENGTH);
            return new Challenge(serverName, nonce, mac);
        }
    }

    record Auth(byte[] mac) implements WireMessage {
        @Override
        public WireMessageType type() {
            return WireMessageType.AUTH;
        }

        @Override
        public void write(DataOutputStream out) throws IOException {
            WireCodec.writeFixedBytes(out, mac, Handshake.MAC_LENGTH);
        }

        public static Auth read(DataInputStream in) throws IOException {
            return new Auth(WireCodec.readFixedBytes(in, Handshake.MAC_LENGTH));
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

    record HandoffRequest(UUID transferId, UUID playerId, String playerName, UUID destPortalId, WireTraversive traversive) implements WireMessage {
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
            traversive.write(out);
        }

        public static HandoffRequest read(DataInputStream in) throws IOException {
            return new HandoffRequest(readUuid(in), readUuid(in), in.readUTF(), readUuid(in), WireTraversive.read(in));
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

    record HandoffDeny(UUID transferId, String reason) implements WireMessage {
        @Override
        public WireMessageType type() {
            return WireMessageType.HANDOFF_DENY;
        }

        @Override
        public void write(DataOutputStream out) throws IOException {
            writeUuid(out, transferId);
            out.writeUTF(reason);
        }

        public static HandoffDeny read(DataInputStream in) throws IOException {
            return new HandoffDeny(readUuid(in), in.readUTF());
        }
    }

    record HandoffCancel(UUID playerId) implements WireMessage {
        @Override
        public WireMessageType type() {
            return WireMessageType.HANDOFF_CANCEL;
        }

        @Override
        public void write(DataOutputStream out) throws IOException {
            writeUuid(out, playerId);
        }

        public static HandoffCancel read(DataInputStream in) throws IOException {
            return new HandoffCancel(readUuid(in));
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

    record ViewSnapshot(UUID portalId, ViewBox box, List<ViewSlice> slices) implements WireMessage {
        private static final int MAX_SLICES = 4096;

        @Override
        public WireMessageType type() {
            return WireMessageType.VIEW_SNAPSHOT;
        }

        @Override
        public void write(DataOutputStream out) throws IOException {
            writeUuid(out, portalId);
            box.write(out);
            out.writeInt(slices.size());
            for (ViewSlice slice : slices) {
                slice.write(out);
            }
        }

        public static ViewSnapshot read(DataInputStream in) throws IOException {
            UUID portalId = readUuid(in);
            ViewBox box = ViewBox.read(in);
            int count = in.readInt();
            if (count < 0 || count > MAX_SLICES) {
                throw new IOException("Invalid view snapshot slice count: " + count);
            }
            List<ViewSlice> slices = new ArrayList<>(count);
            for (int i = 0; i < count; i++) {
                slices.add(ViewSlice.read(in));
            }
            return new ViewSnapshot(portalId, box, slices);
        }
    }

    record ViewDelta(UUID portalId, List<ViewSlice> slices) implements WireMessage {
        private static final int MAX_SLICES = 4096;

        @Override
        public WireMessageType type() {
            return WireMessageType.VIEW_DELTA;
        }

        @Override
        public void write(DataOutputStream out) throws IOException {
            writeUuid(out, portalId);
            out.writeInt(slices.size());
            for (ViewSlice slice : slices) {
                slice.write(out);
            }
        }

        public static ViewDelta read(DataInputStream in) throws IOException {
            UUID portalId = readUuid(in);
            int count = in.readInt();
            if (count < 0 || count > MAX_SLICES) {
                throw new IOException("Invalid view delta slice count: " + count);
            }
            List<ViewSlice> slices = new ArrayList<>(count);
            for (int i = 0; i < count; i++) {
                slices.add(ViewSlice.read(in));
            }
            return new ViewDelta(portalId, slices);
        }
    }

    record ViewEntities(UUID portalId, List<EntityVisual> entities) implements WireMessage {
        private static final int MAX_ENTITIES = 64;

        @Override
        public WireMessageType type() {
            return WireMessageType.VIEW_ENTITIES;
        }

        @Override
        public void write(DataOutputStream out) throws IOException {
            writeUuid(out, portalId);
            out.writeInt(entities.size());
            for (EntityVisual entity : entities) {
                entity.write(out);
            }
        }

        public static ViewEntities read(DataInputStream in) throws IOException {
            UUID portalId = readUuid(in);
            int count = in.readInt();
            if (count < 0 || count > MAX_ENTITIES) {
                throw new IOException("Invalid view entity count: " + count);
            }
            List<EntityVisual> entities = new ArrayList<>(count);
            for (int i = 0; i < count; i++) {
                entities.add(EntityVisual.read(in));
            }
            return new ViewEntities(portalId, entities);
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

    private static void writeUuid(DataOutputStream out, UUID id) throws IOException {
        out.writeLong(id.getMostSignificantBits());
        out.writeLong(id.getLeastSignificantBits());
    }

    private static UUID readUuid(DataInputStream in) throws IOException {
        return new UUID(in.readLong(), in.readLong());
    }
}
