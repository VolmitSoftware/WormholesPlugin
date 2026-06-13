package art.arcane.wormholes.network;

import java.security.PrivateKey;

public record LocalIdentity(
    String serverName,
    String mcVersion,
    String pluginVersion,
    String advertiseHost,
    int wormholePort,
    int gamePort,
    byte[] publicKey,
    PrivateKey privateKey
) {
}
