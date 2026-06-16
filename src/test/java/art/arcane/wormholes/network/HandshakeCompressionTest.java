package art.arcane.wormholes.network;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HandshakeCompressionTest {
    private static byte[] generatePublicKey() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("Ed25519");
        KeyPair keyPair = generator.generateKeyPair();
        return keyPair.getPublic().getEncoded();
    }

    private static CompressionDictionary trainDictionary(int version) {
        return trainDictionary(version, 0xDEADBEEFL);
    }

    private static CompressionDictionary trainDictionary(int version, long seed) {
        List<byte[]> samples = new ArrayList<>();
        Random random = new Random(seed);
        for (int i = 0; i < 256; i++) {
            byte[] sample = new byte[1024 + random.nextInt(1024)];
            for (int j = 0; j < sample.length; j++) {
                sample[j] = (byte) ('a' + random.nextInt(8));
            }
            samples.add(sample);
        }
        return CompressionDictionary.train(samples, 8 * 1024, version);
    }

    private static boolean negotiateUseDict(WireMessage.Hello local, WireMessage.Hello remote) {
        if (!local.compressionSupported() || !remote.compressionSupported()) {
            return false;
        }
        return CompressionDictionary.sameHash(local.currentDictHash(), remote.currentDictHash())
            && local.currentDictVersion() == remote.currentDictVersion()
            && local.currentDictVersion() != 0;
    }

    @Test
    void helloCarriesCompressionFlagsAndRoundTrips() throws Exception {
        CompressionDictionary dictionary = trainDictionary(91);
        WireMessage.Hello hello = new WireMessage.Hello(
            WireCodec.PROTOCOL_VERSION,
            "1.26.1",
            "1.0.0",
            "alpha",
            "10.0.0.5",
            8901,
            25565,
            Handshake.newNonce(),
            generatePublicKey(),
            true,
            dictionary.hash(),
            91
        );
        byte[] frame = WireCodec.encodeFrame(hello);
        WireMessage.Hello decoded = assertInstanceOf(WireMessage.Hello.class, WireCodec.readFrame(new DataInputStream(new ByteArrayInputStream(frame))));
        assertTrue(decoded.compressionSupported());
        assertArrayEquals(dictionary.hash(), decoded.currentDictHash());
        assertEquals(91, decoded.currentDictVersion());
    }

    @Test
    void challengeCarriesCompressionFlagsAndRoundTrips() throws Exception {
        CompressionDictionary dictionary = trainDictionary(92);
        WireMessage.Challenge challenge = new WireMessage.Challenge(
            "beta",
            "10.0.0.2",
            8901,
            25565,
            Handshake.newNonce(),
            generatePublicKey(),
            new byte[]{1, 2, 3, 4},
            true,
            dictionary.hash(),
            92
        );
        byte[] frame = WireCodec.encodeFrame(challenge);
        WireMessage.Challenge decoded = assertInstanceOf(WireMessage.Challenge.class, WireCodec.readFrame(new DataInputStream(new ByteArrayInputStream(frame))));
        assertTrue(decoded.compressionSupported());
        assertArrayEquals(dictionary.hash(), decoded.currentDictHash());
        assertEquals(92, decoded.currentDictVersion());
    }

    @Test
    void matchingDictHashAndVersionEnablesDictMode() throws Exception {
        CompressionDictionary dictionary = trainDictionary(50);
        WireMessage.Hello local = new WireMessage.Hello(
            WireCodec.PROTOCOL_VERSION, "1.26.1", "1.0.0", "alpha", "10.0.0.1", 8901, 25565,
            Handshake.newNonce(), generatePublicKey(), true, dictionary.hash(), 50);
        WireMessage.Hello remote = new WireMessage.Hello(
            WireCodec.PROTOCOL_VERSION, "1.26.1", "1.0.0", "beta", "10.0.0.2", 8901, 25565,
            Handshake.newNonce(), generatePublicKey(), true, dictionary.hash(), 50);
        assertTrue(negotiateUseDict(local, remote));
    }

    @Test
    void mismatchedHashFallsBackToDictless() throws Exception {
        byte[] alphaHash = new byte[CompressionDictionary.HASH_LENGTH];
        byte[] betaHash = new byte[CompressionDictionary.HASH_LENGTH];
        for (int i = 0; i < alphaHash.length; i++) {
            alphaHash[i] = (byte) 0xAA;
            betaHash[i] = (byte) 0xBB;
        }
        WireMessage.Hello local = new WireMessage.Hello(
            WireCodec.PROTOCOL_VERSION, "1.26.1", "1.0.0", "alpha", "10.0.0.1", 8901, 25565,
            Handshake.newNonce(), generatePublicKey(), true, alphaHash, 50);
        WireMessage.Hello remote = new WireMessage.Hello(
            WireCodec.PROTOCOL_VERSION, "1.26.1", "1.0.0", "beta", "10.0.0.2", 8901, 25565,
            Handshake.newNonce(), generatePublicKey(), true, betaHash, 50);
        assertFalse(negotiateUseDict(local, remote));
    }

    @Test
    void unsupportedRemoteForcesPlainMode() throws Exception {
        CompressionDictionary dictionary = trainDictionary(60);
        WireMessage.Hello local = new WireMessage.Hello(
            WireCodec.PROTOCOL_VERSION, "1.26.1", "1.0.0", "alpha", "10.0.0.1", 8901, 25565,
            Handshake.newNonce(), generatePublicKey(), true, dictionary.hash(), 60);
        WireMessage.Hello remote = new WireMessage.Hello(
            WireCodec.PROTOCOL_VERSION, "1.26.1", "1.0.0", "beta", "10.0.0.2", 8901, 25565,
            Handshake.newNonce(), generatePublicKey(), false, CompressionDictionary.ZERO_HASH, 0);
        assertFalse(negotiateUseDict(local, remote));
    }

    @Test
    void zeroVersionDoesNotNegotiateDict() throws Exception {
        WireMessage.Hello local = new WireMessage.Hello(
            WireCodec.PROTOCOL_VERSION, "1.26.1", "1.0.0", "alpha", "10.0.0.1", 8901, 25565,
            Handshake.newNonce(), generatePublicKey(), true, CompressionDictionary.ZERO_HASH, 0);
        WireMessage.Hello remote = new WireMessage.Hello(
            WireCodec.PROTOCOL_VERSION, "1.26.1", "1.0.0", "beta", "10.0.0.2", 8901, 25565,
            Handshake.newNonce(), generatePublicKey(), true, CompressionDictionary.ZERO_HASH, 0);
        assertFalse(negotiateUseDict(local, remote));
    }

    @Test
    void invalidHashLengthIsRejectedAtEncodeTime() throws Exception {
        WireMessage.Hello hello = new WireMessage.Hello(
            WireCodec.PROTOCOL_VERSION, "1.26.1", "1.0.0", "alpha", "10.0.0.1", 8901, 25565,
            Handshake.newNonce(), generatePublicKey(), true, new byte[]{1, 2, 3}, 1);
        try {
            WireCodec.encodeFrame(hello);
        } catch (IOException expected) {
            return;
        }
        throw new AssertionError("expected IOException for malformed hash length");
    }
}
