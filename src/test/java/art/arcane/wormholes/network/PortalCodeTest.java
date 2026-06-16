package art.arcane.wormholes.network;

import org.junit.jupiter.api.Test;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PortalCodeTest {
    @Test
    void codeRoundTripsAllFields() throws Exception {
        UUID portalId = UUID.randomUUID();
        PortalCode original = new PortalCode("hub", "play.example.com", java.util.List.of("203.0.113.7", "192.168.1.50"), 8901, 25565, publicKey(), portalId, "Gateway 1a2b");
        String encoded = original.encode();
        assertTrue(encoded.startsWith(PortalCode.PREFIX));

        PortalCode decoded = PortalCode.decode(encoded);
        assertEquals(original, decoded);
    }

    @Test
    void typicalCodeFitsInChat() throws Exception {
        PortalCode code = new PortalCode("survival-main", "play.somewhere-long.example.com", java.util.List.of("203.0.113.7", "192.168.1.50"), 8901, 25565,
            publicKey(), UUID.randomUUID(), "Gateway abcd");
        assertTrue(code.encode().length() <= 250, "typical code should be chat-pasteable, was " + code.encode().length());
    }

    @Test
    void decodeToleratesSurroundingWhitespace() throws Exception {
        PortalCode original = new PortalCode("hub", "10.0.0.2", java.util.List.of(), 8901, 25565, publicKey(), UUID.randomUUID(), "Gate");
        assertEquals(original, PortalCode.decode("  " + original.encode() + " \n"));
    }

    @Test
    void invalidCodesReturnNull() throws Exception {
        assertNull(PortalCode.decode(null));
        assertNull(PortalCode.decode(""));
        assertNull(PortalCode.decode("not a code"));
        assertNull(PortalCode.decode("WHP1.!!!!not-base64!!!!"));
        assertNull(PortalCode.decode(PortalCode.PREFIX));

        String valid = new PortalCode("hub", "10.0.0.2", java.util.List.of(), 8901, 25565, publicKey(), UUID.randomUUID(), "Gate").encode();
        assertNull(PortalCode.decode(valid.substring(0, valid.length() - 10)), "truncated code must not decode");
    }

    @Test
    void blankRequiredFieldsRejected() throws Exception {
        String publicKey = publicKey();
        String blankServer = new PortalCode("", "10.0.0.2", java.util.List.of(), 8901, 25565, publicKey, UUID.randomUUID(), "Gate").encode();
        assertNull(PortalCode.decode(blankServer));
        String blankHost = new PortalCode("hub", "", java.util.List.of(), 8901, 25565, publicKey, UUID.randomUUID(), "Gate").encode();
        assertNull(PortalCode.decode(blankHost));
        String blankPublicKey = new PortalCode("hub", "10.0.0.2", java.util.List.of(), 8901, 25565, "", UUID.randomUUID(), "Gate").encode();
        assertNull(PortalCode.decode(blankPublicKey));
    }

    private static String publicKey() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("Ed25519");
        KeyPair keyPair = generator.generateKeyPair();
        return Handshake.encodePublicKey(keyPair.getPublic().getEncoded());
    }
}
