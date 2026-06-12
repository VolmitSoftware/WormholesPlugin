package art.arcane.wormholes.network;

import art.arcane.wormholes.config.toml.NetworkConfig;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiConsumer;
import java.util.logging.Logger;

public final class NetworkManager implements PeerConnection.Listener {
    public record PeerStatus(String name, String address, String state, boolean dialer, long rttMillis, long lastInboundAgeMillis, String lastError) {
    }

    private static final long DIAL_SCAN_INTERVAL_MS = 2_000L;
    private static final long KEEPALIVE_INTERVAL_MS = 5_000L;
    private static final long BACKOFF_BASE_MS = 1_000L;
    private static final long BACKOFF_MAX_MS = 30_000L;
    private static final int CONNECT_TIMEOUT_MS = 5_000;

    private final Logger logger;
    private final String mcVersion;
    private final String pluginVersion;
    private final int gamePort;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final Map<String, PeerConnection> readyPeers = new ConcurrentHashMap<>();
    private final Set<PeerConnection> pending = ConcurrentHashMap.newKeySet();
    private final Map<String, Long> nextDialAttempt = new ConcurrentHashMap<>();
    private final Map<String, Integer> dialFailures = new ConcurrentHashMap<>();
    private final Map<String, NetworkConfig.PeerEntry> learnedPeers = new ConcurrentHashMap<>();
    private final Map<String, Integer> dialCandidateIndex = new ConcurrentHashMap<>();
    private final Map<String, String> lastDialError = new ConcurrentHashMap<>();

    private volatile NetworkConfig config;
    private volatile BiConsumer<String, WireMessage> messageSink;
    private volatile BiConsumer<String, Boolean> peerStateSink;
    private volatile ServerSocket listenSocket;
    private volatile ScheduledExecutorService scheduler;
    private volatile Thread acceptThread;
    private volatile String detectedHost;

    public NetworkManager(Logger logger, NetworkConfig config, String mcVersion, String pluginVersion, int gamePort) {
        this.logger = logger;
        this.config = config;
        this.mcVersion = mcVersion;
        this.pluginVersion = pluginVersion;
        this.gamePort = gamePort;
    }

    private LocalIdentity identity(NetworkConfig active) {
        return new LocalIdentity(getLocalName(), active.sharedSecret, mcVersion, pluginVersion, getAdvertiseHost(), active.listenPort, gamePort);
    }

    public String getAdvertiseHost() {
        NetworkConfig active = config;
        if (active.advertiseHost != null && !active.advertiseHost.isBlank()) {
            return active.advertiseHost;
        }
        String detected = detectedHost;
        if (detected == null) {
            detected = AddressResolver.detectLanAddress();
            detectedHost = detected;
        }
        return detected;
    }

    public String getLocalName() {
        String host = getAdvertiseHost();
        return gamePort == 25565 ? host : host + ":" + gamePort;
    }

    public void setMessageSink(BiConsumer<String, WireMessage> sink) {
        this.messageSink = sink;
    }

    public void setPeerStateSink(BiConsumer<String, Boolean> sink) {
        this.peerStateSink = sink;
    }

