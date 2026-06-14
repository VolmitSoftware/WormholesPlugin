package art.arcane.wormholes.config.toml;

import art.arcane.wormholes.util.project.config.ConfigDescription;
import art.arcane.wormholes.util.project.config.ConfigDoc;

@ConfigDoc({
    "Wormholes cross-server networking.",
    "This file controls broad runtime behavior. Portal-code imports and learned routes are stored internally,",
    "so route tables do not live in network.toml.",
    "Best security and reliability come from opening the raw Wormholes port on both linked servers.",
    "Player transfer to a peer uses proxy routing for Boats or public-host/public-port for direct packet transfer."
})
public class NetworkConfig {
    @ConfigDescription({
        "Master switch for cross-server networking.",
        "When false no sockets are opened and universal tunnels stay closed."
    })
    public boolean enabled = false;

    @ConfigDescription({
        "Stable logical server name used by portal links and peer trust.",
        "Set this to a short unique name such as hub, survival, creative, or home.",
        "When blank, Wormholes derives a stable wh-... name from this server's generated public key."
    })
    public String serverName = "";

    @ConfigDescription({
        "Network role: anchor for public servers, boat for outbound-only servers, or mesh for interlinked anchors.",
        "This is descriptive for status and future routing; listen-enabled controls whether a socket is opened."
    })
    public String role = "anchor";

    @ConfigDescription({
        "When false, Wormholes does not open an inbound network socket and only dials known peer routes.",
        "Use false for Boat servers behind NAT that rely on an Anchor."
    })
    public boolean listenEnabled = true;

    @ConfigDescription({
        "Bind address for the inter-server listen socket.",
        "Leave blank to listen on all interfaces. This is the normal Anchor setting."
    })
    public String listenHost = "";

    @ConfigDescription({
        "Bind port for the signed raw Wormholes inter-server socket.",
        "Prefer opening/forwarding this same Wormholes port on both linked servers.",
        "The Minecraft game-port status sideband can fragment large frames, but it is slower and should stay a fallback."
    })
    public int listenPort = 8901;

    @ConfigDescription({
        "Address OTHER SERVERS can reach this server's Wormholes socket at: just a domain or IP.",
        "This is advertised in portal codes and handshakes, while server-name remains the stable portal identity.",
        "Leave blank to auto-detect when exporting a portal code. Set your public domain/IP for internet deployments."
    })
    public String advertiseHost = "";

    @ConfigDescription({
        "Accept a first inbound peer after it proves ownership of its advertised public key.",
        "The peer key is stored in Wormholes' trust store so later connections must use the same key.",
        "Disable this if every peer should be pre-approved by importing a portal code first."
    })
    public boolean trustOnFirstUse = true;

    @ConfigDescription("Allow this Anchor to relay Wormholes messages for trusted Boats or mesh peers.")
    public boolean relayEnabled = false;

    @ConfigDescription({
        "How players are moved to the destination server: auto, packet, or proxy.",
        "packet uses the native transfer packet (standalone servers; destination needs accepts-transfers=true).",
        "proxy sends a BungeeCord/Velocity Connect plugin message (requires both servers behind one proxy,",
        "with the peer name matching the proxy's registered server name). auto uses proxy for Boat peers or",
        "when public-host is blank, otherwise it uses packet transfer."
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

    public static class PeerEntry {
        @ConfigDescription("The peer's server-name (must match its own network.toml server-name).")
        public String name = "";

        @ConfigDescription("Address this server uses to reach the peer's wormhole listen socket.")
        public String host = "";

        @ConfigDescription({
            "Optional comma-separated alternate addresses tried in rotation when host is unreachable",
            "(portal-code imports store these in the internal route store instead)."
        })
        public String fallbackHosts = "";

        @ConfigDescription("The peer's wormhole listen port.")
        public int port = 8901;

        @ConfigDescription("Address PLAYERS use to join the peer (for the transfer packet).")
        public String publicHost = "";

        @ConfigDescription("Port PLAYERS use to join the peer (for the transfer packet).")
        public int publicPort = 25565;

        @ConfigDescription("Relationship to this peer: direct, anchor, boat, or mesh.")
        public String relationship = "direct";
    }
}
