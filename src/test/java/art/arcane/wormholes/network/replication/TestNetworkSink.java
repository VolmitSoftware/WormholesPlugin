package art.arcane.wormholes.network.replication;

import art.arcane.wormholes.config.toml.NetworkConfig;
import art.arcane.wormholes.network.NetworkManager;
import art.arcane.wormholes.network.WireMessage;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

public final class TestNetworkSink extends NetworkManager {
    private final Map<String, List<WireMessage>> outbound = new ConcurrentHashMap<>();

    public TestNetworkSink(Path dataDirectory) {
        super(Logger.getLogger("Wormholes-test"), new NetworkConfig(), "26.2", "test", 25565, dataDirectory);
    }

    private final List<NetworkManager.PeerSnapshot> fakePeers = new ArrayList<>();

    @Override
    public boolean send(String peerName, WireMessage message) {
        outbound.computeIfAbsent(peerName, ignored -> new ArrayList<>()).add(message);
        return true;
    }

    @Override
    public List<NetworkManager.PeerSnapshot> peerSnapshots() {
        return List.copyOf(fakePeers);
    }

    public void registerFakePeer(String peerName) {
        fakePeers.add(new NetworkManager.PeerSnapshot(peerName, "TCP", "127.0.0.1:" + peerName.hashCode(), "plain", 0, "-", 0L, 1L, true, false));
    }

    public List<WireMessage> sentTo(String peerName) {
        List<WireMessage> messages = outbound.get(peerName);
        return messages == null ? List.of() : List.copyOf(messages);
    }

    public int sentCount(String peerName) {
        List<WireMessage> messages = outbound.get(peerName);
        return messages == null ? 0 : messages.size();
    }

    public void clear() {
        outbound.clear();
    }
}
