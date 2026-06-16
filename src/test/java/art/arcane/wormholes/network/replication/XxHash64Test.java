package art.arcane.wormholes.network.replication;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class XxHash64Test {
    @Test
    void emptyInputProducesKnownSeedHash() {
        long hash = XxHash64.hash(new byte[0]);
        assertEquals(0xEF46DB3751D8E999L, hash);
    }

    @Test
    void shortInputAbcMatchesReference() {
        byte[] data = "abc".getBytes(StandardCharsets.UTF_8);
        long hash = XxHash64.hash(data);
        assertEquals(0x44BC2CF5AD770999L, hash);
    }

    @Test
    void mediumInput16BytesMatchesReference() {
        byte[] data = "abcdefghijklmnop".getBytes(StandardCharsets.UTF_8);
        long hash = XxHash64.hash(data);
        assertEquals(0x71CE8137CA2DD53DL, hash);
    }

    @Test
    void largeBufferIsDeterministic() {
        Random random = new Random(123456789L);
        byte[] buffer = new byte[4096];
        random.nextBytes(buffer);
        long first = XxHash64.hash(buffer);
        long second = XxHash64.hash(buffer);
        assertEquals(first, second);
    }

    @Test
    void differentInputsProduceDifferentHashes() {
        byte[] a = "abc".getBytes(StandardCharsets.UTF_8);
        byte[] b = "abd".getBytes(StandardCharsets.UTF_8);
        assertNotEquals(XxHash64.hash(a), XxHash64.hash(b));
    }

    @Test
    void offsetLengthVariantMatchesFullCall() {
        byte[] padded = new byte[64];
        Random random = new Random(42L);
        random.nextBytes(padded);
        byte[] sliced = new byte[32];
        System.arraycopy(padded, 16, sliced, 0, 32);
        long viaOffset = XxHash64.hash(padded, 16, 32, 0L);
        long viaCopy = XxHash64.hash(sliced);
        assertEquals(viaCopy, viaOffset);
    }
}
