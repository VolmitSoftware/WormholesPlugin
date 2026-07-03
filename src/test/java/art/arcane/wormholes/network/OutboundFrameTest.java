package art.arcane.wormholes.network;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;

class OutboundFrameTest {
    private static WireMessage.Hello compressibleHello(int fillSize) {
        char[] chars = new char[fillSize];
        for (int i = 0; i < fillSize; i++) {
            chars[i] = (char) ('a' + (i % 8));
        }
        byte[] nonce = new byte[Handshake.NONCE_LENGTH];
        byte[] publicKey = new byte[64];
        return new WireMessage.Hello(WireCodec.PROTOCOL_VERSION, "26.2", new String(chars), "alpha", "10.0.0.1",
            8901, 25565, nonce, publicKey, true, CompressionDictionary.ZERO_HASH, 0);
    }

    private static CompressionDictionary trainDictionary() {
        List<byte[]> samples = new ArrayList<>(256);
        Random random = new Random(0xF00DL);
        for (int i = 0; i < 256; i++) {
            byte[] sample = new byte[1024 + random.nextInt(1024)];
            for (int j = 0; j < sample.length; j++) {
                sample[j] = (byte) ('a' + random.nextInt(8));
            }
            samples.add(sample);
        }
        return CompressionDictionary.train(samples, 8 * 1024);
    }

    private static WireMessage readBack(byte[] frame, WireCompression compression) throws IOException {
        return WireCodec.readFrame(new DataInputStream(new ByteArrayInputStream(frame)), compression);
    }

    @Test
    void payloadIsSerializedOnce() throws IOException {
        OutboundFrame frame = new OutboundFrame(new WireMessage.Ping(42L));
        byte[] first = frame.payload();
        byte[] second = frame.payload();
        assertSame(first, second);
    }

    @Test
    void encodedFrameMemoizesPerDictVariant() throws Exception {
        WireCompression compression = new WireCompression(WireCompression.DEFAULT_LEVEL);
        try {
            CompressionDictionary dictionary = trainDictionary();
            compression.installDictionary(dictionary);
            WireMessage.Hello hello = compressibleHello(8192);
            OutboundFrame frame = new OutboundFrame(hello);

            ExecutorService pool = Executors.newFixedThreadPool(2);
            try {
                CountDownLatch start = new CountDownLatch(1);
                List<Future<byte[]>> dictless = new ArrayList<>(2);
                for (int i = 0; i < 2; i++) {
                    dictless.add(pool.submit(() -> {
                        start.await();
                        return frame.encodedFrame(compression, 0, null);
                    }));
                }
                start.countDown();
                assertSame(dictless.get(0).get(), dictless.get(1).get());

                CountDownLatch dictStart = new CountDownLatch(1);
                List<Future<byte[]>> dictMode = new ArrayList<>(2);
                for (int i = 0; i < 2; i++) {
                    dictMode.add(pool.submit(() -> {
                        dictStart.await();
                        return frame.encodedFrame(compression, dictionary.version(), null);
                    }));
                }
                dictStart.countDown();
                assertSame(dictMode.get(0).get(), dictMode.get(1).get());

                byte[] dictlessFrame = dictless.get(0).get();
                byte[] dictFrame = dictMode.get(0).get();
                assertEquals(WireCompression.MODE_ZSTD_DICTLESS, dictlessFrame[5]);
                assertEquals(WireCompression.MODE_ZSTD_DICT, dictFrame[5]);
                assertFalse(dictlessFrame == dictFrame);

                WireMessage.Hello dictlessDecoded = assertInstanceOf(WireMessage.Hello.class, readBack(dictlessFrame, compression));
                WireMessage.Hello dictDecoded = assertInstanceOf(WireMessage.Hello.class, readBack(dictFrame, compression));
                assertEquals(hello.pluginVersion(), dictlessDecoded.pluginVersion());
                assertEquals(hello.pluginVersion(), dictDecoded.pluginVersion());
            } finally {
                pool.shutdownNow();
            }
        } finally {
            compression.close();
        }
    }

    @Test
    void plainFrameRoundTripsThroughReadFrameWithoutCompression() throws IOException {
        WireMessage.Hello hello = compressibleHello(2048);
        OutboundFrame frame = new OutboundFrame(hello);
        byte[] plain = frame.plainFrame();
        assertSame(plain, frame.plainFrame());
        assertEquals(WireCompression.MODE_NONE, plain[5]);
        WireMessage.Hello decoded = assertInstanceOf(WireMessage.Hello.class, readBack(plain, null));
        assertEquals(hello.pluginVersion(), decoded.pluginVersion());
        assertArrayEquals(WireCodec.encodeFrame(hello), plain);
    }

    @Test
    void samplerInvokedExactlyOnceAcrossVariants() throws IOException {
        WireCompression compression = new WireCompression(WireCompression.DEFAULT_LEVEL);
        try {
            CompressionDictionary dictionary = trainDictionary();
            compression.installDictionary(dictionary);
            WireMessage.Hello hello = compressibleHello(4096);
            OutboundFrame frame = new OutboundFrame(hello);
            AtomicInteger calls = new AtomicInteger();
            byte[][] sampledPayload = new byte[1][];
            WireCodec.PayloadSampler sampler = (type, payload) -> {
                calls.incrementAndGet();
                assertEquals(WireMessageType.HELLO, type);
                sampledPayload[0] = payload;
            };
            frame.encodedFrame(compression, 0, sampler);
            frame.encodedFrame(compression, dictionary.version(), sampler);
            frame.encodedFrame(compression, 0, sampler);
            assertEquals(1, calls.get());
            assertArrayEquals(WireCodec.encodePayload(hello), sampledPayload[0]);
        } finally {
            compression.close();
        }
    }
}
