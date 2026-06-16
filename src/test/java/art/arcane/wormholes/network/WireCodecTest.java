package art.arcane.wormholes.network;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WireCodecTest {
    private static WireMessage roundTrip(WireMessage message) throws IOException {
        byte[] frame = WireCodec.encodeFrame(message);
        return WireCodec.readFrame(new DataInputStream(new ByteArrayInputStream(frame)));
    }

    @Test
    void helloRoundTripPreservesAllFields() throws Exception {
        byte[] nonce = Handshake.newNonce();
        byte[] publicKey = publicKey();
        WireMessage.Hello hello = new WireMessage.Hello(WireCodec.PROTOCOL_VERSION, "1.26.1", "1.0.0", "alpha", "10.0.0.5", 8901, 25565, nonce, publicKey, false, CompressionDictionary.ZERO_HASH, 0);
        WireMessage.Hello decoded = assertInstanceOf(WireMessage.Hello.class, roundTrip(hello));
        assertEquals(WireCodec.PROTOCOL_VERSION, decoded.protocolVersion());
        assertEquals("1.26.1", decoded.mcVersion());
        assertEquals("1.0.0", decoded.pluginVersion());
        assertEquals("alpha", decoded.serverName());
        assertArrayEquals(nonce, decoded.nonce());
        assertArrayEquals(publicKey, decoded.publicKey());
        assertEquals(false, decoded.compressionSupported());
        assertArrayEquals(CompressionDictionary.ZERO_HASH, decoded.currentDictHash());
        assertEquals(0, decoded.currentDictVersion());
    }

    @Test
    void challengeRoundTripPreservesAllFields() throws Exception {
        byte[] nonce = Handshake.newNonce();
        byte[] publicKey = publicKey();
        byte[] signature = new byte[] {1, 2, 3, 4};
        WireMessage.Challenge challenge = new WireMessage.Challenge("beta", "10.0.0.2", 8901, 25565, nonce, publicKey, signature, true, CompressionDictionary.ZERO_HASH, 0);
        WireMessage.Challenge decoded = assertInstanceOf(WireMessage.Challenge.class, roundTrip(challenge));
        assertEquals("beta", decoded.serverName());
        assertEquals("10.0.0.2", decoded.advertiseHost());
        assertEquals(8901, decoded.wormholePort());
        assertEquals(25565, decoded.gamePort());
        assertArrayEquals(nonce, decoded.nonce());
        assertArrayEquals(publicKey, decoded.publicKey());
        assertArrayEquals(signature, decoded.signature());
        assertEquals(true, decoded.compressionSupported());
        assertArrayEquals(CompressionDictionary.ZERO_HASH, decoded.currentDictHash());
        assertEquals(0, decoded.currentDictVersion());
    }

    @Test
    void authReadyPingPongRoundTrip() throws IOException {
        byte[] signature = new byte[] {5, 6, 7, 8};
        WireMessage.Auth auth = assertInstanceOf(WireMessage.Auth.class, roundTrip(new WireMessage.Auth(signature)));
        assertArrayEquals(signature, auth.signature());

        assertInstanceOf(WireMessage.Ready.class, roundTrip(new WireMessage.Ready()));

        WireMessage.Ping ping = assertInstanceOf(WireMessage.Ping.class, roundTrip(new WireMessage.Ping(123456789L)));
        assertEquals(123456789L, ping.sentAtMillis());

        WireMessage.Pong pong = assertInstanceOf(WireMessage.Pong.class, roundTrip(new WireMessage.Pong(987654321L)));
        assertEquals(987654321L, pong.echoMillis());
    }

    @Test
    void largePayloadIsCompressedAndRoundTrips() throws Exception {
        String bigVersion = "x".repeat(50_000);
        WireMessage.Hello hello = new WireMessage.Hello(WireCodec.PROTOCOL_VERSION, "1.26.1", bigVersion, "alpha", "10.0.0.5", 8901, 25565, Handshake.newNonce(), publicKey(), true, CompressionDictionary.ZERO_HASH, 0);
        WireCompression compression = new WireCompression(WireCompression.DEFAULT_LEVEL);
        try {
            byte[] frame = WireCodec.encodeFrame(hello, compression, false);
            assertTrue(frame.length < 10_000, "highly repetitive payload should compress well, frame was " + frame.length);
            WireMessage.Hello decoded = assertInstanceOf(WireMessage.Hello.class, WireCodec.readFrame(new DataInputStream(new ByteArrayInputStream(frame)), compression));
            assertEquals(bigVersion, decoded.pluginVersion());
        } finally {
            compression.close();
        }
    }

    @Test
    void oversizedFrameLengthIsRejected() {
        byte[] bogus = new byte[]{0x7F, 0x7F, 0x7F, 0x7F, 0, 0};
        assertThrows(IOException.class, () -> WireCodec.readFrame(new DataInputStream(new ByteArrayInputStream(bogus))));
    }

    @Test
    void unknownTypeIdIsRejected() {
        byte[] frame = new byte[]{0, 0, 0, 2, (byte) 200, 0};
        assertThrows(IOException.class, () -> WireCodec.readFrame(new DataInputStream(new ByteArrayInputStream(frame))));
    }

    @Test
    void truncatedFrameIsRejected() throws IOException {
        byte[] frame = WireCodec.encodeFrame(new WireMessage.Ping(42L));
        byte[] truncated = new byte[frame.length - 4];
        System.arraycopy(frame, 0, truncated, 0, truncated.length);
        assertThrows(IOException.class, () -> WireCodec.readFrame(new DataInputStream(new ByteArrayInputStream(truncated))));
    }

    @Test
    void byteArrayHelpersEnforceCaps() throws IOException {
        java.io.ByteArrayOutputStream buffer = new java.io.ByteArrayOutputStream();
        java.io.DataOutputStream out = new java.io.DataOutputStream(buffer);
        byte[] payload = new byte[256];
        WireCodec.writeByteArray(out, payload, 256);
        assertThrows(IOException.class, () -> WireCodec.writeByteArray(out, payload, 255));

        DataInputStream in = new DataInputStream(new ByteArrayInputStream(buffer.toByteArray()));
        assertThrows(IOException.class, () -> WireCodec.readByteArray(in, 255));
    }

    private static byte[] publicKey() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("Ed25519");
        KeyPair keyPair = generator.generateKeyPair();
        return keyPair.getPublic().getEncoded();
    }
}
