package art.arcane.wormholes.network;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;

public final class Handshake {
    public static final int NONCE_LENGTH = 32;
    public static final int SIGNATURE_MAX_LENGTH = 512;
    public static final int PUBLIC_KEY_MAX_LENGTH = 512;
    public static final String ROLE_ACCEPTOR = "acceptor";
    public static final String ROLE_DIALER = "dialer";

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final String KEY_ALGORITHM = "Ed25519";
    private static final String SIGNATURE_ALGORITHM = "Ed25519";

    private Handshake() {
    }

    public static byte[] newNonce() {
        byte[] nonce = new byte[NONCE_LENGTH];
        RANDOM.nextBytes(nonce);
        return nonce;
    }

    public static byte[] sign(PrivateKey privateKey, String role, String signerName, String peerName, byte[] dialerNonce, byte[] acceptorNonce, byte[] signerPublicKey, byte[] peerPublicKey) {
        try {
            Signature signature = Signature.getInstance(SIGNATURE_ALGORITHM);
            signature.initSign(privateKey);
            signature.update(payload(role, signerName, peerName, dialerNonce, acceptorNonce, signerPublicKey, peerPublicKey));
            return signature.sign();
        } catch (Exception e) {
            throw new IllegalStateException("Ed25519 signing unavailable", e);
        }
    }

    public static byte[] sign(PrivateKey privateKey, byte[] payload) {
        try {
            Signature signature = Signature.getInstance(SIGNATURE_ALGORITHM);
            signature.initSign(privateKey);
            signature.update(payload);
            return signature.sign();
        } catch (Exception e) {
            throw new IllegalStateException("Ed25519 signing unavailable", e);
        }
    }

    public static boolean verify(byte[] publicKey, byte[] signatureBytes, String role, String signerName, String peerName, byte[] dialerNonce, byte[] acceptorNonce, byte[] signerPublicKey, byte[] peerPublicKey) {
        if (publicKey == null || signatureBytes == null) {
            return false;
        }
        try {
            Signature signature = Signature.getInstance(SIGNATURE_ALGORITHM);
            signature.initVerify(decodePublicKey(publicKey));
            signature.update(payload(role, signerName, peerName, dialerNonce, acceptorNonce, signerPublicKey, peerPublicKey));
            return signature.verify(signatureBytes);
        } catch (Exception e) {
            return false;
        }
    }

    public static boolean verify(byte[] publicKey, byte[] signatureBytes, byte[] payload) {
        if (publicKey == null || signatureBytes == null || payload == null) {
            return false;
        }
        try {
            Signature signature = Signature.getInstance(SIGNATURE_ALGORITHM);
            signature.initVerify(decodePublicKey(publicKey));
            signature.update(payload);
            return signature.verify(signatureBytes);
        } catch (Exception e) {
            return false;
        }
    }

    public static PublicKey decodePublicKey(byte[] publicKey) throws Exception {
        if (publicKey == null || publicKey.length == 0 || publicKey.length > PUBLIC_KEY_MAX_LENGTH) {
            throw new IllegalArgumentException("Invalid public key length");
        }
        KeyFactory factory = KeyFactory.getInstance(KEY_ALGORITHM);
        return factory.generatePublic(new X509EncodedKeySpec(publicKey));
    }

    public static String encodePublicKey(byte[] publicKey) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(publicKey);
    }

    public static byte[] decodePublicKeyText(String publicKey) {
        if (publicKey == null || publicKey.isBlank()) {
            return null;
        }
        try {
            byte[] decoded = Base64.getUrlDecoder().decode(publicKey.trim());
            decodePublicKey(decoded);
            return decoded;
        } catch (Exception e) {
            return null;
        }
    }

    public static String fingerprint(byte[] publicKey) {
        if (publicKey == null) {
            return "unknown";
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(publicKey);
            StringBuilder builder = new StringBuilder(23);
            for (int i = 0; i < 8; i++) {
                if (i > 0) {
                    builder.append(':');
                }
                String hex = Integer.toHexString(hash[i] & 0xFF);
                if (hex.length() == 1) {
                    builder.append('0');
                }
                builder.append(hex);
            }
            return builder.toString();
        } catch (Exception e) {
            return "unknown";
        }
    }

    public static boolean sameKey(byte[] expected, byte[] actual) {
        return expected != null && actual != null && MessageDigest.isEqual(expected, actual);
    }

    private static byte[] payload(String role, String signerName, String peerName, byte[] dialerNonce, byte[] acceptorNonce, byte[] signerPublicKey, byte[] peerPublicKey) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream(256);
        DataOutputStream out = new DataOutputStream(buffer);
        out.writeUTF(role);
        out.writeUTF(signerName);
        out.writeUTF(peerName);
        WireCodec.writeByteArray(out, dialerNonce, NONCE_LENGTH);
        WireCodec.writeByteArray(out, acceptorNonce, NONCE_LENGTH);
        WireCodec.writeByteArray(out, signerPublicKey, PUBLIC_KEY_MAX_LENGTH);
        WireCodec.writeByteArray(out, peerPublicKey, PUBLIC_KEY_MAX_LENGTH);
        out.flush();
        return buffer.toByteArray();
    }
}
