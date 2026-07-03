package art.arcane.wormholes.network;

import art.arcane.wormholes.network.replication.ChunkBulk;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WireCodecFrameLayoutTest {
    private static WireMessage.Hello compressibleHello(int fillSize) {
        char[] chars = new char[fillSize];
        for (int i = 0; i < fillSize; i++) {
            chars[i] = (char) ('a' + (i % 8));
        }
        return new WireMessage.Hello(WireCodec.PROTOCOL_VERSION, "26.2", new String(chars), "alpha", "10.0.0.1",
            8901, 25565, new byte[Handshake.NONCE_LENGTH], new byte[64], true, CompressionDictionary.ZERO_HASH, 0);
    }

    private static WireMessage.EntityTransfer incompressibleTransfer(int snapshotSize, long seed) {
        byte[] snapshot = new byte[snapshotSize];
        new Random(seed).nextBytes(snapshot);
        return new WireMessage.EntityTransfer(new java.util.UUID(1L, 2L), new java.util.UUID(3L, 4L), snapshot,
            new WireTraversive("N", "E", "U",
                10.5D, 64.0D, 20.5D,
                10.5D, 64.0D, 20.5D,
                0.0D, 0.0D, 1.0D,
                0.0D, 0.0D, 1.0D,
                true));
    }

    private static CompressionDictionary trainDictionary() {
        List<byte[]> samples = new ArrayList<>(256);
        Random random = new Random(0xBEEFL);
        for (int i = 0; i < 256; i++) {
            byte[] sample = new byte[1024 + random.nextInt(1024)];
            for (int j = 0; j < sample.length; j++) {
                sample[j] = (byte) ('a' + random.nextInt(8));
            }
            samples.add(sample);
        }
        return CompressionDictionary.train(samples, 8 * 1024);
    }

    private static int readBigEndianInt(byte[] data, int offset) {
        return ((data[offset] & 0xFF) << 24)
            | ((data[offset + 1] & 0xFF) << 16)
            | ((data[offset + 2] & 0xFF) << 8)
            | (data[offset + 3] & 0xFF);
    }

    private static int readLittleEndianInt(byte[] data, int offset) {
        return (data[offset] & 0xFF)
            | ((data[offset + 1] & 0xFF) << 8)
            | ((data[offset + 2] & 0xFF) << 16)
            | ((data[offset + 3] & 0xFF) << 24);
    }

    private static void assertLayoutAndRoundTrip(WireMessage message, WireCompression compression, int negotiatedDictVersion, byte expectedMode) throws IOException {
        byte[] frame = WireCodec.encodeFrame(message, compression, negotiatedDictVersion);
        int frameLength = readBigEndianInt(frame, 0);
        assertEquals(frame.length - 4, frameLength, "BE length prefix must cover type byte plus body");
        assertEquals(message.type().id(), frame[4]);
        assertEquals(expectedMode, frame[5]);
        if (expectedMode == WireCompression.MODE_ZSTD_DICT) {
            assertEquals(negotiatedDictVersion, readLittleEndianInt(frame, 6));
        }
        WireMessage decoded = WireCodec.readFrame(new DataInputStream(new ByteArrayInputStream(frame)), compression);
        assertEquals(message.type(), decoded.type());
    }

    @Test
    void encodeFramePinsWireLayout() throws IOException {
        WireCompression dictless = new WireCompression(WireCompression.DEFAULT_LEVEL);
        WireCompression dictMode = new WireCompression(WireCompression.DEFAULT_LEVEL);
        try {
            CompressionDictionary dictionary = trainDictionary();
            dictMode.installDictionary(dictionary);

            assertLayoutAndRoundTrip(new WireMessage.Ping(42L), dictless, 0, WireCompression.MODE_NONE);
            assertLayoutAndRoundTrip(new WireMessage.Ping(42L), dictMode, dictionary.version(), WireCompression.MODE_NONE);

            assertLayoutAndRoundTrip(compressibleHello(16 * 1024), dictless, 0, WireCompression.MODE_ZSTD_DICTLESS);
            assertLayoutAndRoundTrip(compressibleHello(16 * 1024), dictMode, dictionary.version(), WireCompression.MODE_ZSTD_DICT);

            assertLayoutAndRoundTrip(incompressibleTransfer(8 * 1024, 1L), dictless, 0, WireCompression.MODE_NONE);
            assertLayoutAndRoundTrip(incompressibleTransfer(8 * 1024, 2L), dictMode, dictionary.version(), WireCompression.MODE_NONE);
        } finally {
            dictless.close();
            dictMode.close();
        }
    }

    @Test
    void plainEncodeFrameMatchesLegacyLayoutByteForByte() throws IOException {
        WireMessage.Hello hello = compressibleHello(4096);
        byte[] frame = WireCodec.encodeFrame(hello);
        byte[] payload = WireCodec.encodePayload(hello);
        assertEquals(4 + 1 + 1 + payload.length, frame.length);
        assertEquals(1 + 1 + payload.length, readBigEndianInt(frame, 0));
        assertEquals(hello.type().id(), frame[4]);
        assertEquals(WireCompression.MODE_NONE, frame[5]);
        for (int i = 0; i < payload.length; i++) {
            assertEquals(payload[i], frame[6 + i], "payload byte " + i);
        }
        WireMessage.Hello decoded = assertInstanceOf(WireMessage.Hello.class,
            WireCodec.readFrame(new DataInputStream(new ByteArrayInputStream(frame))));
        assertEquals(hello.pluginVersion(), decoded.pluginVersion());
    }

    @Test
    void oversizedCompressedFrameThrowsFrameTooLarge() {
        WireCompression compression = new WireCompression(WireCompression.DEFAULT_LEVEL);
        try {
            Random random = new Random(7L);
            List<ChunkBulk> chunks = new ArrayList<>(3);
            for (int i = 0; i < 3; i++) {
                byte[] payload = new byte[2 * 1024 * 1024];
                random.nextBytes(payload);
                chunks.add(new ChunkBulk(i, i + 1L, payload));
            }
            WireMessage.ChunkBulkBatch batch = new WireMessage.ChunkBulkBatch(chunks);
            IOException failure = assertThrows(IOException.class, () -> WireCodec.encodeFrame(batch, compression, 0));
            assertTrue(failure.getMessage().contains("Frame too large"), "unexpected message: " + failure.getMessage());
        } finally {
            compression.close();
        }
    }
}