    public void start() {
        NetworkConfig active = config;
        if (!active.enabled) {
            return;
        }
        if (active.sharedSecret == null || active.sharedSecret.isBlank()) {
            logger.warning("net: enabled but shared-secret is blank; networking stays off");
            return;
        }
        if (!running.compareAndSet(false, true)) {
            return;
        }

        try {
            ServerSocket socket = new ServerSocket();
            socket.setReuseAddress(true);
            socket.bind(new InetSocketAddress(active.listenHost, active.listenPort));
            listenSocket = socket;
        } catch (IOException e) {
            running.set(false);
            logger.severe("net: failed to bind " + active.listenHost + ":" + active.listenPort + " - " + e.getMessage());
            return;
        }

        Thread accept = new Thread(this::runAcceptLoop, "Wormholes-Net-Accept");
        accept.setDaemon(true);
        acceptThread = accept;
        accept.start();

        ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "Wormholes-Net-Timer");
            thread.setDaemon(true);
            return thread;
        });
        executor.scheduleWithFixedDelay(this::scanDials, 250L, DIAL_SCAN_INTERVAL_MS, TimeUnit.MILLISECONDS);
        executor.scheduleWithFixedDelay(this::keepalive, KEEPALIVE_INTERVAL_MS, KEEPALIVE_INTERVAL_MS, TimeUnit.MILLISECONDS);
        scheduler = executor;

        logger.info("net: " + getLocalName() + " listening on " + active.listenHost + ":" + active.listenPort + " (" + active.peers.size() + " peer" + (active.peers.size() == 1 ? "" : "s") + ")");
    }

    public void stop() {
        if (!running.compareAndSet(true, false)) {
            return;
        }
        ScheduledExecutorService executor = scheduler;
        scheduler = null;
        if (executor != null) {
            executor.shutdownNow();
        }
        ServerSocket socket = listenSocket;
        listenSocket = null;
        if (socket != null) {
            try {
                socket.close();
            } catch (IOException ignored) {
            }
        }
        for (PeerConnection connection : pending) {
            connection.close("shutdown");
        }
        for (PeerConnection connection : readyPeers.values()) {
            connection.close("shutdown");
        }
        pending.clear();
        readyPeers.clear();
        acceptThread = null;
    }

    public void applyConfig(NetworkConfig next) {
        NetworkConfig previous = config;
        config = next;
        boolean restartNeeded = previous.enabled != next.enabled
            || previous.listenPort != next.listenPort
            || !previous.listenHost.equals(next.listenHost)
            || !previous.advertiseHost.equals(next.advertiseHost)
            || !previous.sharedSecret.equals(next.sharedSecret);
        if (restartNeeded) {
            stop();
            start();
            return;
        }
        for (Map.Entry<String, PeerConnection> entry : readyPeers.entrySet()) {
            if (findPeer(next, entry.getKey()) == null && !learnedPeers.containsKey(entry.getKey())) {
                entry.getValue().close("peer removed from config");
            }
        }
    }

    public boolean isRunning() {
        return running.get();
    }

    public boolean isPeerReady(String name) {
        PeerConnection connection = readyPeers.get(name);
        return connection != null && connection.getState() == PeerConnection.State.READY;
    }

    public boolean send(String peerName, WireMessage message) {
        PeerConnection connection = readyPeers.get(peerName);
        return connection != null && connection.send(message);
    }

    public NetworkConfig.PeerEntry getPeer(String name) {
        NetworkConfig.PeerEntry configured = findPeer(config, name);
        return configured != null ? configured : learnedPeers.get(name);
    }

    public List<PeerStatus> status() {
        NetworkConfig active = config;
        List<PeerStatus> statuses = new ArrayList<>(active.peers.size());
        long now = System.currentTimeMillis();
        for (NetworkConfig.PeerEntry peer : active.peers) {
            PeerConnection connection = readyPeers.get(peer.name);
            if (connection != null && connection.getState() == PeerConnection.State.READY) {
                statuses.add(new PeerStatus(peer.name, connection.describeRemote(), "CONNECTED", connection.isDialer(), connection.getRttMillis(), now - connection.getLastInboundMillis(), null));
            } else {
                statuses.add(new PeerStatus(peer.name, peer.host + ":" + peer.port, running.get() ? "CONNECTING" : "OFFLINE", false, -1L, -1L, lastDialError.get(peer.name)));
            }
        }
        for (Map.Entry<String, PeerConnection> entry : readyPeers.entrySet()) {
            if (findPeer(active, entry.getKey()) != null) {
                continue;
            }
            PeerConnection connection = entry.getValue();
            if (connection.getState() == PeerConnection.State.READY) {
                statuses.add(new PeerStatus(entry.getKey(), connection.describeRemote(), "CONNECTED", connection.isDialer(), connection.getRttMillis(), now - connection.getLastInboundMillis(), null));
            }
        }
        return statuses;
    }

    @Override
    public boolean approvePeer(PeerConnection connection, String peerName, String peerMcVersion, String peerPluginVersion) {
        NetworkConfig active = config;
        if (peerName == null || peerName.isBlank() || peerName.equals(getLocalName())) {
            return false;
        }
        if (findPeer(active, peerName) == null) {
            logger.info("net: accepting unconfigured peer " + peerName + " (authenticated by shared secret)");
        }
        return true;
    }

    @Override
    public void onReady(PeerConnection connection) {
        pending.remove(connection);
        String name = connection.getPeerName();
        dialFailures.remove(name);
        nextDialAttempt.remove(name);
        lastDialError.remove(name);
        learnPeer(connection);

        PeerConnection displaced = null;
        synchronized (readyPeers) {
            PeerConnection previous = readyPeers.get(name);
            if (previous != null && previous != connection && previous.getState() == PeerConnection.State.READY) {
                if (initiatorName(previous).compareTo(initiatorName(connection)) <= 0) {
                    connection.close("duplicate connection (kept " + initiatorName(previous) + "-initiated)");
                    return;
                }
                displaced = previous;
            }
            readyPeers.put(name, connection);
        }
        if (displaced != null) {
            displaced.close("duplicate connection (kept " + initiatorName(connection) + "-initiated)");
        }

        logger.info("net: peer " + name + " connected (" + (connection.isDialer() ? "dialed" : "accepted") + " " + connection.describeRemote() + ")");
        BiConsumer<String, Boolean> sink = peerStateSink;
        if (sink != null) {
            sink.accept(name, true);
        }
    }

    private String initiatorName(PeerConnection connection) {
        return connection.isDialer() ? getLocalName() : connection.getPeerName();
    }

    private void learnPeer(PeerConnection connection) {
        String name = connection.getPeerName();
        if (connection.isDialer() || findPeer(config, name) != null) {
            return;
        }
        String host = connection.getPeerAdvertiseHost();
        if (host == null || connection.getPeerWormholePort() <= 0) {
            return;
        }
        NetworkConfig.PeerEntry learned = new NetworkConfig.PeerEntry();
        learned.name = name;
        learned.host = host;
        learned.port = connection.getPeerWormholePort();
        learned.publicHost = host;
        learned.publicPort = connection.getPeerGamePort() > 0 ? connection.getPeerGamePort() : 25565;
        learnedPeers.put(name, learned);
    }

    @Override
    public void onMessage(PeerConnection connection, WireMessage message) {
        BiConsumer<String, WireMessage> sink = messageSink;
        if (sink != null) {
            sink.accept(connection.getPeerName(), message);
        }
    }

    @Override
    public void onClosed(PeerConnection connection, String reason) {
        pending.remove(connection);
        String name = connection.getPeerName();
        boolean wasReady = name != null && readyPeers.remove(name, connection);
        if (wasReady) {
            logger.info("net: peer " + name + " disconnected: " + reason);
            BiConsumer<String, Boolean> sink = peerStateSink;
            if (sink != null) {
                sink.accept(name, false);
            }
        }
        if (name != null && connection.isDialer()) {
            registerDialFailure(name);
            if (!wasReady && reason != null && !reason.startsWith("duplicate connection") && !"shutdown".equals(reason)) {
                lastDialError.put(name, connection.describeRemote() + " - " + reason);
            }
        }
    }

    private void runAcceptLoop() {
        ServerSocket socket = listenSocket;
        while (running.get() && socket != null && !socket.isClosed()) {
            try {
                Socket client = socket.accept();
                PeerConnection connection = new PeerConnection(client, false, identity(config), null, this);
                pending.add(connection);
                connection.start();
            } catch (IOException e) {
                if (running.get()) {
                    logger.warning("net: accept failed: " + e.getMessage());
                }
            }
        }
    }

    private void scanDials() {
        NetworkConfig active = config;
        long now = System.currentTimeMillis();
        for (NetworkConfig.PeerEntry peer : active.peers) {
            if (peer.name.equals(getLocalName())) {
                continue;
            }
            if (isPeerReady(peer.name) || hasPendingDial(peer.name)) {
                continue;
            }
            long notBefore = nextDialAttempt.getOrDefault(peer.name, 0L);
            if (now < notBefore) {
                continue;
            }
            dial(active, peer);
        }
    }

    private void dial(NetworkConfig active, NetworkConfig.PeerEntry peer) {
        List<String> candidates = dialCandidates(peer);
        int index = dialCandidateIndex.getOrDefault(peer.name, 0) % candidates.size();
        String host = candidates.get(index);
        try {
            Socket socket = new Socket();
            socket.connect(new InetSocketAddress(host, peer.port), CONNECT_TIMEOUT_MS);
            PeerConnection connection = new PeerConnection(socket, true, identity(active), peer.name, this);
            pending.add(connection);
            connection.start();
        } catch (IOException e) {
            lastDialError.put(peer.name, host + ":" + peer.port + " - " + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()));
            dialCandidateIndex.put(peer.name, index + 1);
            registerDialFailure(peer.name);
        }
    }

    private static List<String> dialCandidates(NetworkConfig.PeerEntry peer) {
        List<String> candidates = new ArrayList<>(3);
        candidates.add(peer.host);
        if (peer.fallbackHosts != null && !peer.fallbackHosts.isBlank()) {
            for (String host : peer.fallbackHosts.split("\\s*,\\s*")) {
                if (!host.isBlank() && !candidates.contains(host)) {
                    candidates.add(host);
                }
            }
        }
        return candidates;
    }

    private void keepalive() {
        long now = System.currentTimeMillis();
        for (PeerConnection connection : readyPeers.values()) {
            connection.send(new WireMessage.Ping(now));
        }
    }

    private boolean hasPendingDial(String peerName) {
        for (PeerConnection connection : pending) {
            if (connection.isDialer() && peerName.equals(connection.getPeerName())) {
                return true;
            }
        }
        return false;
    }

    private void registerDialFailure(String peerName) {
        int failures = dialFailures.merge(peerName, 1, Integer::sum);
        long backoff = Math.min(BACKOFF_MAX_MS, BACKOFF_BASE_MS << Math.min(10, failures - 1));
        nextDialAttempt.put(peerName, System.currentTimeMillis() + backoff);
    }

    private static NetworkConfig.PeerEntry findPeer(NetworkConfig active, String name) {
        for (NetworkConfig.PeerEntry peer : active.peers) {
            if (peer.name.equals(name)) {
                return peer;
            }
        }
        return null;
    }
}
