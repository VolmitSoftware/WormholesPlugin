package art.arcane.wormholes.network;

public record LocalIdentity(
    String serverName,
    String secret,
    String mcVersion,
    String pluginVersion,
    String advertiseHost,
    int wormholePort,
    int gamePort
) {
}
