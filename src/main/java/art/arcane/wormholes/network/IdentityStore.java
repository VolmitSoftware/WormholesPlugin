package art.arcane.wormholes.network;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Set;

public final class IdentityStore {
    private static final String KEY_ALGORITHM = "Ed25519";
    private static final String PRIVATE_FILE = "server.key";
    private static final String PUBLIC_FILE = "server.pub";

    private final Path directory;
    private final KeyPair keyPair;

    private IdentityStore(Path directory, KeyPair keyPair) {
        this.directory = directory;
        this.keyPair = keyPair;
    }

    public static IdentityStore loadOrCreate(Path dataDirectory) throws IOException {
        Path identityDirectory = dataDirectory.resolve("identity");
        Path privatePath = identityDirectory.resolve(PRIVATE_FILE);
        Path publicPath = identityDirectory.resolve(PUBLIC_FILE);
        Files.createDirectories(identityDirectory);
        if (Files.isRegularFile(privatePath) && Files.isRegularFile(publicPath)) {
            return new IdentityStore(identityDirectory, readKeyPair(privatePath, publicPath));
        }
        KeyPair keyPair = generateKeyPair();
        Files.write(privatePath, keyPair.getPrivate().getEncoded());
        Files.write(publicPath, keyPair.getPublic().getEncoded());
        restrictPrivateKey(privatePath);
        return new IdentityStore(identityDirectory, keyPair);
    }

    public byte[] publicKeyBytes() {
        return keyPair.getPublic().getEncoded();
    }

    public PrivateKey privateKey() {
        return keyPair.getPrivate();
    }

    public Path directory() {
        return directory;
    }

    private static KeyPair readKeyPair(Path privatePath, Path publicPath) throws IOException {
        try {
            KeyFactory factory = KeyFactory.getInstance(KEY_ALGORITHM);
            byte[] privateBytes = Files.readAllBytes(privatePath);
            byte[] publicBytes = Files.readAllBytes(publicPath);
            PrivateKey privateKey = factory.generatePrivate(new PKCS8EncodedKeySpec(privateBytes));
            PublicKey publicKey = factory.generatePublic(new X509EncodedKeySpec(publicBytes));
            return new KeyPair(publicKey, privateKey);
        } catch (Exception e) {
            throw new IOException("Could not load Wormholes identity key pair", e);
        }
    }

    private static KeyPair generateKeyPair() throws IOException {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance(KEY_ALGORITHM);
            return generator.generateKeyPair();
        } catch (Exception e) {
            throw new IOException("Could not generate Wormholes identity key pair", e);
        }
    }

    private static void restrictPrivateKey(Path privatePath) throws IOException {
        try {
            Files.setPosixFilePermissions(privatePath, Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE));
        } catch (UnsupportedOperationException ignored) {
        }
    }
}
