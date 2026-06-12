package art.arcane.wormholes.config.toml;

import art.arcane.wormholes.util.project.config.ConfigDescription;
import art.arcane.wormholes.util.project.config.ConfigDoc;

import java.util.ArrayList;
import java.util.List;

@ConfigDoc({
    "Wormholes cross-server networking.",
    "Each server in the network runs Wormholes with this file configured: a unique server-name,",
    "a listen address for the inter-server channel, a shared secret, and one [[peers]] block per remote server.",
    "Player transfer to a peer uses its public-host/public-port (the address PLAYERS use to join that server).",
    "The destination server must set accepts-transfers=true in server.properties for packet transfers."
})
public class NetworkConfig {
    @ConfigDescription({
        "Master switch for cross-server networking.",
        "When false no sockets are opened and universal tunnels stay closed."
    })
    public boolean enabled = false;

    @ConfigDescription("Bind address for the inter-server listen socket.")
    public String listenHost = "0.0.0.0";

    @ConfigDescription("Bind port for the inter-server listen socket.")
    public int listenPort = 8901;

    @ConfigDescription({
        "Address OTHER SERVERS (and their players) can reach this server at. This IS this server's identity",
        "in the wormhole network: just a domain or IP (the game port is appended automatically when it",
        "is not the default 25565). Leave blank to auto-detect the LAN IP - the first portal export or",
        "import pins the detected address here. Set your public domain/IP for internet deployments."
    })
    public String advertiseHost = "";

    @ConfigDescription({
        "Shared secret used for the HMAC handshake between peers. Must be identical on every server.",
        "Anyone holding this secret is fully trusted by the wormhole network; use a long random value.",
        "Traffic is NOT encrypted - tunnel over WireGuard/stunnel when crossing untrusted networks."
    })
    public String sharedSecret = "";

    @ConfigDescription({
        "How players are moved to the destination server: auto, packet, or proxy.",
        "packet uses the native transfer packet (standalone servers; destination needs accepts-transfers=true).",
        "proxy sends a BungeeCord/Velocity Connect plugin message (requires both servers behind one proxy,",
        "with the peer name matching the proxy's registered server name). auto prefers packet."
    })
    public String transferMode = "auto";

    @ConfigDescription("Milliseconds to wait for the destination to acknowledge a player handoff before aborting.")
    public long handoffTimeoutMs = 2500L;

    @ConfigDescription({
        "Accept players transferred from other servers even when server.properties has accepts-transfers=false.",
        "Transferred connections are treated exactly like normal joins, so this grants no extra access.",
        "Disable to enforce the vanilla accepts-transfers setting instead."
    })
    public boolean autoAcceptTransfers = true;

    @ConfigDescription({
        "Comma-separated entity types this server refuses to accept through incoming cross-server portals,",
        "for example: WITHER, ENDER_DRAGON, TNT. Players are never blocked by this list."
    })
    public String entityTransferDenyTypes = "";

    @ConfigDescription({
        "How many blocks behind (and in front of) a gateway this server captures and streams to peers",
        "so their players can see through the portal. Larger values stream and load more chunks."
    })
    public int viewDepth = 32;

    @ConfigDescription("Lateral padding in blocks around the gateway frame included in the streamed view volume.")
    public int viewLateralPad = 8;

    @ConfigDescription({
        "Full view refresh cadence in ticks (catches fluid flow, pistons, growth and other quiet changes).",
        "Block breaks/places inside the view are streamed within a couple of ticks regardless.",
        "Unchanged view sections are never re-sent, so an idle scene costs no bandwidth."
    })
    public int viewHeartbeatTicks = 60;

    @ConfigDescription("Seconds without any local observer before this server unsubscribes from a peer's portal view.")
    public int viewUnsubscribeGraceSeconds = 30;

    @ConfigDescription({
        "How often (in ticks) entity positions near a watched gateway are sampled and streamed to peers.",
        "Unchanged entity sets are not re-sent. Lower is smoother through-portal motion, higher is cheaper."
    })
    public int viewEntityIntervalTicks = 10;

    @ConfigDescription({
        "Block rendered where the remote view volume ends (beyond view-depth / lateral padding).",
        "Any block-state string works, for example minecraft:black_concrete or minecraft:air."
    })
    public String viewFallbackBlock = "minecraft:black_concrete";

    @ConfigDescription({
        "One block per remote server, for example:",
        "[[peers]]",
        "name = \"hub\"",
        "host = \"10.0.0.2\"",
        "port = 8901",
        "public-host = \"play.example.com\"",
        "public-port = 25565"
    })
    public List<PeerEntry> peers = new ArrayList<>();

    public static class PeerEntry {
        @ConfigDescription("The peer's server-name (must match its own network.toml server-name).")
        public String name = "";

        @ConfigDescription("Address this server uses to reach the peer's wormhole listen socket.")
        public String host = "";

        @ConfigDescription({
            "Optional comma-separated alternate addresses tried in rotation when host is unreachable",
            "(filled automatically by portal-code imports: public IP, LAN IP)."
        })
        public String fallbackHosts = "";

        @ConfigDescription("The peer's wormhole listen port.")
        public int port = 8901;

        @ConfigDescription("Address PLAYERS use to join the peer (for the transfer packet).")
        public String publicHost = "";

        @ConfigDescription("Port PLAYERS use to join the peer (for the transfer packet).")
        public int publicPort = 25565;
    }
}
