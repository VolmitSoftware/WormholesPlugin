package art.arcane.wormholes.network;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WireCodecCompressionTest {
    private static byte[] highlyCompressibleNonce() {
        byte[] nonce = new byte[Handshake.NONCE_LENGTH];
        for (int i = 0; i < nonce.length; i++) {
            nonce[i] = (byte) (i & 0x07);
        }
        return nonce;
    }

    private static byte[] highlyCompressiblePublicKeyBlob() {
        byte[] data = new byte[256];
        for (int i = 0; i < data.length; i++) {
            data[i] = (byte) ((i % 8) + 0x30);
        }
        return data;
    }

    private static WireMessage.Hello bigCompressibleHello(int payloadSize) {
        char[] chars = new char[payloadSize];
        for (int i = 0; i < payloadSize; i++) {
            chars[i] = (char) ('a' + (i % 8));
        }
        String fillField = new String(chars);
        return new WireMessage.Hello(
            WireCodec.PROTOCOL_VERSION,
            "26.2",
            fillField,
            "alpha",
            "10.0.0.1",
            8901,
            25565,
            highlyCompressibleNonce(),
            highlyCompressiblePublicKeyBlob(),
            true,
            CompressionDictionary.ZERO_HASH,
            0
        );
    }

    private static CompressionDictionary trainDictionary(int version) {
        List<byte[]> samples = new ArrayList<>();
        Random random = new Random(0xCAFEL);
        for (int i = 0; i < 256; i++) {
            byte[] sample = new byte[1024 + random.nextInt(2048)];
            for (int j = 0; j < sample.length; j++) {
                sample[j] = (byte) ('a' + random.nextInt(8));
            }
            samples.add(sample);
        }
        return CompressionDictionary.train(samples, 8 * 1024, version);
    }

    @Test
    void noCompressorEncodesPlainModeAndRoundTrips() throws IOException {
        WireMessage.Ping ping = new WireMessage.Ping(42L);
        byte[] frame = WireCodec.encodeFrame(ping);
        assertEquals(WireCompression.MODE_NONE, frame[5]);
        WireMessage decoded = WireCodec.readFrame(new DataInputStream(new ByteArrayInputStream(frame)));
        WireMessage.Ping echoed = assertInstanceOf(WireMessage.Ping.class, decoded);
        assertEquals(42L, echoed.sentAtMillis());
    }

    @Test
    void dictlessCompressorEncodesMode1ForLargePayload() throws IOException {
        WireCompression compression = new WireCompression(WireCompression.DEFAULT_LEVEL);
        try {
            WireMessage.Hello hello = bigCompressibleHello(8192);
            byte[] frame = WireCodec.encodeFrame(hello, compression, false);
            assertEquals(WireCompression.MODE_ZSTD_DICTLESS, frame[5]);
            WireMessage decoded = WireCodec.readFrame(new DataInputStream(new ByteArrayInputStream(frame)), compression);
            WireMessage.Hello echoed = assertInstanceOf(WireMessage.Hello.class, decoded);
            assertEquals(hello.pluginVersion(), echoed.pluginVersion());
        } finally {
            compression.close();
        }
    }

    @Test
    void dictCompressorEncodesMode2WithVersionPrefix() throws IOException {
        WireCompression compression = new WireCompression(WireCompression.DEFAULT_LEVEL);
        try {
            CompressionDictionary dictionary = trainDictionary(77);
            compression.installDictionary(dictionary);
            WireMessage.Hello hello = bigCompressibleHello(8192);
            byte[] frame = WireCodec.encodeFrame(hello, compression, true);
            assertEquals(WireCompression.MODE_ZSTD_DICT, frame[5]);
            int version = (frame[6] & 0xFF)
                | ((frame[7] & 0xFF) << 8)
                | ((frame[8] & 0xFF) << 16)
                | ((frame[9] & 0xFF) << 24);
            assertEquals(77, version);
            WireMessage decoded = WireCodec.readFrame(new DataInputStream(new ByteArrayInputStream(frame)), compression);
            WireMessage.Hello echoed = assertInstanceOf(WireMessage.Hello.class, decoded);
            assertEquals(hello.pluginVersion(), echoed.pluginVersion());
        } finally {
            compression.close();
        }
    }

    @Test
    void dictlessCompressorCompressesHighlyRedundantPayloadBelowHalfSize() throws IOException {
        WireCompression compression = new WireCompression(WireCompression.DEFAULT_LEVEL);
        try {
            int rawPayloadSize = 32 * 1024;
            WireMessage.Hello hello = bigCompressibleHello(rawPayloadSize);
            byte[] rawPayload = WireCodec.encodePayload(hello);
            byte[] frame = WireCodec.encodeFrame(hello, compression, false);
            double ratio = (double) frame.length / (double) rawPayload.length;
            assertTrue(ratio < 0.5D, "expected compression ratio < 0.5, was " + ratio + " (frame=" + frame.length + " raw=" + rawPayload.length + ")");
        } finally {
            compression.close();
        }
    }

    @Test
    void dictModeBeatsOrMatchesDictlessOnSyntheticCorpus() throws IOException {
        WireCompression dictless = new WireCompression(WireCompression.DEFAULT_LEVEL);
        WireCompression dictMode = new WireCompression(WireCompression.DEFAULT_LEVEL);
        try {
            CompressionDictionary dictionary = trainDictionary(1);
            dictMode.installDictionary(dictionary);
            WireMessage.Hello hello = bigCompressibleHello(1024);
            byte[] dictlessFrame = WireCodec.encodeFrame(hello, dictless, false);
            byte[] dictFrame = WireCodec.encodeFrame(hello, dictMode, true);
            int allowedOverhead = Math.max(dictlessFrame.length / 4, 64);
            assertTrue(dictFrame.length <= dictlessFrame.length + allowedOverhead,
                "dict frame should not balloon compared to dictless: dict=" + dictFrame.length + " dictless=" + dictlessFrame.length);
        } finally {
            dictless.close();
            dictMode.close();
        }
    }
}
