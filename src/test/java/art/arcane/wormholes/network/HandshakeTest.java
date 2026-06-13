package art.arcane.wormholes.network;

import org.junit.jupiter.api.Test;

import java.security.KeyPair;
import java.security.KeyPairGenerator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HandshakeTest {
    @Test
    void signatureVerifiesForMatchingInputs() throws Exception {
        KeyPair signer = keyPair();
        KeyPair peer = keyPair();
        byte[] nonceA = Handshake.newNonce();
        byte[] nonceB = Handshake.newNonce();
        byte[] signature = Handshake.sign(signer.getPrivate(), Handshake.ROLE_ACCEPTOR, "hub", "boat", nonceA, nonceB, signer.getPublic().getEncoded(), peer.getPublic().getEncoded());

        assertTrue(Handshake.verify(signer.getPublic().getEncoded(), signature, Handshake.ROLE_ACCEPTOR, "hub", "boat", nonceA, nonceB, signer.getPublic().getEncoded(), peer.getPublic().getEncoded()));
    }

    @Test
    void signatureRejectsChangedInputs() throws Exception {
        KeyPair signer = keyPair();
        KeyPair peer = keyPair();
        byte[] nonceA = Handshake.newNonce();
        byte[] nonceB = Handshake.newNonce();
        byte[] signature = Handshake.sign(signer.getPrivate(), Handshake.ROLE_ACCEPTOR, "hub", "boat", nonceA, nonceB, signer.getPublic().getEncoded(), peer.getPublic().getEncoded());

        assertFalse(Handshake.verify(signer.getPublic().getEncoded(), signature, Handshake.ROLE_DIALER, "hub", "boat", nonceA, nonceB, signer.getPublic().getEncoded(), peer.getPublic().getEncoded()));
        assertFalse(Handshake.verify(signer.getPublic().getEncoded(), signature, Handshake.ROLE_ACCEPTOR, "spoke", "boat", nonceA, nonceB, signer.getPublic().getEncoded(), peer.getPublic().getEncoded()));
        assertFalse(Handshake.verify(signer.getPublic().getEncoded(), signature, Handshake.ROLE_ACCEPTOR, "hub", "boat", nonceB, nonceA, signer.getPublic().getEncoded(), peer.getPublic().getEncoded()));
        assertFalse(Handshake.verify(peer.getPublic().getEncoded(), signature, Handshake.ROLE_ACCEPTOR, "hub", "boat", nonceA, nonceB, signer.getPublic().getEncoded(), peer.getPublic().getEncoded()));
    }

    @Test
    void publicKeyTextRoundTrips() throws Exception {
        KeyPair keyPair = keyPair();
        String encoded = Handshake.encodePublicKey(keyPair.getPublic().getEncoded());
        byte[] decoded = Handshake.decodePublicKeyText(encoded);
        assertNotNull(decoded);
        assertTrue(Handshake.sameKey(keyPair.getPublic().getEncoded(), decoded));
    }

    @Test
    void noncesAreUniqueAndCorrectLength() {
        byte[] first = Handshake.newNonce();
        byte[] second = Handshake.newNonce();
        assertEquals(Handshake.NONCE_LENGTH, first.length);
        assertFalse(Handshake.sameKey(first, second));
    }

    private static KeyPair keyPair() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("Ed25519");
        return generator.generateKeyPair();
    }
}
