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
        CompressionDictionary dictionary = CompressionDictionary.train(structuredCorpus(1L), 8 * 1024);
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
        CompressionDictionary first = CompressionDictionary.of(bytes);
        CompressionDictionary second = CompressionDictionary.of(bytes);
        assertArrayEquals(first.hash(), second.hash());
        assertEquals(first.version(), second.version());
    }

    @Test
    void versionIsDerivedFromHashDeterministically() {
        CompressionDictionary first = CompressionDictionary.train(structuredCorpus(2L), 8 * 1024);
        CompressionDictionary again = CompressionDictionary.of(first.bytes());
        assertTrue(first.version() > 0);
        assertEquals(first.version(), again.version());
        CompressionDictionary other = CompressionDictionary.train(alternateCorpus(2L), 8 * 1024);
        assertTrue(other.version() > 0);
        assertNotEquals(first.version(), other.version());
    }

    @Test
    void saveLoadRoundTripsBytesAndHash(@TempDir Path tempDir) throws IOException {
        CompressionDictionary original = CompressionDictionary.train(structuredCorpus(3L), 8 * 1024);
        Path file = original.save(tempDir);
        CompressionDictionary loaded = CompressionDictionary.load(file);
        assertArrayEquals(original.bytes(), loaded.bytes());
        assertArrayEquals(original.hash(), loaded.hash());
        assertEquals(original.version(), loaded.version());
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
        CompressionDictionary first = CompressionDictionary.train(structuredCorpus(4L), 8 * 1024);
        CompressionDictionary second = CompressionDictionary.train(alternateCorpus(4L), 8 * 1024);
        assertNotEquals(0, first.bytes().length);
        assertNotEquals(0, second.bytes().length);
        assertFalse(CompressionDictionary.sameHash(first.hash(), second.hash()));
    }

    @Test
    void hashHex8IsEightHexBytes() {
        CompressionDictionary dictionary = CompressionDictionary.train(structuredCorpus(5L), 8 * 1024);
        String hex = dictionary.hashHex8();
        assertEquals(16, hex.length());
        for (int i = 0; i < hex.length(); i++) {
            char ch = hex.charAt(i);
            assertTrue((ch >= '0' && ch <= '9') || (ch >= 'a' && ch <= 'f'));
        }
    }

    @Test
    void ofRejectsEmptyBytes() {
        assertThrows(IllegalArgumentException.class, () -> CompressionDictionary.of(new byte[0]));
    }

    @Test
    void ofRejectsNullBytes() {
        assertThrows(IllegalArgumentException.class, () -> CompressionDictionary.of(null));
    }

    @Test
    void trainRejectsEmptySamples() {
        assertThrows(IllegalStateException.class, () -> CompressionDictionary.train(List.of(), 8 * 1024));
    }

    @Test
    void trainRejectsAllEmptySamples() {
        List<byte[]> samples = new ArrayList<>();
        samples.add(new byte[0]);
        samples.add(new byte[0]);
        assertThrows(IllegalStateException.class, () -> CompressionDictionary.train(samples, 8 * 1024));
    }

    private static List<byte[]> tokenCorpus(long seed, String[] tokens, int samples) {
        Random random = new Random(seed);
        List<byte[]> corpus = new ArrayList<>(samples);
        for (int i = 0; i < samples; i++) {
            StringBuilder builder = new StringBuilder(1200);
            while (builder.length() < 1024) {
                builder.append(tokens[random.nextInt(tokens.length)]).append(random.nextInt(1000));
            }
            byte[] bytes = builder.toString().getBytes(java.nio.charset.StandardCharsets.US_ASCII);
            byte[] sample = new byte[1024];
            System.arraycopy(bytes, 0, sample, 0, 1024);
            corpus.add(sample);
        }
        return corpus;
    }

    private static final String[] TOKENS_A = {
        "portal:overworld:gateway;state=open;frame=N,E,U;",
        "origin=10.5,64.0,20.5;bounds=9.5,63.5,19.5:11.5,66.5,21.5;",
        "chunk:12:-7:palette=minecraft:stone,minecraft:dirt,minecraft:grass_block;",
        "entity:minecraft:zombie:pos=1.0,2.0,3.0:health=20.0;",
        "diff:seq=42:blocks=minecraft:oak_fence[waterlogged=false];"
    };

    private static final String[] TOKENS_B = {
        "biome#minecraft:deep_dark#depth=-32#scale=0.75#",
        "vel=-0.25,0.0,0.75|yaw=180.0|pitch=-12.5|onGround=true|",
        "nbt{Items:[{id:diamond_sword,Count:1b,tag:{Enchantments:[]}}]}",
        "light:block=15:sky=0:section=8:mask=0xFFEE;",
        "REDSTONE|LAMP|COMPARATOR|OBSERVER|PISTON|"
    };

    @Test
    void compressedSizeSumIsSmallerWithMatchedDictionary() {
        CompressionDictionary matched = CompressionDictionary.train(tokenCorpus(6L, TOKENS_A, 256), 8 * 1024);
        CompressionDictionary mismatched = CompressionDictionary.train(tokenCorpus(6L, TOKENS_B, 256), 8 * 1024);
        List<byte[]> holdout = tokenCorpus(7L, TOKENS_A, 32);
        long matchedBytes = CompressionDictionary.compressedSizeSum(holdout, matched.bytes(), WireCompression.DEFAULT_LEVEL);
        long mismatchedBytes = CompressionDictionary.compressedSizeSum(holdout, mismatched.bytes(), WireCompression.DEFAULT_LEVEL);
        assertTrue(matchedBytes > 0L);
        assertTrue(matchedBytes < mismatchedBytes,
            "matched dict should compress its own traffic better: matched=" + matchedBytes + " mismatched=" + mismatchedBytes);
    }
}
