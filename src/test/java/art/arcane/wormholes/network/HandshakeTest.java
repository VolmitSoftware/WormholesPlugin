package art.arcane.wormholes.network;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HandshakeTest {
    @Test
    void macIsDeterministicForSameInputs() {
        byte[] nonceA = Handshake.newNonce();
        byte[] nonceB = Handshake.newNonce();
        byte[] first = Handshake.mac("secret", Handshake.ROLE_ACCEPTOR, "hub", nonceA, nonceB);
        byte[] second = Handshake.mac("secret", Handshake.ROLE_ACCEPTOR, "hub", nonceA, nonceB);
        assertArrayEquals(first, second);
        assertEquals(Handshake.MAC_LENGTH, first.length);
    }

    @Test
    void macDiffersWhenAnyInputDiffers() {
        byte[] nonceA = Handshake.newNonce();
        byte[] nonceB = Handshake.newNonce();
        byte[] base = Handshake.mac("secret", Handshake.ROLE_ACCEPTOR, "hub", nonceA, nonceB);

        assertFalse(Handshake.verify(base, Handshake.mac("other", Handshake.ROLE_ACCEPTOR, "hub", nonceA, nonceB)));
        assertFalse(Handshake.verify(base, Handshake.mac("secret", Handshake.ROLE_DIALER, "hub", nonceA, nonceB)));
        assertFalse(Handshake.verify(base, Handshake.mac("secret", Handshake.ROLE_ACCEPTOR, "spoke", nonceA, nonceB)));
        assertFalse(Handshake.verify(base, Handshake.mac("secret", Handshake.ROLE_ACCEPTOR, "hub", nonceB, nonceA)));
    }

    @Test
    void verifyAcceptsMatchingMacs() {
        byte[] nonceA = Handshake.newNonce();
        byte[] nonceB = Handshake.newNonce();
        byte[] mac = Handshake.mac("secret", Handshake.ROLE_DIALER, "alpha", nonceA, nonceB);
        assertTrue(Handshake.verify(mac, Handshake.mac("secret", Handshake.ROLE_DIALER, "alpha", nonceA, nonceB)));
    }

    @Test
    void verifyRejectsNullAndLengthMismatch() {
        byte[] mac = Handshake.mac("secret", Handshake.ROLE_DIALER, "alpha", Handshake.newNonce(), Handshake.newNonce());
        assertFalse(Handshake.verify(mac, null));
        assertFalse(Handshake.verify(null, mac));
        assertFalse(Handshake.verify(mac, new byte[16]));
    }

    @Test
    void noncesAreUniqueAndCorrectLength() {
        byte[] first = Handshake.newNonce();
        byte[] second = Handshake.newNonce();
        assertEquals(Handshake.NONCE_LENGTH, first.length);
        assertFalse(Handshake.verify(first, second));
    }
}
