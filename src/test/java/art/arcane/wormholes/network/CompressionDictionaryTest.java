package art.arcane.wormholes.network;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CompressionDictionaryTest {
    private static List<byte[]> structuredCorpus(long seed) {
        Random random = new Random(seed);
        List<byte[]> samples = new ArrayList<>();
        for (int i = 0; i < 256; i++) {
            byte[] sample = new byte[1024 + random.nextInt(2048)];
            for (int j = 0; j < sample.length; j++) {
                sample[j] = (byte) ('a' + random.nextInt(16));
            }
            samples.add(sample);
        }
        return samples;
    }

    private static List<byte[]> alternateCorpus(long seed) {
        Random random = new Random(seed);
        List<byte[]> samples = new ArrayList<>();
        for (int i = 0; i < 256; i++) {
            byte[] sample = new byte[2048 + random.nextInt(2048)];
            for (int j = 0; j < sample.length; j++) {
                sample[j] = (byte) ('M' + random.nextInt(16));
            }
            samples.add(sample);
        }
        return samples;
    }

    @Test
    void trainProducesNonEmptyBytesAndHash() {
        CompressionDictionary dictionary = CompressionDictionary.train(structuredCorpus(1L), 8 * 1024, 1);
        assertNotNull(dictionary.bytes());
        assertTrue(dictionary.bytes().length > 0);
        assertEquals(CompressionDictionary.HASH_LENGTH, dictionary.hash().length);
        boolean nonZero = false;
        for (byte hashByte : dictionary.hash()) {
            if (hashByte != 0) {
                nonZero = true;
                break;
            }
        }
        assertTrue(nonZero);
    }

    @Test
    void hashIsDeterministicForSameInputBytes() {
        byte[] bytes = new byte[]{1, 2, 3, 4, 5, 6, 7, 8};
        CompressionDictionary first = CompressionDictionary.of(bytes, 1);
        CompressionDictionary second = CompressionDictionary.of(bytes, 2);
        assertArrayEquals(first.hash(), second.hash());
    }

    @Test
    void withVersionDoesNotChangeHash() {
        CompressionDictionary dictionary = CompressionDictionary.train(structuredCorpus(2L), 8 * 1024, 5);
        CompressionDictionary rotated = dictionary.withVersion(9);
        assertArrayEquals(dictionary.hash(), rotated.hash());
        assertEquals(9, rotated.version());
        assertEquals(5, dictionary.version());
    }

    @Test
    void saveLoadRoundTripsBytesAndHash(@TempDir Path tempDir) throws IOException {
        CompressionDictionary original = CompressionDictionary.train(structuredCorpus(3L), 8 * 1024, 12);
        Path file = original.save(tempDir);
        CompressionDictionary loaded = CompressionDictionary.load(file, 12);
        assertArrayEquals(original.bytes(), loaded.bytes());
        assertArrayEquals(original.hash(), loaded.hash());
        assertEquals(12, loaded.version());
    }

    @Test
    void sameHashOnZeroHashesIsTrue() {
        assertTrue(CompressionDictionary.sameHash(CompressionDictionary.ZERO_HASH, CompressionDictionary.ZERO_HASH));
    }

    @Test
    void sameHashOnMismatchedLengthIsFalse() {
        assertFalse(CompressionDictionary.sameHash(new byte[]{1, 2, 3}, new byte[]{1, 2}));
    }

    @Test
    void sameHashOnNullIsFalse() {
        assertFalse(CompressionDictionary.sameHash(null, CompressionDictionary.ZERO_HASH));
        assertFalse(CompressionDictionary.sameHash(CompressionDictionary.ZERO_HASH, null));
    }

    @Test
    void differentCorporaProduceDifferentHashes() {
        CompressionDictionary first = CompressionDictionary.train(structuredCorpus(4L), 8 * 1024, 1);
        CompressionDictionary second = CompressionDictionary.train(alternateCorpus(4L), 8 * 1024, 1);
        assertNotEquals(0, first.bytes().length);
        assertNotEquals(0, second.bytes().length);
        assertFalse(CompressionDictionary.sameHash(first.hash(), second.hash()));
    }

    @Test
    void hashHex8IsEightHexBytes() {
        CompressionDictionary dictionary = CompressionDictionary.train(structuredCorpus(5L), 8 * 1024, 1);
        String hex = dictionary.hashHex8();
        assertEquals(16, hex.length());
        for (int i = 0; i < hex.length(); i++) {
            char ch = hex.charAt(i);
            assertTrue((ch >= '0' && ch <= '9') || (ch >= 'a' && ch <= 'f'));
        }
    }

    @Test
    void ofRejectsEmptyBytes() {
        assertThrows(IllegalArgumentException.class, () -> CompressionDictionary.of(new byte[0], 1));
    }

    @Test
    void ofRejectsNullBytes() {
        assertThrows(IllegalArgumentException.class, () -> CompressionDictionary.of(null, 1));
    }

    @Test
    void trainRejectsEmptySamples() {
        assertThrows(IllegalStateException.class, () -> CompressionDictionary.train(List.of(), 8 * 1024, 1));
    }

    @Test
    void trainRejectsAllEmptySamples() {
        List<byte[]> samples = new ArrayList<>();
        samples.add(new byte[0]);
        samples.add(new byte[0]);
        assertThrows(IllegalStateException.class, () -> CompressionDictionary.train(samples, 8 * 1024, 1));
    }
}
