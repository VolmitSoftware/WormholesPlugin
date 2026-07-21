package art.arcane.wormholes.network;

import art.arcane.wormholes.Wormholes;
import art.arcane.wormholes.config.toml.NetworkConfig;

import org.bukkit.entity.Player;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.Locale;
import java.util.logging.Level;

public final class PlayerTransfer {
    public enum Method {
        DIRECT,
        PROXY
    }

    public static final String PROXY_CHANNEL = "BungeeCord";

    private PlayerTransfer() {
    }

    public static boolean send(Player player, NetworkConfig.PeerEntry peer, String transferMode) {
        return send(player, peer, resolveMethod(peer, transferMode));
    }

    public static boolean send(Player player, NetworkConfig.PeerEntry peer, Method method) {
        return send(player, peer, method, null);
    }

    static boolean send(Player player, NetworkConfig.PeerEntry peer, Method method,
                        String verifiedPrivateHost) {
        if (method == Method.PROXY) {
            return sendViaProxy(player, peer);
        }
        return sendViaTransferPacket(player, peer, verifiedPrivateHost);
    }

    static boolean usesProxy(NetworkConfig.PeerEntry peer, String transferMode) {
        return resolveMethod(peer, transferMode) == Method.PROXY;
    }

    static Method resolveMethod(NetworkConfig.PeerEntry peer, String transferMode) {
        String mode = transferMode == null ? "auto" : transferMode.toLowerCase(Locale.ROOT);
        return mode.equals("proxy") || mode.equals("auto") && peer.useProxy ? Method.PROXY : Method.DIRECT;
    }

    static boolean hasDirectHost(NetworkConfig.PeerEntry peer) {
        return !PeerEndpointResolver.gameHosts(peer).isEmpty();
    }

    static String directHost(InetSocketAddress clientAddress, NetworkConfig.PeerEntry peer) {
        return directHost(clientAddress, peer, null);
    }

    static String directHost(InetSocketAddress clientAddress, NetworkConfig.PeerEntry peer,
                             String verifiedPrivateHost) {
        return PeerEndpointResolver.playerTransferHost(peer, clientAddress, verifiedPrivateHost);
    }

    private static boolean sendViaTransferPacket(Player player, NetworkConfig.PeerEntry peer,
                                                 String verifiedPrivateHost) {
        InetSocketAddress clientAddress = player.getAddress();
        String host = directHost(clientAddress, peer, verifiedPrivateHost);
        int port = PeerEndpointResolver.gamePort(peer);
        if (host == null || host.isBlank()) {
            Wormholes.w("net: peer " + peer.name + " has no reachable host; cannot transfer " + player.getName());
            return false;
        }
        Wormholes.v("[xfer] transfer-packet " + player.getName()
            + " client=" + formatAddress(clientAddress)
            + " localClient=" + PeerEndpointResolver.isLocalClient(clientAddress)
            + " verifiedPrivateHost=" + (verifiedPrivateHost == null ? "-" : verifiedPrivateHost)
            + " selected=" + host + ":" + port
            + " peer=" + peer.name
            + " peerHost=" + peer.host
            + " fallbackHosts=" + peer.fallbackHosts
            + " publicHost=" + peer.publicHost
            + " publicPort=" + peer.publicPort);
        try {
            player.transfer(host, port);
            return true;
        } catch (IllegalStateException error) {
            logTransferFailure("Direct transfer of " + player.getName() + " to " + peer.name + " failed", error);
            return false;
        }
    }

    private static boolean sendViaProxy(Player player, NetworkConfig.PeerEntry peer) {
        try {
            ByteArrayOutputStream buffer = new ByteArrayOutputStream(64);
            DataOutputStream out = new DataOutputStream(buffer);
            out.writeUTF("Connect");
            out.writeUTF(peer.name);
            player.sendPluginMessage(Wormholes.instance, PROXY_CHANNEL, buffer.toByteArray());
            return true;
        } catch (IOException | RuntimeException error) {
            logTransferFailure("Proxy transfer of " + player.getName() + " to " + peer.name + " failed", error);
            return false;
        }
    }

    private static void logTransferFailure(String message, Throwable error) {
        if (Wormholes.instance == null) {
            Wormholes.w(message + ": " + error);
            return;
        }
        Wormholes.instance.getLogger().log(Level.WARNING, message, error);
    }

    private static String formatAddress(InetSocketAddress address) {
        if (address == null) {
            return "-";
        }
        return address.getHostString() + ":" + address.getPort();
    }
}
