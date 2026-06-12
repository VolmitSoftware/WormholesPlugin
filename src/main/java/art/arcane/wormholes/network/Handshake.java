package art.arcane.wormholes.network;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;

public final class Handshake {
    public static final int NONCE_LENGTH = 32;
    public static final int MAC_LENGTH = 32;
    public static final String ROLE_ACCEPTOR = "acceptor";
    public static final String ROLE_DIALER = "dialer";

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final String HMAC_ALGORITHM = "HmacSHA256";

    private Handshake() {
    }

    public static byte[] newNonce() {
        byte[] nonce = new byte[NONCE_LENGTH];
        RANDOM.nextBytes(nonce);
        return nonce;
    }

    public static byte[] mac(String secret, String role, String serverName, byte[] dialerNonce, byte[] acceptorNonce) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), HMAC_ALGORITHM));
            mac.update(role.getBytes(StandardCharsets.UTF_8));
            mac.update((byte) 0);
            mac.update(serverName.getBytes(StandardCharsets.UTF_8));
            mac.update((byte) 0);
            mac.update(dialerNonce);
            mac.update(acceptorNonce);
            return mac.doFinal();
        } catch (Exception e) {
            throw new IllegalStateException("HMAC-SHA256 unavailable", e);
        }
    }

    public static boolean verify(byte[] expected, byte[] actual) {
        if (expected == null || actual == null) {
            return false;
        }
        return MessageDigest.isEqual(expected, actual);
    }
}
