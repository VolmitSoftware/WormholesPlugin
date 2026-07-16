package art.arcane.wormholes.network;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WireCompressionTest {
    private static byte[] randomBytes(int length, long seed) {
        Random random = new Random(seed);
        byte[] data = new byte[length];
        random.nextBytes(data);
        return data;
    }

    private static byte[] compressibleBytes(int length, long seed) {
        Random random = new Random(seed);
        byte[] data = new byte[length];
        for (int i = 0; i < length; i++) {
            data[i] = (byte) ('A' + random.nextInt(8));
        }
        return data;
    }

    private static byte[] repetitiveWords(int length, long seed) {
        String[] words = {"wormholes", "sideband", "status", "bridge", "packet", "gateway", "portal", "chunk"};
        Random random = new Random(seed);
        StringBuilder text = new StringBuilder(length + 16);
        while (text.length() < length) {
            text.append(words[random.nextInt(words.length)]).append(' ');
        }
        return text.substring(0, length).getBytes(StandardCharsets.UTF_8);
    }

    private static int readLittleEndianInt(byte[] data, int offset) {
        return (data[offset] & 0xFF)
            | ((data[offset + 1] & 0xFF) << 8)
            | ((data[offset + 2] & 0xFF) << 16)
            | ((data[offset + 3] & 0xFF) << 24);
    }

    private static CompressionDictionary trainDictionary() {
        return trainDictionary(0xABCDL, 'a');
    }

    private static CompressionDictionary trainDictionary(long seed, char base) {
        List<byte[]> samples = new ArrayList<>(256);
        Random random = new Random(seed);
        for (int i = 0; i < 256; i++) {
            byte[] sample = new byte[1024 + random.nextInt(1024)];
            for (int j = 0; j < sample.length; j++) {
                sample[j] = (byte) (base + random.nextInt(16));
            }
            samples.add(sample);
        }
        return CompressionDictionary.train(samples, 8 * 1024);
    }

    @Test
    void dictlessRoundTripPreservesCompressiblePayload() throws IOException {
        WireCompression compression = new WireCompression(WireCompression.DEFAULT_LEVEL);
        try {
            byte[] payload = compressibleBytes(4096, 1L);
            byte[] encoded = compression.encode(payload, 0);
            assertEquals(WireCompression.MODE_ZSTD_DICTLESS, encoded[0]);
            WireCompression.DecodeResult decoded = compression.decode(encoded);
            assertArrayEquals(payload, decoded.payload());
            assertEquals(WireCompression.MODE_ZSTD_DICTLESS, decoded.mode());
            assertEquals(0, decoded.dictVersion());
        } finally {
            compression.close();
        }
    }

    @Test
    void dictlessRoundTripPreservesRandomPayload() throws IOException {
        WireCompression compression = new WireCompression(WireCompression.DEFAULT_LEVEL);
        try {
            byte[] payload = randomBytes(8192, 2L);
            byte[] encoded = compression.encode(payload, 0);
            WireCompression.DecodeResult decoded = compression.decode(encoded);
            assertArrayEquals(payload, decoded.payload());
        } finally {
            compression.close();
        }
    }

    @Test
    void emptyPayloadEncodesAsNoneMode() throws IOException {
        WireCompression compression = new WireCompression(WireCompression.DEFAULT_LEVEL);
        try {
            byte[] payload = new byte[0];
            byte[] encoded = compression.encode(payload, 0);
            assertEquals(WireCompression.MODE_NONE, encoded[0]);
            WireCompression.DecodeResult decoded = compression.decode(encoded);
            assertArrayEquals(payload, decoded.payload());
        } finally {
            compression.close();
        }
    }

    @Test
    void belowThresholdPayloadIsForcedNoneMode() throws IOException {
        WireCompression compression = new WireCompression(WireCompression.DEFAULT_LEVEL);
        try {
            CompressionDictionary dictionary = trainDictionary();
            compression.installDictionary(dictionary);
            byte[] payload = compressibleBytes(WireCompression.COMPRESS_THRESHOLD_BYTES - 1, 3L);
            byte[] encoded = compression.encode(payload, dictionary.version());
            assertEquals(WireCompression.MODE_NONE, encoded[0]);
        } finally {
            compression.close();
        }
    }

    @Test
    void largePayloadRoundTripsThroughDictless() throws IOException {
        WireCompression compression = new WireCompression(WireCompression.DEFAULT_LEVEL);
        try {
            byte[] payload = compressibleBytes(1024 * 1024, 4L);
            byte[] encoded = compression.encode(payload, 0);
            WireCompression.DecodeResult decoded = compression.decode(encoded);
            assertArrayEquals(payload, decoded.payload());
        } finally {
            compression.close();
        }
    }

    @Test
    void boundedDecodeRejectsPayloadBeforeAllocatingItsDeclaredSize() throws IOException {
        WireCompression compression = new WireCompression(WireCompression.DEFAULT_LEVEL);
        try {
            byte[] payload = compressibleBytes(8192, 401L);
            byte[] encoded = compression.encode(payload, 0);

            assertEquals(WireCompression.MODE_ZSTD_DICTLESS, encoded[0]);
            assertThrows(IOException.class, () -> compression.decode(encoded, 4096));
            assertArrayEquals(payload, compression.decode(encoded, payload.length).payload());
        } finally {
            compression.close();
        }
    }

    @Test
    void boundedDecodeAlsoRejectsOversizedPlainFrames() {
        WireCompression compression = new WireCompression(WireCompression.DEFAULT_LEVEL);
        try {
            byte[] plain = new byte[257];
            plain[0] = WireCompression.MODE_NONE;

            assertThrows(IOException.class, () -> compression.decode(plain, 255));
        } finally {
            compression.close();
        }
    }

    @Test
    void closedCompressionRejectsNewCodecWork() {
        WireCompression compression = new WireCompression(WireCompression.DEFAULT_LEVEL);
        compression.close();

        assertThrows(IOException.class, () -> compression.encode(new byte[32], 0));
        assertThrows(IOException.class, () -> compression.encodeFramedFrame((byte) 1, new byte[32], 0));
        assertThrows(IOException.class, () -> compression.decode(new byte[] { WireCompression.MODE_NONE }));
        assertThrows(IllegalStateException.class, () -> compression.setCompressionLevel(5));
        compression.close();
    }

    @Test
    void dictModeEncodesWithVersionPrefix() throws IOException {
        WireCompression compression = new WireCompression(WireCompression.DEFAULT_LEVEL);
        try {
            CompressionDictionary dictionary = trainDictionary();
            compression.installDictionary(dictionary);
            byte[] payload = compressibleBytes(4096, 5L);
            byte[] encoded = compression.encode(payload, dictionary.version());
            assertEquals(WireCompression.MODE_ZSTD_DICT, encoded[0]);
            int version = readLittleEndianInt(encoded, 1);
            assertEquals(dictionary.version(), version);
            WireCompression.DecodeResult decoded = compression.decode(encoded);
            assertArrayEquals(payload, decoded.payload());
            assertEquals(dictionary.version(), decoded.dictVersion());
        } finally {
            compression.close();
        }
    }

    @Test
    void dictNotNegotiatedFallsBackToDictless() throws IOException {
        WireCompression compression = new WireCompression(WireCompression.DEFAULT_LEVEL);
        try {
            CompressionDictionary dictionary = trainDictionary();
            compression.installDictionary(dictionary);
            byte[] payload = compressibleBytes(4096, 6L);
            byte[] encoded = compression.encode(payload, 0);
            assertEquals(WireCompression.MODE_ZSTD_DICTLESS, encoded[0]);
        } finally {
            compression.close();
        }
    }

    @Test
    void dictlessFrameAfterDictionaryFrameDoesNotReuseThePooledDictionary() throws IOException {
        WireCompression sender = new WireCompression(WireCompression.DEFAULT_LEVEL);
        WireCompression receiver = new WireCompression(WireCompression.DEFAULT_LEVEL);
        try {
            CompressionDictionary dictionary = trainDictionary();
            sender.installDictionary(dictionary);
            receiver.installDictionary(dictionary);
            byte[] dictionaryPayload = compressibleBytes(4096, 61L);
            byte[] dictionaryFrame = sender.encode(dictionaryPayload, dictionary.version());
            assertEquals(WireCompression.MODE_ZSTD_DICT, dictionaryFrame[0]);
            assertArrayEquals(dictionaryPayload, receiver.decode(dictionaryFrame).payload());

            byte[] dictlessPayload = compressibleBytes(4096, 62L);
            byte[] dictlessFrame = sender.encode(dictlessPayload, 0);

            assertEquals(WireCompression.MODE_ZSTD_DICTLESS, dictlessFrame[0]);
            assertArrayEquals(dictlessPayload, receiver.decode(dictlessFrame).payload());
        } finally {
            sender.close();
            receiver.close();
        }
    }

    @Test
    void clearDictionaryRevertsToDictless() throws IOException {
        WireCompression compression = new WireCompression(WireCompression.DEFAULT_LEVEL);
        try {
            CompressionDictionary dictionary = trainDictionary();
            compression.installDictionary(dictionary);
            assertTrue(compression.hasDictionary());
            compression.clearDictionary();
            assertFalse(compression.hasDictionary());
            byte[] payload = compressibleBytes(4096, 7L);
            byte[] encoded = compression.encode(payload, dictionary.version());
            assertEquals(WireCompression.MODE_ZSTD_DICTLESS, encoded[0]);
        } finally {
            compression.close();
        }
    }

    @Test
    void unknownModeIsRejected() {
        WireCompression compression = new WireCompression(WireCompression.DEFAULT_LEVEL);
        try {
            byte[] bogus = new byte[]{(byte) 99, 0, 0, 0, 0};
            assertThrows(IOException.class, () -> compression.decode(bogus));
        } finally {
            compression.close();
        }
    }

    @Test
    void missingModeByteIsRejected() {
        WireCompression compression = new WireCompression(WireCompression.DEFAULT_LEVEL);
        try {
            assertThrows(IOException.class, () -> compression.decode(new byte[0]));
        } finally {
            compression.close();
        }
    }

    @Test
    void truncatedDictVersionIsRejected() {
        WireCompression compression = new WireCompression(WireCompression.DEFAULT_LEVEL);
        try {
            byte[] truncated = new byte[]{WireCompression.MODE_ZSTD_DICT, 1, 2};
            assertThrows(IOException.class, () -> compression.decode(truncated));
        } finally {
            compression.close();
        }
    }

    @Test
    void dictDecodeWithoutInstalledDictionaryIsRejected() throws IOException {
        WireCompression encoderCompression = new WireCompression(WireCompression.DEFAULT_LEVEL);
        WireCompression decoderCompression = new WireCompression(WireCompression.DEFAULT_LEVEL);
        try {
            CompressionDictionary dictionary = trainDictionary();
            encoderCompression.installDictionary(dictionary);
            byte[] payload = compressibleBytes(4096, 8L);
            byte[] encoded = encoderCompression.encode(payload, dictionary.version());
            assertEquals(WireCompression.MODE_ZSTD_DICT, encoded[0]);
            assertThrows(IOException.class, () -> decoderCompression.decode(encoded));
        } finally {
            encoderCompression.close();
            decoderCompression.close();
        }
    }

    @Test
    void compressionLevelIsClampedToValidRange() {
        WireCompression below = new WireCompression(-5);
        try {
            assertEquals(WireCompression.MIN_LEVEL, below.compressionLevel());
        } finally {
            below.close();
        }
        WireCompression above = new WireCompression(999);
        try {
            assertEquals(WireCompression.MAX_LEVEL, above.compressionLevel());
        } finally {
            above.close();
        }
    }

    @Test
    void compressionLevelCanChangeWithAnInstalledDictionary() throws IOException {
        WireCompression compression = new WireCompression(3);
        try {
            CompressionDictionary dictionary = trainDictionary();
            compression.installDictionary(dictionary);
            compression.setCompressionLevel(9);
            assertEquals(9, compression.compressionLevel());
            byte[] payload = compressibleBytes(4096, 91L);
            byte[] encoded = compression.encode(payload, dictionary.version());
            assertEquals(WireCompression.MODE_ZSTD_DICT, encoded[0]);
            assertArrayEquals(payload, compression.decode(encoded).payload());
        } finally {
            compression.close();
        }
    }

    @Test
    void incompressiblePayloadFallsBackToNoneMode() throws IOException {
        WireCompression compression = new WireCompression(WireCompression.DEFAULT_LEVEL);
        try {
            byte[] payload = randomBytes(2048, 9L);
            byte[] encoded = compression.encode(payload, 0);
            assertNotNull(encoded);
            WireCompression.DecodeResult decoded = compression.decode(encoded);
            assertArrayEquals(payload, decoded.payload());
        } finally {
            compression.close();
        }
    }

    @Test
    void switchingDictionaryUsesNewVersion() throws IOException {
        WireCompression compression = new WireCompression(WireCompression.DEFAULT_LEVEL);
        try {
            CompressionDictionary first = trainDictionary(100L, 'a');
            CompressionDictionary second = trainDictionary(101L, 'A');
            assertNotEquals(first.version(), second.version());
            compression.installDictionary(first);
            byte[] payload = compressibleBytes(4096, 10L);
            byte[] firstFrame = compression.encode(payload, first.version());
            assertEquals(first.version(), readLittleEndianInt(firstFrame, 1));
            compression.installDictionary(second);
            byte[] secondFrame = compression.encode(payload, second.version());
            assertEquals(second.version(), readLittleEndianInt(secondFrame, 1));
            assertFalse(Arrays.equals(firstFrame, secondFrame));
            assertArrayEquals(payload, compression.decode(firstFrame).payload());
            assertArrayEquals(payload, compression.decode(secondFrame).payload());
        } finally {
            compression.close();
        }
    }

    @Test
    void decodeRetainsPreviousDictionaryVersionAfterInstall() throws IOException {
        WireCompression compression = new WireCompression(WireCompression.DEFAULT_LEVEL);
        try {
            CompressionDictionary first = trainDictionary(11L, 'a');
            CompressionDictionary second = trainDictionary(12L, 'A');
            compression.installDictionary(first);
            byte[] payload = compressibleBytes(4096, 11L);
            byte[] firstFrame = compression.encode(payload, first.version());
            assertEquals(WireCompression.MODE_ZSTD_DICT, firstFrame[0]);
            compression.installDictionary(second);
            assertArrayEquals(payload, compression.decode(firstFrame).payload());
            byte[] secondFrame = compression.encode(payload, second.version());
            assertEquals(WireCompression.MODE_ZSTD_DICT, secondFrame[0]);
            assertArrayEquals(payload, compression.decode(secondFrame).payload());
        } finally {
            compression.close();
        }
    }

    @Test
    void retiredDictionariesEvictBeyondLimit() throws IOException {
        WireCompression compression = new WireCompression(WireCompression.DEFAULT_LEVEL);
        try {
            CompressionDictionary d1 = trainDictionary(21L, 'a');
            CompressionDictionary d2 = trainDictionary(22L, 'A');
            CompressionDictionary d3 = trainDictionary(23L, '0');
            CompressionDictionary d4 = trainDictionary(24L, 'P');
            byte[] payload = compressibleBytes(4096, 21L);
            compression.installDictionary(d1);
            byte[] frame1 = compression.encode(payload, d1.version());
            compression.installDictionary(d2);
            compression.installDictionary(d3);
            byte[] frame3 = compression.encode(payload, d3.version());
            compression.installDictionary(d4);
            byte[] frame4 = compression.encode(payload, d4.version());
            assertArrayEquals(payload, compression.decode(frame3).payload());
            assertArrayEquals(payload, compression.decode(frame4).payload());
            assertThrows(IOException.class, () -> compression.decode(frame1));
        } finally {
            compression.close();
        }
    }

    @Test
    void reinstallingSameDictionaryDoesNotStackRetiredEntries() throws IOException {
        WireCompression compression = new WireCompression(WireCompression.DEFAULT_LEVEL);
        try {
            CompressionDictionary d1 = trainDictionary(31L, 'a');
            CompressionDictionary d2 = trainDictionary(32L, 'A');
            byte[] payload = compressibleBytes(4096, 31L);
            compression.installDictionary(d1);
            byte[] frame1 = compression.encode(payload, d1.version());
            compression.installDictionary(d2);
            byte[] frame2 = compression.encode(payload, d2.version());
            compression.installDictionary(d1);
            assertArrayEquals(payload, compression.decode(frame1).payload());
            assertArrayEquals(payload, compression.decode(frame2).payload());
        } finally {
            compression.close();
        }
    }

    @Test
    void encodeFallsBackToDictlessWhenNegotiatedVersionMismatchesCurrent() throws IOException {
        WireCompression compression = new WireCompression(WireCompression.DEFAULT_LEVEL);
        try {
            CompressionDictionary d1 = trainDictionary(41L, 'a');
            CompressionDictionary d2 = trainDictionary(42L, 'A');
            compression.installDictionary(d2);
            byte[] payload = compressibleBytes(4096, 41L);
            byte[] encoded = compression.encode(payload, d1.version());
            assertEquals(WireCompression.MODE_ZSTD_DICTLESS, encoded[0]);
            assertArrayEquals(payload, compression.decode(encoded).payload());
        } finally {
            compression.close();
        }
    }

    @Test
    void encodeWithLevelOverrideRoundTripsAndDecodesLevelAgnostic() throws IOException {
        WireCompression encoder = new WireCompression(WireCompression.DEFAULT_LEVEL);
        WireCompression decoder = new WireCompression(WireCompression.DEFAULT_LEVEL);
        try {
            byte[] payload = compressibleBytes(64 * 1024, 12L);
            byte[] encodedHigh = encoder.encode(payload, false, 12);
            assertEquals(WireCompression.MODE_ZSTD_DICTLESS, encodedHigh[0]);
            assertArrayEquals(payload, decoder.decode(encodedHigh).payload());

            byte[] repetitive = repetitiveWords(256 * 1024, 13L);
            byte[] levelTwelve = encoder.encode(repetitive, false, 12);
            byte[] levelOne = encoder.encode(repetitive, false, 1);
            assertTrue(levelTwelve.length <= levelOne.length, "level 12 must not encode larger than level 1, got " + levelTwelve.length + " vs " + levelOne.length);
            assertArrayEquals(repetitive, decoder.decode(levelTwelve).payload());
            assertArrayEquals(repetitive, decoder.decode(levelOne).payload());
        } finally {
            encoder.close();
            decoder.close();
        }
    }

    @Test
    void concurrentDecodeSurvivesDictionaryRotation() throws Exception {
        WireCompression compression = new WireCompression(WireCompression.DEFAULT_LEVEL);
        try {
            CompressionDictionary d1 = trainDictionary(51L, 'a');
            CompressionDictionary d2 = trainDictionary(52L, 'A');
            CompressionDictionary d3 = trainDictionary(53L, '0');
            byte[] payload = compressibleBytes(4096, 51L);
            compression.installDictionary(d1);
            byte[] frame1 = compression.encode(payload, d1.version());
            compression.installDictionary(d2);
            byte[] frame2 = compression.encode(payload, d2.version());
            byte[][] frames = {frame1, frame2};

            int threads = 4;
            CountDownLatch start = new CountDownLatch(1);
            CountDownLatch done = new CountDownLatch(threads);
            AtomicReference<Throwable> unexpected = new AtomicReference<>();
            for (int t = 0; t < threads; t++) {
                Thread worker = new Thread(() -> {
                    try {
                        start.await();
                        for (int i = 0; i < 400; i++) {
                            byte[] frame = frames[i & 1];
                            try {
                                assertArrayEquals(payload, compression.decode(frame).payload());
                            } catch (IOException e) {
                                String text = e.getMessage() == null ? "" : e.getMessage();
                                if (!text.contains("missing dictionary version")) {
                                    throw e;
                                }
                            }
                        }
                    } catch (Throwable ex) {
                        unexpected.compareAndSet(null, ex);
                    } finally {
                        done.countDown();
                    }
                });
                worker.setDaemon(true);
                worker.start();
            }
            start.countDown();
            for (int i = 0; i < 100; i++) {
                compression.installDictionary((i & 1) == 0 ? d3 : d2);
            }
            done.await();
            assertNull(unexpected.get(), () -> "unexpected decode failure: " + unexpected.get());
        } finally {
            compression.close();
        }
    }
}
