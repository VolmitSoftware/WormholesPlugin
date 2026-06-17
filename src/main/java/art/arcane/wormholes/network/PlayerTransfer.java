package art.arcane.wormholes.network;

import art.arcane.wormholes.Wormholes;
import art.arcane.wormholes.config.toml.NetworkConfig;

import org.bukkit.entity.Player;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.Locale;

public final class PlayerTransfer {
    public static final String PROXY_CHANNEL = "BungeeCord";

    private PlayerTransfer() {
    }

    public static boolean send(Player player, NetworkConfig.PeerEntry peer, String transferMode) {
        String mode = transferMode == null ? "auto" : transferMode.toLowerCase(Locale.ROOT);
        if (mode.equals("proxy")) {
            return sendViaProxy(player, peer);
        }
        if (mode.equals("auto") && shouldUseProxy(peer)) {
            return sendViaProxy(player, peer);
        }
        return sendViaTransferPacket(player, peer);
    }

    private static boolean shouldUseProxy(NetworkConfig.PeerEntry peer) {
        return peer.useProxy;
    }

    private static boolean sendViaTransferPacket(Player player, NetworkConfig.PeerEntry peer) {
        String host = peer.publicHost == null || peer.publicHost.isBlank() ? peer.host : peer.publicHost;
        int port = peer.publicPort > 0 ? peer.publicPort : 25565;
        if (host == null || host.isBlank()) {
            Wormholes.w("net: peer " + peer.name + " has no reachable host; cannot transfer " + player.getName());
            return false;
        }
        Wormholes.v("[xfer] transfer-packet " + player.getName() + " -> " + host + ":" + port + " (peer=" + peer.name + " publicHost=" + peer.publicHost + " publicPort=" + peer.publicPort + ")");
        player.transfer(host, port);
        return true;
    }

    private static boolean sendViaProxy(Player player, NetworkConfig.PeerEntry peer) {
        try {
            ByteArrayOutputStream buffer = new ByteArrayOutputStream(64);
            DataOutputStream out = new DataOutputStream(buffer);
            out.writeUTF("Connect");
            out.writeUTF(peer.name);
            player.sendPluginMessage(Wormholes.instance, PROXY_CHANNEL, buffer.toByteArray());
            return true;
        } catch (IOException e) {
            Wormholes.w("net: proxy transfer of " + player.getName() + " to " + peer.name + " failed: " + e.getMessage());
            return false;
        }
    }
}
