package art.arcane.wormholes.network;

import art.arcane.wormholes.config.toml.NetworkConfig;
import com.github.retrooper.packetevents.event.PacketListenerAbstract;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.protocol.player.ClientVersion;
import com.github.retrooper.packetevents.wrapper.handshaking.client.WrapperHandshakingClientHandshake;
import com.github.retrooper.packetevents.wrapper.status.server.WrapperStatusServerResponse;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.PrivateKey;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class MinecraftStatusBridge extends PacketListenerAbstract {
    private static final String HOST_PREFIX = "whs.";
    private static final String JSON_FIELD = "wormholes";
    private static final int FORMAT_VERSION = 1;
    private static final int CONNECT_TIMEOUT_MS = 4000;
    private static final int READ_TIMEOUT_MS = 5000;
    private static final int MAX_HOST_LENGTH = 32000;
    private static final int MAX_STATUS_RESPONSE_CHARS = 32767;
    static final int MAX_ENCODED_CHARS = 30000;
    static final int MAX_PACKET_BYTES = 24000;
    static final int MAX_FRAME_BYTES = 20000;
    static final int MAX_MESSAGES = 16;
    private static final long PENDING_TTL_MS = 10_000L;
    private static final int MAX_PENDING_REQUESTS = 1024;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final NetworkManager network;
    private final Map<Object, PendingRequest> pending = new ConcurrentHashMap<>();

    public MinecraftStatusBridge(NetworkManager network) {
        this.network = network;
    }

    @Override
    public void onPacketReceive(PacketReceiveEvent event) {
        if (event.getPacketType() != PacketType.Handshaking.Client.HANDSHAKE) {
            return;
        }
        try {
            WrapperHandshakingClientHandshake handshake = new WrapperHandshakingClientHandshake(event);
            if (handshake.getIntention() != WrapperHandshakingClientHandshake.ConnectionIntention.STATUS) {
                return;
            }
            String address = handshake.getServerAddress();
            if (address == null || !address.startsWith(HOST_PREFIX)) {
                return;
            }
            StatusPacket packet = StatusPacket.decode(address.substring(HOST_PREFIX.length()));
            long now = System.currentTimeMillis();
            purgePending(now);
            if (pending.size() >= MAX_PENDING_REQUESTS) {
                return;
            }
            pending.put(event.getChannel(), new PendingRequest(packet, now));
        } catch (IOException | RuntimeException ignored) {
        }
    }

    @Override
    public void onPacketSend(PacketSendEvent event) {
        if (event.getPacketType() != PacketType.Status.Server.RESPONSE) {
            return;
        }
        PendingRequest pendingRequest = pending.remove(event.getChannel());
        if (pendingRequest == null) {
            return;
        }
        try {
            StatusPacket request = pendingRequest.packet();
            StatusPacket response = network.handleStatusBridgeRequest(request);
            if (response == null) {
                return;
            }
            String encoded = response.encode();
            if (encoded.length() > MAX_ENCODED_CHARS) {
                throw new IllegalStateException("status sideband response is too large: " + encoded.length() + " chars");
            }
            WrapperStatusServerResponse wrapper = new WrapperStatusServerResponse(event);
            JsonObject component = wrapper.getComponent();
            component.addProperty(JSON_FIELD, encoded);
            wrapper.setComponent(component);
            event.markForReEncode(true);
        } catch (RuntimeException e) {
            network.logStatusBridgeFailure("status sideband response failed", e);
        }
    }

    private void purgePending(long now) {
        pending.entrySet().removeIf(entry -> now - entry.getValue().createdAtMillis() > PENDING_TTL_MS);
    }

    public StatusPacket poll(NetworkConfig.PeerEntry peer, StatusPacket request) throws IOException {
        String host = peer.publicHost == null || peer.publicHost.isBlank() ? peer.host : peer.publicHost;
        int port = peer.publicPort > 0 ? peer.publicPort : 25565;
        if (host == null || host.isBlank()) {
            throw new IOException("no game-port host available");
        }
        String encoded = request.encode();
        String handshakeHost = HOST_PREFIX + encoded;
        if (handshakeHost.length() > MAX_HOST_LENGTH) {
            throw new IOException("status sideband request is too large: " + handshakeHost.length() + " chars");
        }
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), CONNECT_TIMEOUT_MS);
            socket.setSoTimeout(READ_TIMEOUT_MS);
            OutputStream output = socket.getOutputStream();
            InputStream input = socket.getInputStream();
            writePacket(output, handshakePacket(handshakeHost, port));
            writePacket(output, statusRequestPacket());
            String responseJson = readStatusResponse(input);
            JsonObject root = JsonParser.parseString(responseJson).getAsJsonObject();
            if (!root.has(JSON_FIELD)) {
                throw new IOException("status response did not include Wormholes sideband data");
            }
            return StatusPacket.decode(root.get(JSON_FIELD).getAsString());
        }
    }

    public static StatusPacket create(String sourceServer, String targetServer, String mcVersion, String pluginVersion, String replyHost, int replyPort, byte[] publicKey, PrivateKey privateKey, List<EncodedMessage> messages) {
        List<WireMessage> wireMessages = new ArrayList<>(messages.size());
        List<byte[]> frames = new ArrayList<>(messages.size());
        for (EncodedMessage message : messages) {
            wireMessages.add(message.message());
            frames.add(message.frame());
        }
        StatusPacket unsigned = new StatusPacket(sourceServer, targetServer, mcVersion, pluginVersion, replyHost, replyPort, publicKey, RANDOM.nextLong(), List.copyOf(wireMessages), List.copyOf(frames), null);
        byte[] payload = unsigned.unsignedBytes();
        return unsigned.withSignature(Handshake.sign(privateKey, payload));
    }

    private static byte[] handshakePacket(String host, int port) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream(256);
        DataOutputStream out = new DataOutputStream(buffer);
        writeVarInt(out, 0);
        writeVarInt(out, ClientVersion.getLatest().getProtocolVersion());
        writeString(out, host);
        out.writeShort(port);
        writeVarInt(out, 1);
        out.flush();
        return buffer.toByteArray();
    }

    private static byte[] statusRequestPacket() throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream(4);
        DataOutputStream out = new DataOutputStream(buffer);
        writeVarInt(out, 0);
        out.flush();
        return buffer.toByteArray();
    }

    private static String readStatusResponse(InputStream input) throws IOException {
        int packetLength = readVarInt(input);
        if (packetLength <= 0 || packetLength > MAX_STATUS_RESPONSE_CHARS + 8) {
            throw new IOException("invalid status response length: " + packetLength);
        }
        byte[] packet = input.readNBytes(packetLength);
        if (packet.length != packetLength) {
            throw new EOFException("truncated status response");
        }
        DataInputStream in = new DataInputStream(new ByteArrayInputStream(packet));
        int packetId = readVarInt(in);
        if (packetId != 0) {
            throw new IOException("unexpected status response packet id: " + packetId);
        }
        return readString(in, MAX_STATUS_RESPONSE_CHARS);
    }

    private static void writePacket(OutputStream output, byte[] packet) throws IOException {
        writeVarInt(output, packet.length);
        output.write(packet);
        output.flush();
    }

    private static void writeString(DataOutputStream out, String value) throws IOException {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        writeVarInt(out, bytes.length);
        out.write(bytes);
    }

    private static String readString(DataInputStream in, int maxLength) throws IOException {
        int length = readVarInt(in);
        if (length < 0 || length > maxLength) {
            throw new IOException("invalid string length: " + length);
        }
        byte[] bytes = new byte[length];
        in.readFully(bytes);
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private static void writeVarInt(OutputStream output, int value) throws IOException {
        int remaining = value;
        while ((remaining & 0xFFFFFF80) != 0) {
            output.write((remaining & 0x7F) | 0x80);
            remaining >>>= 7;
        }
        output.write(remaining);
    }

    private static int readVarInt(InputStream input) throws IOException {
        int value = 0;
        int position = 0;
        while (position < 35) {
            int current = input.read();
            if (current < 0) {
                throw new EOFException("truncated varint");
            }
            value |= (current & 0x7F) << position;
            if ((current & 0x80) == 0) {
                return value;
            }
            position += 7;
        }
        throw new IOException("varint too large");
    }

    private record PendingRequest(StatusPacket packet, long createdAtMillis) {
    }

    public record EncodedMessage(WireMessage message, byte[] frame) {
    }

    public static final class StatusPacket {
        private final String sourceServer;
        private final String targetServer;
        private final String mcVersion;
        private final String pluginVersion;
        private final String replyHost;
        private final int replyPort;
        private final byte[] publicKey;
        private final long nonce;
        private final List<WireMessage> messages;
        private final List<byte[]> encodedFrames;
        private final byte[] signature;

        private StatusPacket(String sourceServer, String targetServer, String mcVersion, String pluginVersion, String replyHost, int replyPort, byte[] publicKey, long nonce, List<WireMessage> messages, List<byte[]> encodedFrames, byte[] signature) {
            this.sourceServer = sourceServer;
            this.targetServer = targetServer;
            this.mcVersion = mcVersion;
            this.pluginVersion = pluginVersion;
            this.replyHost = replyHost;
            this.replyPort = replyPort;
            this.publicKey = publicKey == null ? new byte[0] : publicKey.clone();
            this.nonce = nonce;
            this.messages = List.copyOf(messages);
            this.encodedFrames = encodedFrames == null ? null : List.copyOf(encodedFrames);
            this.signature = signature == null ? new byte[0] : signature.clone();
        }

        public String sourceServer() {
            return sourceServer;
        }

        public String targetServer() {
            return targetServer;
        }

        public String mcVersion() {
            return mcVersion;
        }

        public String pluginVersion() {
            return pluginVersion;
        }

        public String replyHost() {
            return replyHost;
        }

        public int replyPort() {
            return replyPort;
        }

        public byte[] publicKey() {
            return publicKey.clone();
        }

        public List<WireMessage> messages() {
            return messages;
        }

        public boolean verify() {
            return Handshake.verify(publicKey, signature, unsignedBytes());
        }

        public String encode() {
            try {
                byte[] unsigned = unsignedBytes();
                ByteArrayOutputStream buffer = new ByteArrayOutputStream(unsigned.length + signature.length + 16);
                DataOutputStream out = new DataOutputStream(buffer);
                WireCodec.writeByteArray(out, unsigned, MAX_PACKET_BYTES);
                WireCodec.writeByteArray(out, signature, Handshake.SIGNATURE_MAX_LENGTH);
                out.flush();
                return Base64.getUrlEncoder().withoutPadding().encodeToString(buffer.toByteArray());
            } catch (IOException e) {
                throw new IllegalStateException("Could not encode status bridge packet", e);
            }
        }

        private StatusPacket withSignature(byte[] nextSignature) {
            return new StatusPacket(sourceServer, targetServer, mcVersion, pluginVersion, replyHost, replyPort, publicKey, nonce, messages, encodedFrames, nextSignature);
        }

        private byte[] unsignedBytes() {
            try {
                ByteArrayOutputStream buffer = new ByteArrayOutputStream(512);
                DataOutputStream out = new DataOutputStream(buffer);
                out.writeInt(FORMAT_VERSION);
                out.writeUTF(sourceServer == null ? "" : sourceServer);
                out.writeUTF(targetServer == null ? "" : targetServer);
                out.writeUTF(mcVersion == null ? "" : mcVersion);
                out.writeUTF(pluginVersion == null ? "" : pluginVersion);
                out.writeUTF(replyHost == null ? "" : replyHost);
                out.writeShort(Math.max(0, Math.min(65535, replyPort)));
                WireCodec.writeByteArray(out, publicKey, Handshake.PUBLIC_KEY_MAX_LENGTH);
                out.writeLong(nonce);
                out.writeInt(Math.min(messages.size(), MAX_MESSAGES));
                int written = 0;
                for (int i = 0; i < messages.size(); i++) {
                    if (written >= MAX_MESSAGES) {
                        break;
                    }
                    byte[] frame = encodedFrames == null ? WireCodec.encodeFrame(messages.get(i)) : encodedFrames.get(i);
                    WireCodec.writeByteArray(out, frame, MAX_FRAME_BYTES);
                    written++;
                }
                out.flush();
                return buffer.toByteArray();
            } catch (IOException e) {
                throw new IllegalStateException("Could not encode status bridge payload", e);
            }
        }

        public static StatusPacket decode(String encoded) throws IOException {
            byte[] envelope = Base64.getUrlDecoder().decode(encoded);
            DataInputStream envelopeIn = new DataInputStream(new ByteArrayInputStream(envelope));
            byte[] unsigned = WireCodec.readByteArray(envelopeIn, MAX_PACKET_BYTES);
            byte[] signature = WireCodec.readByteArray(envelopeIn, Handshake.SIGNATURE_MAX_LENGTH);
            DataInputStream in = new DataInputStream(new ByteArrayInputStream(unsigned));
            int version = in.readInt();
            if (version != FORMAT_VERSION) {
                throw new IOException("unsupported status bridge packet version: " + version);
            }
            String sourceServer = in.readUTF();
            String targetServer = in.readUTF();
            String mcVersion = in.readUTF();
            String pluginVersion = in.readUTF();
            String replyHost = in.readUTF();
            int replyPort = in.readUnsignedShort();
            byte[] publicKey = WireCodec.readByteArray(in, Handshake.PUBLIC_KEY_MAX_LENGTH);
            long nonce = in.readLong();
            int messageCount = in.readInt();
            if (messageCount < 0 || messageCount > MAX_MESSAGES) {
                throw new IOException("invalid status bridge message count: " + messageCount);
            }
            List<WireMessage> messages = new ArrayList<>(messageCount);
            for (int i = 0; i < messageCount; i++) {
                byte[] frame = WireCodec.readByteArray(in, MAX_FRAME_BYTES);
                messages.add(WireCodec.readFrame(new DataInputStream(new ByteArrayInputStream(frame))));
            }
            return new StatusPacket(sourceServer, targetServer, mcVersion, pluginVersion, replyHost, replyPort, publicKey, nonce, messages, null, signature);
        }
    }
}
