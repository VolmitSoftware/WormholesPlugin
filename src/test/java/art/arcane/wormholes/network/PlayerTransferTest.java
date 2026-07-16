package art.arcane.wormholes.network;

import art.arcane.wormholes.config.toml.NetworkConfig;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlayerTransferTest {
    @Test
    void localClientUsesPrivateFallbackWithDestinationGamePort() throws Exception {
        NetworkConfig.PeerEntry peer = route();
        AtomicReference<TransferCall> call = new AtomicReference<>();
        Player player = player(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 60123), call, false);

        assertTrue(PlayerTransfer.send(player, peer, "direct"));
        assertEquals(new TransferCall("192.168.1.42", 25566), call.get());
    }

    @Test
    void publicClientUsesPublicEndpoint() throws Exception {
        NetworkConfig.PeerEntry peer = route();
        InetSocketAddress client = new InetSocketAddress(InetAddress.getByName("198.51.100.7"), 60123);

        assertEquals("204.111.10.237", PlayerTransfer.directHost(client, peer));
    }

    @Test
    void unknownClientUsesPublicEndpoint() {
        NetworkConfig.PeerEntry peer = route();

        assertEquals("204.111.10.237", PlayerTransfer.directHost(null, peer));
        assertEquals("204.111.10.237", PlayerTransfer.directHost(
            InetSocketAddress.createUnresolved("workstation.lan", 60123), peer));
    }

    @Test
    void localHostnameIsNotResolvedOnTransferThread() throws Exception {
        NetworkConfig.PeerEntry peer = route();
        peer.fallbackHosts = "destination.internal";
        InetSocketAddress client = new InetSocketAddress(InetAddress.getByName("10.0.0.12"), 60123);

        assertEquals("204.111.10.237", PlayerTransfer.directHost(client, peer));
    }

    @Test
    void ipv6UniqueLocalClientUsesUniqueLocalFallback() throws Exception {
        NetworkConfig.PeerEntry peer = route();
        peer.fallbackHosts = "fd00::42";
        InetSocketAddress client = new InetSocketAddress(InetAddress.getByName("fd00::12"), 60123);

        assertEquals("fd00::42", PlayerTransfer.directHost(client, peer));
    }

    @Test
    void rejectedPaperTransferReturnsFalse() throws Exception {
        NetworkConfig.PeerEntry peer = route();
        Player player = player(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 60123),
            new AtomicReference<>(), true);

        assertFalse(PlayerTransfer.send(player, peer, "direct"));
    }

    @Test
    void automaticModeHonorsProxyFlag() {
        NetworkConfig.PeerEntry peer = route();
        peer.useProxy = true;

        assertTrue(PlayerTransfer.usesProxy(peer, "auto"));
        assertFalse(PlayerTransfer.usesProxy(peer, "direct"));
        assertTrue(PlayerTransfer.hasDirectHost(peer));
    }

    private static NetworkConfig.PeerEntry route() {
        NetworkConfig.PeerEntry peer = new NetworkConfig.PeerEntry();
        peer.name = "beta";
        peer.host = "204.111.10.237";
        peer.fallbackHosts = "192.168.1.42,127.0.0.1";
        peer.publicHost = "204.111.10.237";
        peer.publicPort = 25566;
        return peer;
    }

    private static Player player(InetSocketAddress address, AtomicReference<TransferCall> call, boolean reject) {
        return (Player) Proxy.newProxyInstance(Player.class.getClassLoader(), new Class<?>[]{Player.class},
            (proxy, method, arguments) -> switch (method.getName()) {
                case "getAddress" -> address;
                case "getName" -> "Traveler";
                case "transfer" -> {
                    call.set(new TransferCall((String) arguments[0], ((Integer) arguments[1]).intValue()));
                    if (reject) {
                        throw new IllegalStateException("transfer unavailable");
                    }
                    yield null;
                }
                case "hashCode" -> Integer.valueOf(System.identityHashCode(proxy));
                case "equals" -> Boolean.valueOf(proxy == arguments[0]);
                case "toString" -> "PlayerTransferTestPlayer";
                default -> throw new UnsupportedOperationException(method.getName());
            });
    }

    private record TransferCall(String host, int port) {
    }
}
