package art.arcane.wormholes.network;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PortalCodeTest {
    @Test
    void codeRoundTripsAllFields() {
        UUID portalId = UUID.randomUUID();
        PortalCode original = new PortalCode("hub", "play.example.com", java.util.List.of("203.0.113.7", "192.168.1.50"), 8901, 25565, "sup3r-s3cret", portalId, "Gateway 1a2b");
        String encoded = original.encode();
        assertTrue(encoded.startsWith(PortalCode.PREFIX));

        PortalCode decoded = PortalCode.decode(encoded);
        assertEquals(original, decoded);
    }

    @Test
    void typicalCodeFitsInChat() {
        PortalCode code = new PortalCode("survival-main", "play.somewhere-long.example.com", java.util.List.of("203.0.113.7", "192.168.1.50"), 8901, 25565,
            "AbCdEfGhIjKlMnOpQrStUvWx", UUID.randomUUID(), "Gateway abcd");
        assertTrue(code.encode().length() <= 250, "typical code should be chat-pasteable, was " + code.encode().length());
    }

    @Test
    void decodeToleratesSurroundingWhitespace() {
        PortalCode original = new PortalCode("hub", "10.0.0.2", java.util.List.of(), 8901, 25565, "secret", UUID.randomUUID(), "Gate");
        assertEquals(original, PortalCode.decode("  " + original.encode() + " \n"));
    }

    @Test
    void invalidCodesReturnNull() {
        assertNull(PortalCode.decode(null));
        assertNull(PortalCode.decode(""));
        assertNull(PortalCode.decode("not a code"));
        assertNull(PortalCode.decode("WHP1.!!!!not-base64!!!!"));
        assertNull(PortalCode.decode(PortalCode.PREFIX));

        String valid = new PortalCode("hub", "10.0.0.2", java.util.List.of(), 8901, 25565, "secret", UUID.randomUUID(), "Gate").encode();
        assertNull(PortalCode.decode(valid.substring(0, valid.length() - 10)), "truncated code must not decode");
    }

    @Test
    void blankRequiredFieldsRejected() {
        String blankServer = new PortalCode("", "10.0.0.2", java.util.List.of(), 8901, 25565, "secret", UUID.randomUUID(), "Gate").encode();
        assertNull(PortalCode.decode(blankServer));
        String blankHost = new PortalCode("hub", "", java.util.List.of(), 8901, 25565, "secret", UUID.randomUUID(), "Gate").encode();
        assertNull(PortalCode.decode(blankHost));
        String blankSecret = new PortalCode("hub", "10.0.0.2", java.util.List.of(), 8901, 25565, "", UUID.randomUUID(), "Gate").encode();
        assertNull(PortalCode.decode(blankSecret));
    }
}
