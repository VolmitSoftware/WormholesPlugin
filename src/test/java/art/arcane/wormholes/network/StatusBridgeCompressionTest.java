package art.arcane.wormholes.network;

import art.arcane.wormholes.network.replication.ChunkBulk;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.lang.reflect.Method;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StatusBridgeCompressionTest {
    private static final int SMALL_PAYLOAD_BYTES = 600;
    private static final int LARGE_CHUNK_PAYLOAD_BYTES = 12_000;
    private static final int FRAGMENT_CHUNK_BYTES = 4 * 1024;

    @Test
    void severalSmallMessagesCompressAndRoundTripWithSignature() throws Exception {
        KeyPair keyPair = keyPair();
        WireCompression encodeSide = new WireCompression(WireCompression.DEFAULT_LEVEL);
        WireCompression decodeSide = new WireCompression(WireCompression.DEFAULT_LEVEL);
        List<MinecraftStatusBridge.EncodedMessage> messages = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            WireMessage.Routed routed = new WireMessage.Routed("alpha", "beta", 4, WireMessageType.PING, repeating((byte) ('a' + i), SMALL_PAYLOAD_BYTES));
            messages.add(new MinecraftStatusBridge.EncodedMessage(routed, WireCodec.encodeFrame(routed)));
        }

        MinecraftStatusBridge.StatusPacket packet = MinecraftStatusBridge.create(
            "alpha", "beta", "26.2", "1.0.0", "10.0.0.5", 25565,
            keyPair.getPublic().getEncoded(), keyPair.getPrivate(), messages);

        byte[] plainUnsigned = unsignedBytes(packet);
        String encoded = packet.encode(encodeSide);
        byte[] transport = transportBlob(encoded);

        assertTrue(transport.length > 0 && transport[0] == WireCompression.MODE_ZSTD_DICTLESS, "assembled status packet should be transported zstd-compressed");
        assertTrue(transport.length < plainUnsigned.length, "compressed transport blob must be smaller than the plain unsigned bytes");

        MinecraftStatusBridge.StatusPacket decoded = MinecraftStatusBridge.StatusPacket.decode(encoded, decodeSide);
        assertArrayEquals(plainUnsigned, unsignedBytes(decoded), "decoded packet must recover byte-identical plain unsigned bytes");
        assertTrue(decoded.verify(), "signature must still verify against the plain unsigned bytes after compression round-trip");

        assertEquals("alpha", decoded.sourceServer());
        assertEquals("beta", decoded.targetServer());
        assertEquals("26.2", decoded.mcVersion());
        assertEquals("1.0.0", decoded.pluginVersion());
        assertEquals("10.0.0.5", decoded.replyHost());
        assertEquals(25565, decoded.replyPort());
        assertArrayEquals(keyPair.getPublic().getEncoded(), decoded.publicKey());

        assertEquals(messages.size(), decoded.messages().size());
        for (int i = 0; i < messages.size(); i++) {
            WireMessage.Routed original = assertInstanceOf(WireMessage.Routed.class, messages.get(i).message());
            WireMessage.Routed result = assertInstanceOf(WireMessage.Routed.class, decoded.messages().get(i));
            assertEquals(original.sourceServer(), result.sourceServer());
            assertEquals(original.targetServer(), result.targetServer());
            assertEquals(original.ttl(), result.ttl());
            assertEquals(original.innerType(), result.innerType());
            assertArrayEquals(original.payload(), result.payload());
        }
    }

    @Test
    void oversizedFragmentedChunkBulkRoundTripsThroughCompressedPacket() throws Exception {
        KeyPair keyPair = keyPair();
        WireCompression encodeSide = new WireCompression(WireCompression.DEFAULT_LEVEL);
        WireCompression decodeSide = new WireCompression(WireCompression.DEFAULT_LEVEL);

        WireMessage.ChunkBulkBatch bulk = new WireMessage.ChunkBulkBatch(List.of(
            new ChunkBulk(0x1122334455667788L, 7L, patternedBytes(LARGE_CHUNK_PAYLOAD_BYTES))));
        byte[] plainFrame = WireCodec.encodeFrame(bulk);
        assertTrue(plainFrame.length > MinecraftStatusBridge.MAX_FRAME_BYTES, "frame must exceed the per-frame budget to force fragmentation");

        byte[] compressedFrame = encodeSide.encode(plainFrame, false);
        List<MinecraftStatusBridge.EncodedMessage> fragments = fragmentMessages(compressedFrame);
        assertTrue(fragments.size() > 1, "oversized frame should fragment into multiple sideband messages");

        MinecraftStatusBridge.StatusPacket packet = MinecraftStatusBridge.create(
            "alpha", "beta", "26.2", "1.0.0", "10.0.0.5", 25565,
            keyPair.getPublic().getEncoded(), keyPair.getPrivate(), fragments);

        String encoded = packet.encode(encodeSide);
        MinecraftStatusBridge.StatusPacket decoded = MinecraftStatusBridge.StatusPacket.decode(encoded, decodeSide);

        assertTrue(decoded.verify(), "fragmented packet signature must still verify after whole-packet compression");
        assertEquals(fragments.size(), decoded.messages().size());

        byte[] reassembled = reassembleFragments(decoded.messages());
        byte[] recoveredFrame = decodeSide.decode(reassembled).payload();
        assertArrayEquals(plainFrame, recoveredFrame, "reassembled+decompressed CHUNK_BULK frame must equal the original");

        WireMessage recoveredMessage = WireCodec.readFrame(new DataInputStream(new ByteArrayInputStream(recoveredFrame)));
        WireMessage.ChunkBulkBatch recoveredBulk = assertInstanceOf(WireMessage.ChunkBulkBatch.class, recoveredMessage);
        assertEquals(1, recoveredBulk.chunks().size());
        assertEquals(bulk.chunks().get(0).chunkKey(), recoveredBulk.chunks().get(0).chunkKey());
        assertEquals(bulk.chunks().get(0).sequence(), recoveredBulk.chunks().get(0).sequence());
        assertArrayEquals(bulk.chunks().get(0).bulkPayload(), recoveredBulk.chunks().get(0).bulkPayload());
    }

    @Test
    void maxMessagesRoundTripAtRaisedCap() throws Exception {
        KeyPair keyPair = keyPair();
        WireCompression encodeSide = new WireCompression(WireCompression.DEFAULT_LEVEL);
        WireCompression decodeSide = new WireCompression(WireCompression.DEFAULT_LEVEL);
        assertEquals(64, MinecraftStatusBridge.MAX_MESSAGES);
        List<MinecraftStatusBridge.EncodedMessage> messages = new ArrayList<>(MinecraftStatusBridge.MAX_MESSAGES);
        for (int i = 0; i < MinecraftStatusBridge.MAX_MESSAGES; i++) {
            WireMessage.Routed routed = new WireMessage.Routed("alpha", "beta", 4, WireMessageType.PING, repeating((byte) ('a' + (i % 26)), 200));
            messages.add(new MinecraftStatusBridge.EncodedMessage(routed, WireCodec.encodeFrame(routed)));
        }

        MinecraftStatusBridge.StatusPacket packet = MinecraftStatusBridge.create(
            "alpha", "beta", "26.2", "1.0.0", "10.0.0.5", 25565,
            keyPair.getPublic().getEncoded(), keyPair.getPrivate(), messages);

        String encoded = packet.encode(encodeSide);
        assertTrue(encoded.length() < MinecraftStatusBridge.MAX_ENCODED_CHARS, "a full 64-message packet must stay under the encoded cap, got " + encoded.length());

        MinecraftStatusBridge.StatusPacket decoded = MinecraftStatusBridge.StatusPacket.decode(encoded, decodeSide);
        assertEquals(MinecraftStatusBridge.MAX_MESSAGES, decoded.messages().size());
        assertTrue(decoded.verify(), "signature must verify after a max-message round trip");
    }

    private static List<MinecraftStatusBridge.EncodedMessage> fragmentMessages(byte[] frame) throws IOException {
        int total = (frame.length + FRAGMENT_CHUNK_BYTES - 1) / FRAGMENT_CHUNK_BYTES;
        List<MinecraftStatusBridge.EncodedMessage> fragments = new ArrayList<>(total);
        for (int index = 0; index < total; index++) {
            int offset = index * FRAGMENT_CHUNK_BYTES;
            int length = Math.min(FRAGMENT_CHUNK_BYTES, frame.length - offset);
            byte[] chunk = Arrays.copyOfRange(frame, offset, offset + length);
            WireMessage.SidebandFragment fragment = new WireMessage.SidebandFragment(99L, index, total, frame.length, chunk);
            fragments.add(new MinecraftStatusBridge.EncodedMessage(fragment, WireCodec.encodeFrame(fragment)));
        }
        return fragments;
    }

    private static byte[] reassembleFragments(List<WireMessage> messages) {
        int frameLength = ((WireMessage.SidebandFragment) messages.get(0)).frameLength();
        byte[] frame = new byte[frameLength];
        for (WireMessage message : messages) {
            WireMessage.SidebandFragment fragment = (WireMessage.SidebandFragment) message;
            System.arraycopy(fragment.chunk(), 0, frame, fragment.index() * FRAGMENT_CHUNK_BYTES, fragment.chunk().length);
        }
        return frame;
    }

    private static byte[] transportBlob(String encoded) throws IOException {
        byte[] envelope = Base64.getUrlDecoder().decode(encoded);
        DataInputStream in = new DataInputStream(new ByteArrayInputStream(envelope));
        return WireCodec.readByteArray(in, MinecraftStatusBridge.MAX_PACKET_BYTES);
    }

    private static byte[] unsignedBytes(MinecraftStatusBridge.StatusPacket packet) throws Exception {
        Method method = MinecraftStatusBridge.StatusPacket.class.getDeclaredMethod("unsignedBytes");
        method.setAccessible(true);
        return (byte[]) method.invoke(packet);
    }

    private static byte[] repeating(byte value, int length) {
        byte[] bytes = new byte[length];
        Arrays.fill(bytes, value);
        return bytes;
    }

    private static byte[] patternedBytes(int length) {
        byte[] bytes = new byte[length];
        new SecureRandom().nextBytes(bytes);
        return bytes;
    }

    private static KeyPair keyPair() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("Ed25519");
        return generator.generateKeyPair();
    }
}
