package art.arcane.wormholes.network;

import art.arcane.wormholes.config.toml.NetworkConfig;
import art.arcane.wormholes.network.replication.ChunkReplicationManager;
import art.arcane.wormholes.network.replication.HashProbeScheduler;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.net.SocketTimeoutException;
import java.net.UnixDomainSocketAddress;
import java.net.UnknownHostException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiConsumer;
import java.util.logging.Level;
import java.util.logging.Logger;

public class NetworkManager implements PeerConnection.Listener, PeerConnection.CompressionProvider {
    public record PeerStatus(String name, String address, String state, boolean dialer, long rttMillis, long lastInboundAgeMillis, String lastError) {
    }

    public record PeerSnapshot(String name, String transport, String remoteAddress, String compressionMode,
                               int dictVersion, String dictHashHex, long lastInboundAgeMillis, long rttMillis,
                               boolean handshakeComplete, boolean disconnected) {
    }

    private static final long DIAL_SCAN_INTERVAL_MS = 2_000L;
    private static final long KEEPALIVE_INTERVAL_MS = 5_000L;
    private static final long BACKOFF_BASE_MS = 1_000L;
    private static final long BACKOFF_MAX_MS = 30_000L;
    private static final int CONNECT_TIMEOUT_MS = 5_000;
    private static final int ROUTE_TTL = 8;
    private static final int LISTEN_PORT_FALLBACK_RANGE = 50;
    private static final long STATUS_BRIDGE_INTERVAL_MS = 600L;
    private static final long STATUS_BRIDGE_FAST_INTERVAL_MS = 75L;
    private static final long STATUS_BRIDGE_FAIL_BACKOFF_MS = 5_000L;
    private static final long STATUS_BRIDGE_READY_TTL_MS = 12_000L;
    private static final long STATUS_FRAGMENT_TTL_MS = 15L * 60_000L;
    private static final int STATUS_FRAGMENT_ASSEMBLY_CAPACITY = 128;
    private static final int STATUS_BRIDGE_QUEUE_CAPACITY = 1024;
    private static final int STATUS_BRIDGE_BESTEFFORT_SHED_THRESHOLD = STATUS_BRIDGE_QUEUE_CAPACITY / 2;
    private static final int STATUS_BRIDGE_FRAME_BUDGET_BYTES = 20_000;
    static final int STATUS_BRIDGE_REQUEST_BUDGET_MIN_BYTES = 4_000;
    static final int STATUS_BRIDGE_REQUEST_BUDGET_MAX_BYTES = 20_000;
    private static final int STATUS_BRIDGE_MAX_CONTINUATIONS = 32;
    private static final long STATUS_FRAGMENT_EXPIRY_GATE_MS = 1_000L;
    private static final int STATUS_FRAGMENT_MAX_FRAME_BYTES = WireCodec.MAX_FRAME_BYTES + Integer.BYTES;
    private static final int STATUS_FRAGMENT_CHUNK_BYTES = 4 * 1024;
    private static final int STATUS_FRAGMENT_MAX_COUNT = (STATUS_FRAGMENT_MAX_FRAME_BYTES / STATUS_FRAGMENT_CHUNK_BYTES) + 2;
    private static final int RETRAIN_HOLDOUT_STRIDE = 16;
    private static final int RETRAIN_HOLDOUT_CAP_BYTES = 512 * 1024;
    private static final double RETRAIN_ADOPT_RATIO = 0.97D;
    private static final Set<WireMessageType> SAMPLE_EXCLUDED_TYPES = EnumSet.of(
        WireMessageType.HELLO, WireMessageType.CHALLENGE, WireMessageType.AUTH, WireMessageType.READY,
        WireMessageType.PING, WireMessageType.PONG,
        WireMessageType.DICT_OFFER, WireMessageType.DICT_REQUEST, WireMessageType.DICT_DATA);

    private final Logger logger;
    private final String mcVersion;
    private final String pluginVersion;
    private final int gamePort;
    private final Path dataDirectory;
    private final IdentityStore identityStore;
    private final PeerTrustStore trustStore;
    private final PeerRouteStore routeStore;
    final MinecraftStatusBridge statusBridge;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final Map<String, PeerConnection> readyPeers = new ConcurrentHashMap<>();
    private final Set<PeerConnection> pending = ConcurrentHashMap.newKeySet();
    private final Map<String, Long> nextDialAttempt = new ConcurrentHashMap<>();
    private final Map<String, Integer> dialFailures = new ConcurrentHashMap<>();
    private final Map<String, NetworkConfig.PeerEntry> learnedPeers = new ConcurrentHashMap<>();
    private final Map<String, Integer> dialCandidateIndex = new ConcurrentHashMap<>();
    private final Map<String, String> lastDialError = new ConcurrentHashMap<>();
    private final Map<String, BlockingQueue<MinecraftStatusBridge.EncodedMessage>> statusOutbox = new ConcurrentHashMap<>();
    private final Map<String, Long> statusLastSeen = new ConcurrentHashMap<>();
    private final Map<String, Long> statusRttMillis = new ConcurrentHashMap<>();
    final Map<String, Long> nextStatusAttempt = new ConcurrentHashMap<>();
    private final Map<String, Integer> statusRequestBudgets = new ConcurrentHashMap<>();
    private final AtomicLong statusFragmentIds = new AtomicLong();
    private final Set<String> statusOversizeWarnings = ConcurrentHashMap.newKeySet();
    private final Set<String> statusPollFailing = ConcurrentHashMap.newKeySet();
    final Set<String> statusPollInFlight = ConcurrentHashMap.newKeySet();
    private final Map<String, StatusFragmentAssembly> statusFragments = new ConcurrentHashMap<>();
    private final Map<String, String> routes = new ConcurrentHashMap<>();
    private final Map<String, List<PortalInfo>> relayedPortalDirectories = new ConcurrentHashMap<>();
    private final Map<String, DictionaryTransfer> inboundDictionaries = new ConcurrentHashMap<>();
    private final AtomicBoolean retrainInFlight = new AtomicBoolean();
    private final WireCompression wireCompression;
    private final DictionarySampleCollector sampleCollector;
    private final ChunkReplicationManager replicationManager;
    private final HashProbeScheduler hashProbeScheduler;

    private volatile NetworkConfig config;
    private volatile BiConsumer<String, WireMessage> messageSink;
    private volatile BiConsumer<String, Boolean> peerStateSink;
    private volatile TcpPeerTransport tcpTransport;
    private volatile UnixDomainPeerTransport udsTransport;
    private volatile ScheduledExecutorService scheduler;
    private volatile ExecutorService statusPollExecutor;
    private volatile String generatedName;
    private volatile long nextStatusFragmentExpiry;
    private volatile Thread tcpAcceptThread;
    private volatile Thread udsAcceptThread;
    private volatile String detectedLanHost;
    private volatile String detectedPublicHost;
    private volatile int boundListenPort;
    private final PublicHostResolver publicHostResolver;

    public NetworkManager(Logger logger, NetworkConfig config, String mcVersion, String pluginVersion, int gamePort) {
        this(logger, config, mcVersion, pluginVersion, gamePort, Path.of("plugins", "Wormholes"));
    }

    public NetworkManager(Logger logger, NetworkConfig config, String mcVersion, String pluginVersion, int gamePort, Path dataDirectory) {
        this.logger = logger;
        this.config = config;
        this.mcVersion = mcVersion;
        this.pluginVersion = pluginVersion;
        this.gamePort = gamePort;
        this.dataDirectory = dataDirectory;
        this.wireCompression = new WireCompression(config.transport.compressionLevel);
        this.sampleCollector = new DictionarySampleCollector(Math.max(64 * 1024, config.transport.compressionDictTrainBytes));
        try {
            this.identityStore = IdentityStore.loadOrCreate(dataDirectory);
            this.trustStore = PeerTrustStore.loadOrCreate(dataDirectory);
            this.routeStore = PeerRouteStore.loadOrCreate(dataDirectory);
            for (NetworkConfig.PeerEntry route : routeStore.all()) {
                learnedPeers.put(route.name, route);
            }
            this.statusBridge = new MinecraftStatusBridge(this);
        } catch (IOException e) {
            throw new IllegalStateException("Could not initialize Wormholes network identity", e);
        }
        this.replicationManager = new ChunkReplicationManager(this, new ChunkReplicationManager.ReplicationConfig(config.replication == null ? 4096L : config.replication.maxQueuedDiffsPerPeer));
        this.hashProbeScheduler = new HashProbeScheduler(this, replicationManager);
        if (config.replication != null) {
            this.hashProbeScheduler.configure(config.replication.hashProbeIntervalSec, config.replication.hashProbeChunksPerTick);
        }
        this.publicHostResolver = new PublicHostResolver(logger);
        this.boundListenPort = config.listenPort;
    }

    public ChunkReplicationManager getReplicationManager() {
        return replicationManager;
    }

    public HashProbeScheduler getHashProbeScheduler() {
        return hashProbeScheduler;
    }

    @Override
    public WireCompression compression() {
        return wireCompression;
    }

    @Override
    public boolean compressionEnabled() {
        return config.transport.compressionEnabled;
    }

    @Override
    public CompressionDictionary currentDictionary() {
        return wireCompression.currentDictionary();
    }

    @Override
    public void recordDictionarySample(WireMessageType type, byte[] payload) {
        if (!config.transport.compressionEnabled || SAMPLE_EXCLUDED_TYPES.contains(type) || sampleCollector.isFull()) {
            return;
        }
        sampleCollector.record(payload);
    }

    @Override
    public void onDictionaryAdvertised(PeerConnection connection, byte[] peerDictHash, int peerDictVersion) {
        if (peerDictHash == null || peerDictVersion <= 0 || isZeroHash(peerDictHash)) {
            return;
        }
        CompressionDictionary local = wireCompression.currentDictionary();
        if (local != null && local.version() == peerDictVersion && CompressionDictionary.sameHash(local.hash(), peerDictHash)) {
            return;
        }
        if (local != null && CompressionDictionary.sameHash(local.hash(), peerDictHash)) {
            return;
        }
        connection.send(new WireMessage.DictRequest(peerDictVersion));
    }

    @Override
    public void onDictionaryNegotiated(PeerConnection connection, int dictVersion) {
        logger.fine("net: dict v" + dictVersion + " negotiated with " + connection.getPeerName());
    }

    private LocalIdentity identity(NetworkConfig active) {
        return new LocalIdentity(getLocalName(), mcVersion, pluginVersion, getAdvertiseHost(), getBoundListenPort(), gamePort, identityStore.publicKeyBytes(), identityStore.privateKey());
    }

    public String getAdvertiseHost() {
        NetworkConfig active = config;
        if (active.advertiseHostOverride != null && !active.advertiseHostOverride.isBlank()) {
            return active.advertiseHostOverride;
        }
        String publicHost = detectedPublicHost;
        if (publicHost == null) {
            publicHost = publicHostResolver.cached();
        }
        if (publicHost != null && !publicHost.isBlank()) {
            return publicHost;
        }
        String lan = detectedLanHost;
        if (lan == null) {
            lan = LanAddressResolver.detectLanAddress();
            detectedLanHost = lan;
        }
        return lan;
    }

    public String getResolvedPublicHost() {
        String publicHost = detectedPublicHost;
        if (publicHost != null && !publicHost.isBlank()) {
            return publicHost;
        }
        return publicHostResolver.cached();
    }

    public int getBoundListenPort() {
        int bound = boundListenPort;
        if (bound > 0) {
            return bound;
        }
        return config.listenPort;
    }

    public String getLocalName() {
        NetworkConfig active = config;
        if (active.serverName != null && !active.serverName.isBlank()) {
            return active.serverName;
        }
        return generatedServerName();
    }

    public String getPublicKey() {
        return Handshake.encodePublicKey(identityStore.publicKeyBytes());
    }

    public String getPublicKeyFingerprint() {
        return Handshake.fingerprint(identityStore.publicKeyBytes());
    }

    public void setInferredAdvertiseHost(String host) {
        NetworkConfig active = config;
        if (host == null || host.isBlank() || (active.advertiseHostOverride != null && !active.advertiseHostOverride.isBlank())) {
            return;
        }
        if (PublicHostResolver.isValidHostLiteral(host)) {
            detectedPublicHost = host;
        }
    }

    public void trustPeer(String peerName, String publicKey) {
        byte[] decoded = Handshake.decodePublicKeyText(publicKey);
        if (decoded == null) {
            throw new IllegalArgumentException("Invalid public key for " + peerName);
        }
        trustStore.trustOrReplace(peerName, decoded);
    }

    public void savePeer(NetworkConfig.PeerEntry peer) {
        if (peer == null || peer.name == null || peer.name.isBlank()) {
            return;
        }
        routeStore.save(peer);
        NetworkConfig.PeerEntry stored = routeStore.get(peer.name);
        if (stored != null) {
            learnedPeers.put(stored.name, stored);
        }
        nextDialAttempt.remove(peer.name);
        dialFailures.remove(peer.name);
        lastDialError.remove(peer.name);
    }

    public String getListenAddress() {
        return "all interfaces:" + getBoundListenPort();
    }

    public void setMessageSink(BiConsumer<String, WireMessage> sink) {
        this.messageSink = sink;
    }

    public void setPeerStateSink(BiConsumer<String, Boolean> sink) {
        this.peerStateSink = sink;
    }

    public MinecraftStatusBridge statusBridge() {
        return statusBridge;
    }

    public void start() {
        NetworkConfig active = config;
        if (!active.enabled) {
            return;
        }
        if (!running.compareAndSet(false, true)) {
            return;
        }

        if (active.listenEnabled) {
            tcpTransport = bindWithFallback(active.listenPort);
            if (tcpTransport == null) {
                logger.warning("net: could not bind any raw Wormholes port in " + active.listenPort + ".." + (active.listenPort + LISTEN_PORT_FALLBACK_RANGE) + "; running sideband-only over the MC game port " + gamePort);
                boundListenPort = 0;
            }

            if (tcpTransport != null && tcpTransport.isListening()) {
                Thread accept = new Thread(this::runTcpAcceptLoop, "Wormholes-Net-Accept-Tcp");
                accept.setDaemon(true);
                tcpAcceptThread = accept;
                accept.start();
            }

            if (active.transport.udsEnabled) {
                try {
                    udsTransport = UnixDomainPeerTransport.bind(localUdsSocketPath(active));
                    Thread udsAccept = new Thread(this::runUdsAcceptLoop, "Wormholes-Net-Accept-Uds");
                    udsAccept.setDaemon(true);
                    udsAcceptThread = udsAccept;
                    udsAccept.start();
                } catch (IOException | UnsupportedOperationException e) {
                    logger.warning("net: failed to bind UDS listener - " + e.getMessage() + "; falling back to TCP for loopback peers");
                    udsTransport = null;
                }
            }
        }

        statusPollExecutor = Executors.newVirtualThreadPerTaskExecutor();
        ScheduledExecutorService executor = Executors.newScheduledThreadPool(3, runnable -> {
            Thread thread = new Thread(runnable, "Wormholes-Net-Timer");
            thread.setDaemon(true);
            return thread;
        });
        executor.scheduleWithFixedDelay(this::scanDials, 250L, DIAL_SCAN_INTERVAL_MS, TimeUnit.MILLISECONDS);
        executor.scheduleWithFixedDelay(this::pollStatusBridges, 300L, STATUS_BRIDGE_FAST_INTERVAL_MS, TimeUnit.MILLISECONDS);
        executor.scheduleWithFixedDelay(this::keepalive, KEEPALIVE_INTERVAL_MS, KEEPALIVE_INTERVAL_MS, TimeUnit.MILLISECONDS);
        long retrainSec = Math.max(30L, active.transport.compressionRetrainIntervalSec);
        executor.scheduleWithFixedDelay(this::maybeRetrainDictionary, retrainSec, retrainSec, TimeUnit.SECONDS);
        scheduler = executor;

        loadPersistedDictionary(active);
        hashProbeScheduler.start();
        kickOffPublicHostResolution();

        int peerCount = knownPeers().size();
        if (active.listenEnabled && tcpTransport != null && tcpTransport.isListening()) {
            logger.info("net: " + getLocalName() + " listening on " + getListenAddress() + " (" + peerCount + " peer" + (peerCount == 1 ? "" : "s") + ")");
        } else if (active.listenEnabled) {
            logger.info("net: " + getLocalName() + " running sideband-only over game port " + gamePort + " (" + peerCount + " peer" + (peerCount == 1 ? "" : "s") + ")");
        } else {
            logger.info("net: " + getLocalName() + " running outbound-only (" + peerCount + " peer" + (peerCount == 1 ? "" : "s") + ")");
        }
    }

    private TcpPeerTransport bindWithFallback(int preferredPort) {
        int upper = preferredPort + LISTEN_PORT_FALLBACK_RANGE;
        java.net.BindException firstFailure = null;
        for (int port = preferredPort; port <= upper; port++) {
            try {
                TcpPeerTransport transport = TcpPeerTransport.bind("0.0.0.0", port);
                boundListenPort = port;
                if (port != preferredPort) {
                    logger.info("net: raw Wormholes listen-port " + preferredPort + " was busy; bound " + port + " instead");
                }
                return transport;
            } catch (java.net.BindException be) {
                if (firstFailure == null) {
                    firstFailure = be;
                }
            } catch (IOException e) {
                logger.warning("net: bind " + port + " failed: " + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()));
                return null;
            }
        }
        return null;
    }

    private void kickOffPublicHostResolution() {
        NetworkConfig active = config;
        if (active.advertiseHostOverride != null && !active.advertiseHostOverride.isBlank()) {
            return;
        }
        publicHostResolver.refreshAsync(resolved -> {
            if (resolved != null && !resolved.isBlank()) {
                detectedPublicHost = resolved;
            }
        });
    }

    public void stop() {
        if (!running.compareAndSet(true, false)) {
            return;
        }
        hashProbeScheduler.stop();
        ScheduledExecutorService executor = scheduler;
        scheduler = null;
        if (executor != null) {
            executor.shutdownNow();
        }
        ExecutorService pollExecutor = statusPollExecutor;
        statusPollExecutor = null;
        if (pollExecutor != null) {
            pollExecutor.shutdownNow();
        }
        TcpPeerTransport tcp = tcpTransport;
        tcpTransport = null;
        if (tcp != null) {
            try {
                tcp.close();
            } catch (IOException ignored) {
            }
        }
        UnixDomainPeerTransport uds = udsTransport;
        udsTransport = null;
        if (uds != null) {
            try {
                uds.close();
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
        statusLastSeen.clear();
        statusRttMillis.clear();
        nextStatusAttempt.clear();
        statusOutbox.clear();
        statusFragments.clear();
        statusRequestBudgets.clear();
        statusPollInFlight.clear();
        inboundDictionaries.clear();
        tcpAcceptThread = null;
        udsAcceptThread = null;
        boundListenPort = 0;
    }

    public void applyConfig(NetworkConfig next) {
        NetworkConfig previous = config;
        config = next;
        if (next.replication != null) {
            replicationManager.applyConfig(new ChunkReplicationManager.ReplicationConfig(next.replication.maxQueuedDiffsPerPeer));
            hashProbeScheduler.configure(next.replication.hashProbeIntervalSec, next.replication.hashProbeChunksPerTick);
        }
        boolean overrideChanged = !blank(previous.advertiseHostOverride).equals(blank(next.advertiseHostOverride));
        boolean restartNeeded = previous.enabled != next.enabled
            || previous.listenEnabled != next.listenEnabled
            || previous.listenPort != next.listenPort
            || overrideChanged
            || !blank(previous.serverName).equals(blank(next.serverName));
        if (overrideChanged) {
            detectedPublicHost = null;
        }
        if (restartNeeded) {
            stop();
            start();
            return;
        }
        for (Map.Entry<String, PeerConnection> entry : readyPeers.entrySet()) {
            if (findKnownPeer(entry.getKey()) == null) {
                entry.getValue().close("peer removed from config");
            }
        }
    }

    public boolean isRunning() {
        return running.get();
    }

    public boolean isPeerReady(String name) {
        return isRawPeerReady(name) || isStatusPeerReady(name);
    }

    public boolean isSidebandOnlyPeer(String name) {
        return !isRawPeerReady(name) && isStatusPeerReady(name);
    }

    private boolean isRawPeerReady(String name) {
        PeerConnection connection = readyPeers.get(name);
        return connection != null && connection.getState() == PeerConnection.State.READY;
    }

    public void sendToPeers(Collection<String> peerNames, WireMessage message) {
        OutboundFrame frame = new OutboundFrame(message);
        for (String name : peerNames) {
            PeerConnection connection = readyPeers.get(name);
            if (connection != null && connection.getState() == PeerConnection.State.READY) {
                connection.send(frame);
            } else {
                send(name, message);
            }
        }
    }

    public boolean send(String peerName, WireMessage message) {
        PeerConnection connection = readyPeers.get(peerName);
        if (connection != null) {
            return connection.send(message);
        }
        OutboundFrame frame = new OutboundFrame(message);
        recordWireSample(frame);
        NetworkConfig.PeerEntry peer = findKnownPeer(peerName);
        if (canQueueStatusBridge(peer) && enqueueStatusMessage(peerName, frame)) {
            if (isLatencyCriticalStatusMessage(message)) {
                nudgeStatusPoll(peerName);
            }
            return true;
        }
        String nextHop = routes.get(peerName);
        if (nextHop == null || nextHop.equals(peerName) || nextHop.equals(getLocalName())) {
            return false;
        }
        PeerConnection route = readyPeers.get(nextHop);
        if (route != null) {
            return sendRouted(route, getLocalName(), peerName, ROUTE_TTL, message);
        }
        NetworkConfig.PeerEntry routedPeer = findKnownPeer(nextHop);
        return routedPeer != null && canQueueStatusBridge(routedPeer) && enqueueRoutedStatusMessage(nextHop, getLocalName(), peerName, ROUTE_TTL, message);
    }

    public NetworkConfig.PeerEntry getPeer(String name) {
        return learnedPeers.get(name);
    }

    public List<PeerStatus> status() {
        List<NetworkConfig.PeerEntry> peers = knownPeers();
        List<PeerStatus> statuses = new ArrayList<>(peers.size() + readyPeers.size());
        long now = System.currentTimeMillis();
        for (NetworkConfig.PeerEntry peer : peers) {
            PeerConnection connection = readyPeers.get(peer.name);
            if (connection != null && connection.getState() == PeerConnection.State.READY) {
                statuses.add(new PeerStatus(peer.name, connection.describeRemote(), "CONNECTED", connection.isDialer(), connection.getRttMillis(), now - connection.getLastInboundMillis(), null));
            } else if (isStatusPeerReady(peer.name)) {
                statuses.add(new PeerStatus(peer.name, statusBridgeAddress(peer), "CONNECTED", canUseStatusBridge(peer), statusRttMillis.getOrDefault(peer.name, -1L), now - statusLastSeen.getOrDefault(peer.name, now), null));
            } else if (!isDialable(peer)) {
                statuses.add(new PeerStatus(peer.name, peerAddress(peer), running.get() ? "WAITING" : "OFFLINE", false, -1L, -1L, "no dial address; waiting for the peer to reach this server"));
            } else {
                statuses.add(new PeerStatus(peer.name, peerAddress(peer), running.get() ? "CONNECTING" : "OFFLINE", false, -1L, -1L, lastDialError.get(peer.name)));
            }
        }
        for (Map.Entry<String, PeerConnection> entry : readyPeers.entrySet()) {
            if (findKnownPeer(entry.getKey()) != null) {
                continue;
            }
            PeerConnection connection = entry.getValue();
            if (connection.getState() == PeerConnection.State.READY) {
                statuses.add(new PeerStatus(entry.getKey(), connection.describeRemote(), "CONNECTED", connection.isDialer(), connection.getRttMillis(), now - connection.getLastInboundMillis(), null));
            }
        }
        return statuses;
    }

    public List<PeerSnapshot> peerSnapshots() {
        long now = System.currentTimeMillis();
        List<PeerSnapshot> result = new ArrayList<>(readyPeers.size() + statusLastSeen.size());
        for (PeerConnection connection : readyPeers.values()) {
            String name = connection.getPeerName();
            if (name == null) {
                continue;
            }
            boolean connected = connection.getState() == PeerConnection.State.READY;
            boolean disconnected = connection.getState() == PeerConnection.State.CLOSED;
            String remote = connection.describeRemote();
            String transport = remote != null && remote.startsWith("uds:") ? "UDS" : "TCP";
            String mode = compressionModeFor(connection);
            int dictVersion = connection.getNegotiatedDictVersion();
            String dictHashHex = compressionDictHashHex(connection);
            result.add(new PeerSnapshot(
                name,
                transport,
                remote,
                mode,
                dictVersion,
                dictHashHex,
                connected ? Math.max(0L, now - connection.getLastInboundMillis()) : -1L,
                connected ? connection.getRttMillis() : -1L,
                connected,
                disconnected
            ));
        }
        for (Map.Entry<String, Long> entry : statusLastSeen.entrySet()) {
            String name = entry.getKey();
            if (readyPeers.containsKey(name)) {
                continue;
            }
            NetworkConfig.PeerEntry peer = learnedPeers.get(name);
            String remote = peer == null ? "game-port sideband" : statusBridgeAddress(peer);
            long rtt = statusRttMillis.getOrDefault(name, -1L);
            result.add(new PeerSnapshot(
                name,
                "SIDEBAND",
                remote,
                "none",
                0,
                "-",
                Math.max(0L, now - entry.getValue()),
                rtt,
                true,
                false
            ));
        }
        for (NetworkConfig.PeerEntry peer : learnedPeers.values()) {
            if (peer.name == null || peer.name.isBlank()) {
                continue;
            }
            if (readyPeers.containsKey(peer.name) || statusLastSeen.containsKey(peer.name)) {
                continue;
            }
            result.add(new PeerSnapshot(
                peer.name,
                "TCP",
                peerAddress(peer),
                "pending",
                0,
                "-",
                -1L,
                -1L,
                false,
                false
            ));
        }
        result.sort((a, b) -> a.name().compareTo(b.name()));
        return result;
    }

    public WireCompression wireCompressionMetrics() {
        return wireCompression;
    }

    public DictionarySampleCollector dictionarySampleCollector() {
        return sampleCollector;
    }

    private static String compressionModeFor(PeerConnection connection) {
        if (connection.getState() != PeerConnection.State.READY) {
            return "pending";
        }
        if (!connection.isPeerCompressionSupported()) {
            return "none";
        }
        if (connection.isDictionaryNegotiated()) {
            return "dict";
        }
        return "dictless";
    }

    private String compressionDictHashHex(PeerConnection connection) {
        if (!connection.isDictionaryNegotiated()) {
            return "-";
        }
        CompressionDictionary local = wireCompression.currentDictionary();
        if (local == null) {
            return "-";
        }
        return local.hashHex8();
    }

    public List<String> diagnostics() {
        NetworkConfig active = config;
        List<String> messages = new ArrayList<>();
        if (!active.enabled) {
            messages.add("Networking is disabled.");
            return messages;
        }
        int bound = boundListenPort;
        if (active.listenEnabled) {
            if (bound <= 0) {
                messages.add("Raw Wormholes listener is unbound; running sideband-only over the MC game port " + gamePort + ". Open any port in " + active.listenPort + ".." + (active.listenPort + LISTEN_PORT_FALLBACK_RANGE) + " to enable high-throughput projection streaming.");
            } else if (bound == gamePort) {
                messages.add("Wormholes listen-port resolved to the Minecraft game port " + gamePort + "; the raw listener cannot bind the existing game socket. Peers use the signed status sideband instead.");
            } else {
                messages.add("Raw Wormholes peers dial " + bound + ". If that port is not reachable, imported peers with public-host/public-port can still exchange small signed control frames over the Minecraft game port " + gamePort + "; open the raw port for high-throughput projection streaming.");
            }
        } else {
            messages.add("This server is outbound-only. It will not accept inbound raw Wormholes sockets; it relies on dialed peers or the game-port status sideband.");
        }
        for (NetworkConfig.PeerEntry peer : knownPeers()) {
            if (peer.name == null || peer.name.isBlank() || isPeerReady(peer.name)) {
                continue;
            }
            String publicAddress = peer.publicHost == null || peer.publicHost.isBlank() ? "no player address" : peer.publicHost + ":" + peer.publicPort;
            messages.add("Peer " + peer.name + " is dialed at raw address " + peerAddress(peer) + "; its player join address " + publicAddress + " is used as a signed status sideband when the raw port is unreachable.");
        }
        return messages;
    }

    public MinecraftStatusBridge.StatusPacket handleStatusBridgeRequest(MinecraftStatusBridge.StatusPacket request) {
        String sourceServer = request.sourceServer();
        if (acceptStatusBridgePacket(request, null)) {
            markStatusBridgeReady(sourceServer, -1L);
            receiveStatusBridgeMessages(sourceServer, request.messages());
            return createStatusBridgePacket(sourceServer, drainStatusOutbox(sourceServer, STATUS_BRIDGE_FRAME_BUDGET_BYTES));
        }
        return null;
    }

    void logStatusBridgeFailure(String message, Throwable throwable) {
        logger.log(Level.WARNING, "net: " + message, throwable);
    }

    boolean handleStatusBridgeResponse(String expectedPeerName, MinecraftStatusBridge.StatusPacket response, long rttMillis) {
        if (!acceptStatusBridgePacket(response, expectedPeerName)) {
            return false;
        }
        markStatusBridgeReady(response.sourceServer(), rttMillis);
        receiveStatusBridgeMessages(response.sourceServer(), response.messages());
        return true;
    }

    MinecraftStatusBridge.StatusPacket createStatusBridgePacket(String targetServer, List<MinecraftStatusBridge.EncodedMessage> messages) {
        return MinecraftStatusBridge.create(getLocalName(), targetServer, mcVersion, pluginVersion, getAdvertiseHost(), gamePort, identityStore.publicKeyBytes(), identityStore.privateKey(), messages);
    }

    @Override
    public boolean approvePeer(PeerConnection connection, String peerName, String peerMcVersion, String peerPluginVersion, byte[] publicKey) {
        NetworkConfig active = config;
        if (peerName == null || peerName.isBlank() || peerName.equals(getLocalName())) {
            return false;
        }
        byte[] trustedKey = trustStore.get(peerName);
        if (trustedKey != null) {
            if (Handshake.sameKey(trustedKey, publicKey)) {
                return true;
            }
            logger.warning("net: rejecting peer " + peerName + " because its public key changed");
            return false;
        }
        NetworkConfig.PeerEntry known = findKnownPeer(peerName);
        if (known == null && !active.trustOnFirstUse) {
            return false;
        }
        if (trustStore.trust(peerName, publicKey)) {
            logger.info("net: trusted peer " + peerName + " public key " + Handshake.fingerprint(publicKey));
            return true;
        }
        logger.warning("net: rejecting peer " + peerName + " because its public key did not match stored trust");
        return false;
    }

    private boolean acceptStatusBridgePacket(MinecraftStatusBridge.StatusPacket packet, String expectedSource) {
        if (!running.get() || !config.enabled) {
            return false;
        }
        String sourceServer = packet.sourceServer();
        if (sourceServer == null || sourceServer.isBlank() || sourceServer.equals(getLocalName())) {
            return false;
        }
        if (expectedSource != null && !expectedSource.equals(sourceServer)) {
            return false;
        }
        String targetServer = packet.targetServer();
        if (targetServer != null && !targetServer.isBlank() && !targetServer.equals(getLocalName())) {
            return false;
        }
        if (!packet.verify()) {
            logger.warning("net: rejecting status sideband from " + sourceServer + " because authentication failed");
            return false;
        }
        if (!approveStatusPeer(sourceServer, packet.publicKey())) {
            return false;
        }
        learnStatusPeer(packet);
        return true;
    }

    private boolean approveStatusPeer(String peerName, byte[] publicKey) {
        NetworkConfig active = config;
        byte[] trustedKey = trustStore.get(peerName);
        if (trustedKey != null) {
            if (Handshake.sameKey(trustedKey, publicKey)) {
                return true;
            }
            logger.warning("net: rejecting status sideband peer " + peerName + " because its public key changed");
            return false;
        }
        NetworkConfig.PeerEntry known = findKnownPeer(peerName);
        if (known == null && !active.trustOnFirstUse) {
            return false;
        }
        if (trustStore.trust(peerName, publicKey)) {
            logger.info("net: trusted peer " + peerName + " public key " + Handshake.fingerprint(publicKey));
            return true;
        }
        logger.warning("net: rejecting status sideband peer " + peerName + " because its public key did not match stored trust");
        return false;
    }

    private void learnStatusPeer(MinecraftStatusBridge.StatusPacket packet) {
        String sourceServer = packet.sourceServer();
        String replyHost = packet.replyHost();
        if (replyHost == null || replyHost.isBlank()) {
            return;
        }
        int replyPort = packet.replyPort() > 0 ? packet.replyPort() : 25565;
        NetworkConfig.PeerEntry known = findKnownPeer(sourceServer);
        if (known != null) {
            boolean changed = !replyHost.equals(known.publicHost) || replyPort != known.publicPort;
            if (changed && shouldAdoptAdvertisedHost(known.publicHost, replyHost)) {
                String previous = known.publicHost + ":" + known.publicPort;
                known.publicHost = replyHost;
                known.publicPort = replyPort;
                if (known.host == null || known.host.isBlank() || !isRoutableHost(known.host)) {
                    known.host = replyHost;
                }
                savePeer(known);
                logger.info("net: peer " + sourceServer + " advertised game-port address " + replyHost + ":" + replyPort + " (was " + previous + "); updated from signed status handshake");
            }
            return;
        }
        NetworkConfig.PeerEntry learned = new NetworkConfig.PeerEntry();
        learned.name = sourceServer;
        learned.host = replyHost;
        learned.port = config.listenPort;
        learned.publicHost = replyHost;
        learned.publicPort = replyPort;
        savePeer(learned);
    }

    private static boolean shouldAdoptAdvertisedHost(String current, String advertised) {
        if (current == null || current.isBlank()) {
            return true;
        }
        boolean advertisedRoutable = isRoutableHost(advertised);
        boolean currentRoutable = isRoutableHost(current);
        if (advertisedRoutable && !currentRoutable) {
            return true;
        }
        if (!advertisedRoutable && currentRoutable) {
            return false;
        }
        return true;
    }

    private static boolean isRoutableHost(String host) {
        if (host == null || host.isBlank()) {
            return false;
        }
        try {
            InetAddress address = InetAddress.getByName(host);
            return !address.isLoopbackAddress() && !address.isAnyLocalAddress()
                && !address.isLinkLocalAddress() && !address.isSiteLocalAddress();
        } catch (UnknownHostException e) {
            return true;
        }
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
        sendRelayedDirectoriesTo(name);
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
        if (name == null || name.isBlank()) {
            return;
        }
        NetworkConfig.PeerEntry known = findKnownPeer(name);
        if (known == null) {
            if (connection.isDialer()) {
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
            savePeer(learned);
            return;
        }
        if (autoPopulatePeer(known, connection)) {
            savePeer(known);
        }
    }

    private boolean autoPopulatePeer(NetworkConfig.PeerEntry peer, PeerConnection connection) {
        boolean changed = false;
        String advertised = connection.getPeerAdvertiseHost();
        int peerWirePort = connection.getPeerWormholePort();
        int peerGamePort = connection.getPeerGamePort();
        if ((peer.publicHost == null || peer.publicHost.isBlank()) && advertised != null && !advertised.isBlank()) {
            peer.publicHost = advertised;
            changed = true;
        }
        if (peerGamePort > 0 && peerGamePort != peer.publicPort) {
            peer.publicPort = peerGamePort;
            changed = true;
        }
        if ((peer.host == null || peer.host.isBlank()) && advertised != null && !advertised.isBlank()) {
            peer.host = advertised;
            changed = true;
        }
        if (peer.port <= 0 && peerWirePort > 0) {
            peer.port = peerWirePort;
            changed = true;
        }
        return changed;
    }

    @Override
    public void onMessage(PeerConnection connection, WireMessage message) {
        if (message instanceof WireMessage.DictOffer offer) {
            handleDictOffer(connection, offer);
            return;
        }
        if (message instanceof WireMessage.DictRequest request) {
            handleDictRequest(connection, request);
            return;
        }
        if (message instanceof WireMessage.DictData data) {
            handleDictData(connection, data);
            return;
        }
        if (message instanceof WireMessage.Routed routed) {
            handleRouted(connection.getPeerName(), routed);
            return;
        }
        cacheRelayAnnouncement(connection.getPeerName(), message);
        relayPortalAnnouncement(connection.getPeerName(), connection.getPeerName(), ROUTE_TTL, message);
        deliverMessage(connection.getPeerName(), message);
    }

    private void recordWireSample(OutboundFrame frame) {
        if (!config.transport.compressionEnabled || sampleCollector.isFull() || SAMPLE_EXCLUDED_TYPES.contains(frame.message().type())) {
            return;
        }
        try {
            sampleCollector.record(frame.payload());
        } catch (IOException ignored) {
        }
    }

    private void handleDictOffer(PeerConnection connection, WireMessage.DictOffer offer) {
        if (!config.transport.compressionEnabled || offer.version() <= 0) {
            return;
        }
        connection.updatePeerDictionary(offer.hash(), offer.version());
        CompressionDictionary local = wireCompression.currentDictionary();
        if (local != null && CompressionDictionary.sameHash(local.hash(), offer.hash())) {
            if (connection.getNegotiatedDictVersion() != local.version()) {
                connection.enableDictionary(local.version());
                onDictionaryNegotiated(connection, local.version());
            }
            return;
        }
        connection.send(new WireMessage.DictRequest(offer.version()));
    }

    private void handleDictRequest(PeerConnection connection, WireMessage.DictRequest request) {
        CompressionDictionary local = wireCompression.currentDictionary();
        if (local == null || local.version() != request.version()) {
            return;
        }
        byte[] bytes = local.bytes();
        int chunkSize = WireMessage.DictData.MAX_CHUNK_BYTES;
        int total = Math.max(1, (bytes.length + chunkSize - 1) / chunkSize);
        for (int index = 0; index < total; index++) {
            int offset = index * chunkSize;
            int length = Math.min(chunkSize, bytes.length - offset);
            byte[] chunk = new byte[length];
            System.arraycopy(bytes, offset, chunk, 0, length);
            connection.send(new WireMessage.DictData(local.version(), index, total, local.hash(), chunk));
        }
    }

    private void handleDictData(PeerConnection connection, WireMessage.DictData data) {
        if (data.version() <= 0 || data.chunkTotal() <= 0 || data.chunkIndex() < 0 || data.chunkIndex() >= data.chunkTotal()) {
            return;
        }
        String key = connection.getPeerName() + "@" + data.version();
        byte[] completedBytes = null;
        synchronized (inboundDictionaries) {
            DictionaryTransfer transfer = inboundDictionaries.get(key);
            if (transfer == null || transfer.total != data.chunkTotal() || transfer.version != data.version()) {
                transfer = new DictionaryTransfer(data.version(), data.chunkTotal(), data.hash());
                inboundDictionaries.put(key, transfer);
            }
            transfer.addChunk(data.chunkIndex(), data.chunk());
            if (transfer.isComplete()) {
                completedBytes = transfer.assemble();
                inboundDictionaries.remove(key);
            }
        }
        if (completedBytes == null) {
            return;
        }
        installInboundDictionary(connection, completedBytes, data.version(), data.hash());
    }

    private void installInboundDictionary(PeerConnection connection, byte[] dictBytes, int version, byte[] expectedHash) {
        try {
            CompressionDictionary candidate = CompressionDictionary.of(dictBytes);
            if (!CompressionDictionary.sameHash(candidate.hash(), expectedHash)) {
                logger.warning("net: dict v" + version + " from " + connection.getPeerName() + " failed hash check, ignoring");
                return;
            }
            demoteMismatchedPeers(candidate.version());
            wireCompression.installDictionary(candidate);
            try {
                candidate.save(dataDirectory.resolve("dict"));
            } catch (IOException e) {
                logger.warning("net: could not persist dict v" + version + ": " + e.getMessage());
            }
            connection.updatePeerDictionary(expectedHash, version);
            broadcastDictOffer(candidate);
            renegotiateDictionaryWithPeers(candidate);
        } catch (RuntimeException e) {
            logger.log(Level.WARNING, "net: failed to install inbound dictionary v" + version + " from " + connection.getPeerName(), e);
        }
    }

    private void demoteMismatchedPeers(int newVersion) {
        for (PeerConnection connection : readyPeers.values()) {
            if (connection.isDictionaryNegotiated() && connection.getNegotiatedDictVersion() != newVersion) {
                connection.disableDictionary();
            }
        }
    }

    private void renegotiateDictionaryWithPeers(CompressionDictionary local) {
        for (PeerConnection connection : readyPeers.values()) {
            byte[] peerHash = connection.getPeerDictHash();
            if (peerHash != null && CompressionDictionary.sameHash(local.hash(), peerHash)) {
                connection.enableDictionary(local.version());
            }
        }
    }

    private void broadcastDictOffer(CompressionDictionary local) {
        OutboundFrame offer = new OutboundFrame(new WireMessage.DictOffer(local.version(), local.hash(), local.bytes().length));
        for (PeerConnection connection : readyPeers.values()) {
            connection.send(offer);
        }
    }

    private void loadPersistedDictionary(NetworkConfig active) {
        if (!active.transport.compressionEnabled) {
            return;
        }
        Path dictDir = dataDirectory.resolve("dict");
        if (!Files.isDirectory(dictDir)) {
            return;
        }
        try (java.util.stream.Stream<Path> stream = Files.list(dictDir)) {
            Path latest = stream.filter(path -> path.getFileName().toString().endsWith(".zdict"))
                .reduce((a, b) -> a.toFile().lastModified() >= b.toFile().lastModified() ? a : b)
                .orElse(null);
            if (latest == null) {
                return;
            }
            CompressionDictionary loaded = CompressionDictionary.load(latest);
            wireCompression.installDictionary(loaded);
            logger.info("net: restored compression dict v" + loaded.version() + " from " + latest.getFileName());
        } catch (IOException e) {
            logger.warning("net: could not restore compression dict: " + e.getMessage());
        }
    }

    void maybeRetrainDictionary() {
        if (!config.transport.compressionEnabled || !sampleCollector.isFull()) {
            return;
        }
        if (!retrainInFlight.compareAndSet(false, true)) {
            return;
        }
        Thread trainer = new Thread(this::retrainNow, "Wormholes-Dict-Train");
        trainer.setDaemon(true);
        trainer.start();
    }

    void retrainNow() {
        try {
            NetworkConfig active = config;
            List<byte[]> snapshot = sampleCollector.snapshot();
            if (snapshot.isEmpty()) {
                return;
            }
            List<byte[]> holdout = new ArrayList<>();
            List<byte[]> training = new ArrayList<>(snapshot.size());
            long holdoutBytes = 0L;
            for (int index = 0; index < snapshot.size(); index++) {
                byte[] sample = snapshot.get(index);
                if (index % RETRAIN_HOLDOUT_STRIDE == 0 && holdoutBytes < RETRAIN_HOLDOUT_CAP_BYTES) {
                    holdout.add(sample);
                    holdoutBytes += sample.length;
                } else {
                    training.add(sample);
                }
            }
            if (training.isEmpty()) {
                training = snapshot;
                holdout = List.of();
            }
            try {
                CompressionDictionary trained = CompressionDictionary.train(training, active.transport.compressionDictTargetSize);
                CompressionDictionary existing = wireCompression.currentDictionary();
                if (existing != null && CompressionDictionary.sameHash(existing.hash(), trained.hash())) {
                    sampleCollector.reset();
                    return;
                }
                boolean adopt;
                if (existing == null || holdout.isEmpty()) {
                    adopt = true;
                } else {
                    long oldBytes = CompressionDictionary.compressedSizeSum(holdout, existing.bytes(), wireCompression.compressionLevel());
                    long newBytes = CompressionDictionary.compressedSizeSum(holdout, trained.bytes(), wireCompression.compressionLevel());
                    adopt = (double) newBytes < (double) oldBytes * RETRAIN_ADOPT_RATIO;
                }
                if (!adopt) {
                    logger.fine("net: dict retrain rejected, holdout shows no measurable improvement");
                    sampleCollector.reset();
                    return;
                }
                if (!running.get()) {
                    return;
                }
                demoteMismatchedPeers(trained.version());
                wireCompression.installDictionary(trained);
                try {
                    trained.save(dataDirectory.resolve("dict"));
                } catch (IOException e) {
                    logger.warning("net: could not persist trained dict v" + trained.version() + ": " + e.getMessage());
                }
                broadcastDictOffer(trained);
                sampleCollector.reset();
            } catch (RuntimeException e) {
                logger.log(Level.WARNING, "net: dict training failed", e);
            }
        } finally {
            retrainInFlight.set(false);
        }
    }

    private static boolean isZeroHash(byte[] hash) {
        if (hash == null || hash.length == 0) {
            return true;
        }
        for (byte b : hash) {
            if (b != 0) {
                return false;
            }
        }
        return true;
    }

    private static final class DictionaryTransfer {
        private final int version;
        private final int total;
        private final byte[] expectedHash;
        private final byte[][] chunks;
        private int received;

        private DictionaryTransfer(int version, int total, byte[] expectedHash) {
            this.version = version;
            this.total = total;
            this.expectedHash = expectedHash;
            this.chunks = new byte[total][];
        }

        private void addChunk(int index, byte[] chunk) {
            if (index < 0 || index >= total) {
                return;
            }
            if (chunks[index] != null) {
                return;
            }
            chunks[index] = chunk;
            received++;
        }

        private boolean isComplete() {
            return received == total;
        }

        private byte[] assemble() {
            int totalBytes = 0;
            for (byte[] chunk : chunks) {
                totalBytes += chunk.length;
            }
            byte[] dict = new byte[totalBytes];
            int offset = 0;
            for (byte[] chunk : chunks) {
                System.arraycopy(chunk, 0, dict, offset, chunk.length);
                offset += chunk.length;
            }
            return dict;
        }
    }

    private void receiveStatusBridgeMessages(String peerName, List<WireMessage> messages) {
        for (WireMessage message : messages) {
            receiveStatusBridgeMessage(peerName, message);
        }
    }

    private void receiveStatusBridgeMessage(String peerName, WireMessage message) {
        if (message instanceof WireMessage.SidebandFragment fragment) {
            WireMessage reassembled = receiveStatusFragment(peerName, fragment);
            if (reassembled == null) {
                return;
            }
            message = reassembled;
        }
        if (message instanceof WireMessage.Routed routed) {
            handleRouted(peerName, routed);
            return;
        }
        cacheRelayAnnouncement(peerName, message);
        relayPortalAnnouncement(peerName, peerName, ROUTE_TTL, message);
        deliverMessage(peerName, message);
    }

    private WireMessage receiveStatusFragment(String peerName, WireMessage.SidebandFragment fragment) {
        long now = System.currentTimeMillis();
        if (now >= nextStatusFragmentExpiry) {
            nextStatusFragmentExpiry = now + STATUS_FRAGMENT_EXPIRY_GATE_MS;
            expireStatusFragments(now);
        }
        if (!isValidStatusFragment(fragment)) {
            return null;
        }
        String key = statusFragmentKey(peerName, fragment.messageId());
        if (!statusFragments.containsKey(key) && statusFragments.size() >= STATUS_FRAGMENT_ASSEMBLY_CAPACITY) {
            return null;
        }
        byte[][] completedFrame = new byte[1][];
        statusFragments.compute(key, (ignored, previous) -> {
            StatusFragmentAssembly assembly = previous;
            if (assembly == null || !assembly.accepts(fragment)) {
                assembly = new StatusFragmentAssembly(fragment, now);
            }
            if (!assembly.add(fragment)) {
                return assembly;
            }
            if (!assembly.isComplete()) {
                return assembly;
            }
            completedFrame[0] = assembly.assemble();
            return null;
        });
        if (completedFrame[0] == null) {
            return null;
        }
        try {
            byte[] plainFrame = wireCompression.decode(completedFrame[0]).payload();
            return WireCodec.readFrame(new DataInputStream(new ByteArrayInputStream(plainFrame)));
        } catch (IOException e) {
            logger.warning("net: dropped corrupt status sideband jumbo frame from " + peerName + ": " + e.getMessage());
            return null;
        }
    }

    private static boolean isValidStatusFragment(WireMessage.SidebandFragment fragment) {
        if (fragment.total() <= 0 || fragment.total() > STATUS_FRAGMENT_MAX_COUNT) {
            return false;
        }
        if (fragment.index() < 0 || fragment.index() >= fragment.total()) {
            return false;
        }
        if (fragment.frameLength() <= 0 || fragment.frameLength() > STATUS_FRAGMENT_MAX_FRAME_BYTES) {
            return false;
        }
        return fragment.chunk() != null && fragment.chunk().length > 0 && fragment.chunk().length <= STATUS_FRAGMENT_CHUNK_BYTES;
    }

    private void markStatusBridgeReady(String peerName, long rttMillis) {
        boolean wasReady = isPeerReady(peerName);
        long now = System.currentTimeMillis();
        statusLastSeen.put(peerName, now);
        if (rttMillis >= 0L) {
            statusRttMillis.put(peerName, rttMillis);
        }
        dialFailures.remove(peerName);
        lastDialError.remove(peerName);
        if (!wasReady) {
            logger.info("net: peer " + peerName + " connected (game-port status sideband)");
            sendRelayedDirectoriesTo(peerName);
            BiConsumer<String, Boolean> sink = peerStateSink;
            if (sink != null) {
                sink.accept(peerName, true);
            }
        }
    }

    private void handleRouted(String inboundPeer, WireMessage.Routed routed) {
        if (routed.sourceServer() == null || routed.sourceServer().isBlank() || routed.targetServer() == null || routed.targetServer().isBlank()) {
            return;
        }
        if (routed.sourceServer().equals(getLocalName())) {
            return;
        }
        learnRoute(routed.sourceServer(), inboundPeer);
        if (routed.targetServer().equals(getLocalName())) {
            try {
                WireMessage inner = WireCodec.decodePayload(routed.innerType(), routed.payload());
                cacheRelayAnnouncement(routed.sourceServer(), inner);
                relayPortalAnnouncement(routed.sourceServer(), inboundPeer, routed.ttl() - 1, inner);
                deliverMessage(routed.sourceServer(), inner);
            } catch (IOException e) {
                logger.warning("net: dropped routed message from " + routed.sourceServer() + ": " + e.getMessage());
            }
            return;
        }
        if (routed.ttl() <= 0) {
            return;
        }
        forwardRouted(inboundPeer, routed.sourceServer(), routed.targetServer(), routed.ttl() - 1, routed.innerType(), routed.payload());
    }

    private void deliverMessage(String peerName, WireMessage message) {
        BiConsumer<String, WireMessage> sink = messageSink;
        if (sink != null) {
            sink.accept(peerName, message);
        }
    }

    @Override
    public void onClosed(PeerConnection connection, String reason) {
        pending.remove(connection);
        String name = connection.getPeerName();
        boolean wasReady = name != null && readyPeers.remove(name, connection);
        if (wasReady) {
            logger.info("net: peer " + name + " disconnected: " + reason);
            routes.entrySet().removeIf(entry -> entry.getValue().equals(name));
            if (!isStatusPeerReady(name)) {
                BiConsumer<String, Boolean> sink = peerStateSink;
                if (sink != null) {
                    sink.accept(name, false);
                }
            }
        }
        if (name != null && connection.isDialer()) {
            registerDialFailure(name);
            if (!wasReady && reason != null && !reason.startsWith("duplicate connection") && !"shutdown".equals(reason)) {
                lastDialError.put(name, connection.describeRemote() + " - " + reason);
            }
        }
    }

    private void runTcpAcceptLoop() {
        TcpPeerTransport transport = tcpTransport;
        while (running.get() && transport != null && transport.isListening()) {
            try {
                PeerTransport.PeerChannel channel = transport.accept();
                PeerConnection connection = new PeerConnection(channel, false, identity(config), null, null, this, this);
                pending.add(connection);
                connection.start();
            } catch (IOException e) {
                if (running.get()) {
                    logger.warning("net: tcp accept failed: " + e.getMessage());
                }
            }
        }
    }

    private void runUdsAcceptLoop() {
        UnixDomainPeerTransport transport = udsTransport;
        while (running.get() && transport != null && transport.isListening()) {
            try {
                PeerTransport.PeerChannel channel = transport.accept();
                PeerConnection connection = new PeerConnection(channel, false, identity(config), null, null, this, this);
                pending.add(connection);
                connection.start();
            } catch (IOException e) {
                if (running.get()) {
                    logger.warning("net: uds accept failed: " + e.getMessage());
                }
            }
        }
    }

    private void scanDials() {
        NetworkConfig active = config;
        long now = System.currentTimeMillis();
        String localName = getLocalName();
        for (NetworkConfig.PeerEntry peer : learnedPeers.values()) {
            if (peer.name == null || peer.name.isBlank() || peer.name.equals(localName) || !isDialable(peer)) {
                continue;
            }
            if (isRawPeerReady(peer.name) || hasPendingDial(peer.name)) {
                continue;
            }
            long notBefore = nextDialAttempt.getOrDefault(peer.name, 0L);
            if (now < notBefore) {
                continue;
            }
            dial(active, peer);
        }
    }

    private void pollStatusBridges() {
        ExecutorService exec = statusPollExecutor;
        if (exec == null) {
            return;
        }
        long now = System.currentTimeMillis();
        String localName = getLocalName();
        for (NetworkConfig.PeerEntry peer : learnedPeers.values()) {
            if (peer.name == null || peer.name.isBlank() || peer.name.equals(localName) || !isDialable(peer)) {
                continue;
            }
            if (!canUseStatusBridge(peer)) {
                continue;
            }
            if (now < nextStatusAttempt.getOrDefault(peer.name, 0L)) {
                continue;
            }
            if (statusPollInFlight.contains(peer.name)) {
                continue;
            }
            try {
                exec.execute(() -> pollStatusBridge(peer, System.currentTimeMillis(), false));
            } catch (RejectedExecutionException ignored) {
            }
        }
    }

    private void nudgeStatusPoll(String peerName) {
        ExecutorService exec = statusPollExecutor;
        if (exec == null) {
            return;
        }
        NetworkConfig.PeerEntry peer = findKnownPeer(peerName);
        if (peer == null || peer.name.equals(getLocalName()) || !canUseStatusBridge(peer)) {
            return;
        }
        nextStatusAttempt.put(peerName, 0L);
        try {
            exec.execute(() -> pollStatusBridge(peer, System.currentTimeMillis(), true));
        } catch (RejectedExecutionException ignored) {
        }
    }

    private static boolean isLatencyCriticalStatusMessage(WireMessage message) {
        WireMessageType type = message instanceof WireMessage.Routed routed ? routed.innerType() : message.type();
        return switch (type) {
            case HANDOFF_REQUEST, HANDOFF_ACK, HANDOFF_DENY, HANDOFF_CANCEL,
                 ENTITY_TRANSFER, ENTITY_TRANSFER_ACK, VIEW_SUBSCRIBE, VIEW_UNSUBSCRIBE -> true;
            default -> false;
        };
    }

    private void dial(NetworkConfig active, NetworkConfig.PeerEntry peer) {
        List<String> candidates = dialCandidates(peer);
        if (candidates.isEmpty()) {
            lastDialError.put(peer.name, "no route host available");
            registerDialFailure(peer.name);
            return;
        }
        int index = dialCandidateIndex.getOrDefault(peer.name, 0) % candidates.size();
        String host = candidates.get(index);
        if (active.transport.udsEnabled && hostResolvesToLoopback(host)) {
            PeerTransport.PeerChannel udsChannel = tryDialUds(active, peer);
            if (udsChannel != null) {
                PeerConnection connection = new PeerConnection(udsChannel, true, identity(active), peer.name, trustStore.get(peer.name), this, this);
                pending.add(connection);
                connection.start();
                return;
            }
        }
        try {
            PeerTransport.PeerChannel channel = TcpPeerTransport.dialDirect(host, peer.port, CONNECT_TIMEOUT_MS);
            PeerConnection connection = new PeerConnection(channel, true, identity(active), peer.name, trustStore.get(peer.name), this, this);
            pending.add(connection);
            connection.start();
        } catch (IOException e) {
            lastDialError.put(peer.name, host + ":" + peer.port + " - " + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()));
            dialCandidateIndex.put(peer.name, index + 1);
            registerDialFailure(peer.name);
        }
    }

    private PeerTransport.PeerChannel tryDialUds(NetworkConfig active, NetworkConfig.PeerEntry peer) {
        Path socketPath = peerUdsSocketPath(active, peer);
        if (socketPath == null || !Files.exists(socketPath)) {
            return null;
        }
        try {
            return UnixDomainPeerTransport.dial(socketPath);
        } catch (IOException | UnsupportedOperationException e) {
            return null;
        }
    }

    private Path udsDirectory(NetworkConfig active) {
        String configured = active.transport.udsDir;
        if (configured != null && !configured.isBlank()) {
            return Path.of(configured);
        }
        return dataDirectory.resolve("uds");
    }

    private Path localUdsSocketPath(NetworkConfig active) {
        return udsDirectory(active).resolve("peer-" + sanitizeForFile(getLocalName()) + ".sock");
    }

    private Path peerUdsSocketPath(NetworkConfig active, NetworkConfig.PeerEntry peer) {
        if (peer == null || peer.name == null || peer.name.isBlank()) {
            return null;
        }
        return udsDirectory(active).resolve("peer-" + sanitizeForFile(peer.name) + ".sock");
    }

    private static String sanitizeForFile(String value) {
        StringBuilder builder = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            boolean safe = (ch >= 'a' && ch <= 'z') || (ch >= 'A' && ch <= 'Z') || (ch >= '0' && ch <= '9') || ch == '-' || ch == '_' || ch == '.';
            builder.append(safe ? ch : '_');
        }
        return builder.toString();
    }

    private static boolean hostResolvesToLoopback(String host) {
        if (host == null || host.isBlank()) {
            return false;
        }
        try {
            InetAddress address = InetAddress.getByName(host);
            return address.isLoopbackAddress();
        } catch (UnknownHostException e) {
            return false;
        }
    }

    private void pollStatusBridge(NetworkConfig.PeerEntry peer, long now, boolean ignoreSchedule) {
        if (!canUseStatusBridge(peer)) {
            return;
        }
        if (!ignoreSchedule && now < nextStatusAttempt.getOrDefault(peer.name, 0L)) {
            return;
        }
        if (!statusPollInFlight.add(peer.name)) {
            return;
        }
        try {
            for (int continuation = 0; continuation < STATUS_BRIDGE_MAX_CONTINUATIONS; continuation++) {
                if (!pollStatusBridgeOnce(peer)) {
                    return;
                }
            }
        } finally {
            statusPollInFlight.remove(peer.name);
        }
    }

    private boolean pollStatusBridgeOnce(NetworkConfig.PeerEntry peer) {
        long started = System.currentTimeMillis();
        nextStatusAttempt.put(peer.name, started + STATUS_BRIDGE_FAST_INTERVAL_MS);
        List<MinecraftStatusBridge.EncodedMessage> messages = drainStatusOutbox(peer.name, statusRequestBudgetFor(peer.name));
        try {
            MinecraftStatusBridge.StatusPacket response = statusBridge.poll(peer, createStatusBridgePacket(peer.name, messages));
            if (handleStatusBridgeResponse(peer.name, response, System.currentTimeMillis() - started)) {
                recordStatusRequestSuccess(peer.name);
                lastDialError.remove(peer.name);
                if (statusPollFailing.remove(peer.name)) {
                    logger.info("net: status sideband to " + peer.name + " recovered");
                }
            }
            boolean dataFlowing = !messages.isEmpty()
                || (response != null && !response.messages().isEmpty())
                || statusOutboxPending(peer.name);
            if (!dataFlowing) {
                nextStatusAttempt.put(peer.name, System.currentTimeMillis() + STATUS_BRIDGE_INTERVAL_MS);
                return false;
            }
            return statusOutboxPending(peer.name);
        } catch (IOException | RuntimeException e) {
            requeueStatusMessages(peer.name, messages);
            recordStatusRequestFailure(peer.name);
            nextStatusAttempt.put(peer.name, System.currentTimeMillis() + STATUS_BRIDGE_FAIL_BACKOFF_MS);
            String failure = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            if (!isRawPeerReady(peer.name)) {
                lastDialError.put(peer.name, statusBridgeAddress(peer) + " - " + failure);
            }
            if (!messages.isEmpty() && statusPollFailing.add(peer.name)) {
                logger.warning("net: status sideband data poll to " + peer.name + " failed (" + failure + "); request carried "
                    + messages.size() + " message(s) ~" + drainedFrameBytes(messages) + " frame bytes. Large projection/entity payloads can exceed the game-port status limit -- open the raw Wormholes port " + getBoundListenPort() + " on both servers for reliable high-throughput streaming.");
            }
            return false;
        }
    }

    int statusRequestBudgetFor(String peerName) {
        return statusRequestBudgets.getOrDefault(peerName, STATUS_BRIDGE_REQUEST_BUDGET_MIN_BYTES);
    }

    void recordStatusRequestSuccess(String peerName) {
        statusRequestBudgets.put(peerName, Math.min(STATUS_BRIDGE_REQUEST_BUDGET_MAX_BYTES, statusRequestBudgetFor(peerName) * 2));
    }

    void recordStatusRequestFailure(String peerName) {
        statusRequestBudgets.put(peerName, Math.max(STATUS_BRIDGE_REQUEST_BUDGET_MIN_BYTES, statusRequestBudgetFor(peerName) / 2));
    }

    private static int drainedFrameBytes(List<MinecraftStatusBridge.EncodedMessage> messages) {
        int total = 0;
        for (int i = 0; i < messages.size(); i++) {
            total += messages.get(i).frame().length;
        }
        return total;
    }

    private static List<String> dialCandidates(NetworkConfig.PeerEntry peer) {
        List<String> candidates = new ArrayList<>(3);
        if (peer.host != null && !peer.host.isBlank()) {
            candidates.add(peer.host);
        }
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
        expireStatusBridgePeers(now);
        expireStatusFragments(now);
        OutboundFrame ping = new OutboundFrame(new WireMessage.Ping(now));
        for (PeerConnection connection : readyPeers.values()) {
            connection.send(ping);
        }
    }

    private boolean enqueueRoutedStatusMessage(String nextHop, String sourceServer, String targetServer, int ttl, WireMessage message) {
        if (message instanceof WireMessage.Routed) {
            return false;
        }
        try {
            return enqueueStatusMessage(nextHop, new OutboundFrame(new WireMessage.Routed(sourceServer, targetServer, ttl, message.type(), WireCodec.encodePayload(message))));
        } catch (IOException e) {
            logger.warning("net: could not queue routed status sideband " + message.type() + " from " + sourceServer + " to " + targetServer + ": " + e.getMessage());
            return false;
        }
    }

    private boolean enqueueStatusMessage(String peerName, OutboundFrame frame) {
        List<MinecraftStatusBridge.EncodedMessage> queuedMessages = statusMessagesFor(peerName, frame);
        if (queuedMessages.isEmpty()) {
            return false;
        }
        BlockingQueue<MinecraftStatusBridge.EncodedMessage> queue = statusOutbox.computeIfAbsent(peerName, ignored -> new LinkedBlockingQueue<>(STATUS_BRIDGE_QUEUE_CAPACITY));
        boolean bestEffort = isBestEffortStatusMessage(frame.message());
        if (bestEffort && queue.size() >= STATUS_BRIDGE_BESTEFFORT_SHED_THRESHOLD) {
            return false;
        }
        if (queue.remainingCapacity() < queuedMessages.size()) {
            if (bestEffort || !purgeBestEffortStatusMessages(queue, queuedMessages.size())) {
                if (!bestEffort) {
                    lastDialError.put(peerName, "game-port status sideband queue is full");
                }
                return false;
            }
        }
        for (MinecraftStatusBridge.EncodedMessage queuedMessage : queuedMessages) {
            queue.offer(queuedMessage);
        }
        return true;
    }

    private static boolean purgeBestEffortStatusMessages(BlockingQueue<MinecraftStatusBridge.EncodedMessage> queue, int needed) {
        queue.removeIf(entry -> isQueuedBestEffortStatusMessage(entry.message()));
        return queue.remainingCapacity() >= needed;
    }

    private static boolean isQueuedBestEffortStatusMessage(WireMessage message) {
        if (message instanceof WireMessage.SidebandFragment) {
            return false;
        }
        return isBestEffortStatusMessage(message);
    }

    private static boolean isBestEffortStatusMessage(WireMessage message) {
        WireMessageType type = message instanceof WireMessage.Routed routed ? routed.innerType() : message.type();
        return type == WireMessageType.VIEW_ENTITIES
            || type == WireMessageType.VIEW_ENTITY_ANIMATION
            || type == WireMessageType.CHUNK_HASH_PROBE;
    }

    private static int statusTier(WireMessage message) {
        if (message instanceof WireMessage.SidebandFragment) {
            return 1;
        }
        WireMessageType type = message instanceof WireMessage.Routed routed ? routed.innerType() : message.type();
        return switch (type) {
            case VIEW_ENTITIES, VIEW_ENTITY_ANIMATION, CHUNK_HASH_PROBE -> 2;
            case CHUNK_BULK, CHUNK_DIFF -> 1;
            default -> 0;
        };
    }

    private List<MinecraftStatusBridge.EncodedMessage> statusMessagesFor(String peerName, OutboundFrame frame) {
        try {
            byte[] probe = frame.plainFrame();
            if (probe.length <= MinecraftStatusBridge.MAX_FRAME_BYTES) {
                return List.of(new MinecraftStatusBridge.EncodedMessage(frame.message(), probe));
            }
            return fragmentStatusMessage(peerName, frame.message(), probe);
        } catch (IOException e) {
            logger.warning("net: could not encode " + frame.message().type() + " for status sideband to " + peerName + ": " + e.getMessage());
            return List.of();
        }
    }

    private List<MinecraftStatusBridge.EncodedMessage> fragmentStatusMessage(String peerName, WireMessage message, byte[] plainFrame) throws IOException {
        byte[] frame;
        try {
            frame = wireCompression.encode(plainFrame, false, MinecraftStatusBridge.SIDEBAND_ZSTD_LEVEL);
        } catch (IOException e) {
            logger.warning("net: could not compress " + message.type() + " for status sideband to " + peerName + ": " + e.getMessage());
            return List.of();
        }
        if (frame.length > STATUS_FRAGMENT_MAX_FRAME_BYTES) {
            warnOversizedStatusMessage(peerName, message, frame.length);
            return List.of();
        }
        int total = (frame.length + STATUS_FRAGMENT_CHUNK_BYTES - 1) / STATUS_FRAGMENT_CHUNK_BYTES;
        if (total > STATUS_FRAGMENT_MAX_COUNT) {
            warnOversizedStatusMessage(peerName, message, frame.length);
            return List.of();
        }
        warnFragmentedStatusMessage(peerName, message, frame.length, total);
        long messageId = statusFragmentIds.incrementAndGet();
        List<MinecraftStatusBridge.EncodedMessage> fragments = new ArrayList<>(total);
        for (int index = 0; index < total; index++) {
            int offset = index * STATUS_FRAGMENT_CHUNK_BYTES;
            int length = Math.min(STATUS_FRAGMENT_CHUNK_BYTES, frame.length - offset);
            byte[] chunk = new byte[length];
            System.arraycopy(frame, offset, chunk, 0, length);
            WireMessage.SidebandFragment fragment = new WireMessage.SidebandFragment(messageId, index, total, frame.length, chunk);
            fragments.add(new MinecraftStatusBridge.EncodedMessage(fragment, WireCodec.encodeFrame(fragment)));
        }
        return fragments;
    }

    private void warnFragmentedStatusMessage(String peerName, WireMessage message, int frameLength, int fragments) {
        String key = peerName + ":" + message.type() + ":fragmented";
        if (statusOversizeWarnings.add(key)) {
            logger.warning("net: " + message.type() + " for " + peerName + " is " + frameLength + " bytes and will use " + fragments + " signed game-port sideband fragments; open the raw Wormholes port for high-throughput projection traffic");
        }
    }

    private void warnOversizedStatusMessage(String peerName, WireMessage message, int frameLength) {
        String key = peerName + ":" + message.type();
        if (statusOversizeWarnings.add(key)) {
            logger.warning("net: " + message.type() + " for " + peerName + " is " + frameLength + " bytes and exceeds the Wormholes sideband jumbo frame limit; open the raw Wormholes port for high-throughput projection traffic");
        }
    }

    private boolean statusOutboxPending(String peerName) {
        BlockingQueue<MinecraftStatusBridge.EncodedMessage> queue = statusOutbox.get(peerName);
        return queue != null && !queue.isEmpty();
    }

    List<MinecraftStatusBridge.EncodedMessage> drainStatusOutbox(String peerName, int budgetBytes) {
        BlockingQueue<MinecraftStatusBridge.EncodedMessage> queue = statusOutbox.get(peerName);
        if (queue == null) {
            return List.of();
        }
        List<MinecraftStatusBridge.EncodedMessage> messages = new ArrayList<>(Math.min(MinecraftStatusBridge.MAX_MESSAGES, queue.size()));
        int[] remainingBytes = {budgetBytes};
        drainStatusPass(queue, messages, remainingBytes, 0);
        drainStatusPass(queue, messages, remainingBytes, 1);
        drainStatusPass(queue, messages, remainingBytes, 2);
        return messages.isEmpty() ? List.of() : messages;
    }

    private static void drainStatusPass(BlockingQueue<MinecraftStatusBridge.EncodedMessage> queue, List<MinecraftStatusBridge.EncodedMessage> messages, int[] remainingBytes, int tier) {
        Iterator<MinecraftStatusBridge.EncodedMessage> iterator = queue.iterator();
        while (messages.size() < MinecraftStatusBridge.MAX_MESSAGES && iterator.hasNext()) {
            MinecraftStatusBridge.EncodedMessage entry = iterator.next();
            if (statusTier(entry.message()) != tier) {
                continue;
            }
            if (!messages.isEmpty() && entry.frame().length > remainingBytes[0]) {
                continue;
            }
            iterator.remove();
            messages.add(entry);
            remainingBytes[0] -= entry.frame().length;
        }
    }

    private void requeueStatusMessages(String peerName, List<MinecraftStatusBridge.EncodedMessage> messages) {
        if (messages.isEmpty()) {
            return;
        }
        BlockingQueue<MinecraftStatusBridge.EncodedMessage> queue = statusOutbox.computeIfAbsent(peerName, ignored -> new LinkedBlockingQueue<>(STATUS_BRIDGE_QUEUE_CAPACITY));
        for (MinecraftStatusBridge.EncodedMessage message : messages) {
            queue.offer(message);
        }
    }

    private void expireStatusBridgePeers(long now) {
        for (Map.Entry<String, Long> entry : statusLastSeen.entrySet()) {
            String peerName = entry.getKey();
            long lastSeen = entry.getValue();
            if (now - lastSeen <= STATUS_BRIDGE_READY_TTL_MS) {
                continue;
            }
            if (!statusLastSeen.remove(peerName, lastSeen)) {
                continue;
            }
            statusRttMillis.remove(peerName);
            if (!isRawPeerReady(peerName)) {
                logger.info("net: peer " + peerName + " disconnected: game-port status sideband timed out");
                BiConsumer<String, Boolean> sink = peerStateSink;
                if (sink != null) {
                    sink.accept(peerName, false);
                }
            }
        }
    }

    private void expireStatusFragments(long now) {
        statusFragments.entrySet().removeIf(entry -> now - entry.getValue().createdAtMillis() > STATUS_FRAGMENT_TTL_MS);
    }

    private static String statusFragmentKey(String peerName, long messageId) {
        return peerName + ":" + messageId;
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

    private NetworkConfig.PeerEntry findKnownPeer(String name) {
        return learnedPeers.get(name);
    }

    private List<NetworkConfig.PeerEntry> knownPeers() {
        List<NetworkConfig.PeerEntry> peers = new ArrayList<>(learnedPeers.size());
        for (NetworkConfig.PeerEntry peer : learnedPeers.values()) {
            if (peer.name != null && !peer.name.isBlank()) {
                peers.add(peer);
            }
        }
        return peers;
    }

    private static boolean isDialable(NetworkConfig.PeerEntry peer) {
        if (peer == null) {
            return false;
        }
        if (peer.host != null && !peer.host.isBlank()) {
            return true;
        }
        return peer.fallbackHosts != null && !peer.fallbackHosts.isBlank();
    }

    private boolean isStatusPeerReady(String name) {
        Long lastSeen = statusLastSeen.get(name);
        return lastSeen != null && System.currentTimeMillis() - lastSeen <= STATUS_BRIDGE_READY_TTL_MS;
    }

    private static boolean canUseStatusBridge(NetworkConfig.PeerEntry peer) {
        if (peer == null) {
            return false;
        }
        String host = peer.publicHost == null || peer.publicHost.isBlank() ? peer.host : peer.publicHost;
        return host != null && !host.isBlank();
    }

    private boolean canQueueStatusBridge(NetworkConfig.PeerEntry peer) {
        return peer != null && (canUseStatusBridge(peer) || isStatusPeerReady(peer.name));
    }

    private static String peerAddress(NetworkConfig.PeerEntry peer) {
        if (peer.host == null || peer.host.isBlank()) {
            return "route unavailable";
        }
        return peer.host + ":" + peer.port;
    }

    private static String statusBridgeAddress(NetworkConfig.PeerEntry peer) {
        String host = peer.publicHost == null || peer.publicHost.isBlank() ? peer.host : peer.publicHost;
        if (host == null || host.isBlank()) {
            return "game-port route unavailable";
        }
        int port = peer.publicPort > 0 ? peer.publicPort : 25565;
        return "game-port " + host + ":" + port;
    }

    private String generatedServerName() {
        String name = generatedName;
        if (name == null) {
            name = "wh-" + getPublicKeyFingerprint().replace(":", "");
            generatedName = name;
        }
        return name;
    }

    private static String blank(String value) {
        return value == null ? "" : value;
    }

    private void learnRoute(String sourceServer, String inboundPeer) {
        if (inboundPeer == null || inboundPeer.isBlank() || sourceServer.equals(inboundPeer) || sourceServer.equals(getLocalName())) {
            return;
        }
        if (readyPeers.containsKey(sourceServer)) {
            return;
        }
        routes.put(sourceServer, inboundPeer);
    }

    private boolean sendRouted(PeerConnection connection, String sourceServer, String targetServer, int ttl, WireMessage message) {
        if (message instanceof WireMessage.Routed) {
            return false;
        }
        try {
            return connection.send(new WireMessage.Routed(sourceServer, targetServer, ttl, message.type(), WireCodec.encodePayload(message)));
        } catch (IOException e) {
            logger.warning("net: could not route " + message.type() + " from " + sourceServer + " to " + targetServer + ": " + e.getMessage());
            return false;
        }
    }

    private boolean forwardRouted(String inboundPeer, String sourceServer, String targetServer, int ttl, WireMessageType innerType, byte[] payload) {
        PeerConnection direct = readyPeers.get(targetServer);
        if (direct != null && !targetServer.equals(inboundPeer)) {
            return direct.send(new WireMessage.Routed(sourceServer, targetServer, ttl, innerType, payload));
        }
        String nextHop = routes.get(targetServer);
        if (nextHop == null || nextHop.equals(inboundPeer)) {
            return false;
        }
        PeerConnection route = readyPeers.get(nextHop);
        return route != null && route.send(new WireMessage.Routed(sourceServer, targetServer, ttl, innerType, payload));
    }

    private void cacheRelayAnnouncement(String sourceServer, WireMessage message) {
        if (sourceServer == null || sourceServer.isBlank()) {
            return;
        }
        if (message instanceof WireMessage.PortalDirectory directory) {
            relayedPortalDirectories.put(sourceServer, List.copyOf(directory.portals()));
            return;
        }
        if (message instanceof WireMessage.PortalUpsert upsert) {
            relayedPortalDirectories.compute(sourceServer, (name, previous) -> {
                List<PortalInfo> next = previous == null ? new ArrayList<>() : new ArrayList<>(previous);
                next.removeIf(portal -> portal.id().equals(upsert.portal().id()));
                next.add(upsert.portal());
                return List.copyOf(next);
            });
            return;
        }
        if (message instanceof WireMessage.PortalRemove remove) {
            relayedPortalDirectories.computeIfPresent(sourceServer, (name, previous) -> {
                List<PortalInfo> next = new ArrayList<>(previous);
                next.removeIf(portal -> portal.id().equals(remove.portalId()));
                return List.copyOf(next);
            });
        }
    }

    private void relayPortalAnnouncement(String sourceServer, String inboundPeer, int ttl, WireMessage message) {
        if (ttl <= 0 || !isRelayAnnouncement(message)) {
            return;
        }
        for (NetworkConfig.PeerEntry peer : knownPeers()) {
            String target = peer.name;
            if (target.equals(inboundPeer) || target.equals(sourceServer) || target.equals(getLocalName())) {
                continue;
            }
            PeerConnection connection = readyPeers.get(target);
            if (connection != null) {
                sendRouted(connection, sourceServer, target, ttl, message);
                continue;
            }
            if (canQueueStatusBridge(peer)) {
                enqueueRoutedStatusMessage(target, sourceServer, target, ttl, message);
            }
        }
    }

    private void sendRelayedDirectoriesTo(String targetServer) {
        PeerConnection connection = readyPeers.get(targetServer);
        NetworkConfig.PeerEntry targetPeer = findKnownPeer(targetServer);
        if (connection == null && !canQueueStatusBridge(targetPeer)) {
            return;
        }
        for (Map.Entry<String, List<PortalInfo>> entry : relayedPortalDirectories.entrySet()) {
            String sourceServer = entry.getKey();
            if (sourceServer.equals(targetServer) || sourceServer.equals(getLocalName())) {
                continue;
            }
            WireMessage directory = new WireMessage.PortalDirectory(entry.getValue());
            if (connection != null) {
                sendRouted(connection, sourceServer, targetServer, ROUTE_TTL, directory);
            } else {
                enqueueRoutedStatusMessage(targetServer, sourceServer, targetServer, ROUTE_TTL, directory);
            }
        }
    }

    private static boolean isRelayAnnouncement(WireMessage message) {
        return message instanceof WireMessage.PortalDirectory
            || message instanceof WireMessage.PortalUpsert
            || message instanceof WireMessage.PortalRemove;
    }

    private static final class StatusFragmentAssembly {
        private final long messageId;
        private final int total;
        private final int frameLength;
        private final byte[][] chunks;
        private final long createdAtMillis;
        private int received;
        private int receivedBytes;

        private StatusFragmentAssembly(WireMessage.SidebandFragment first, long createdAtMillis) {
            this.messageId = first.messageId();
            this.total = first.total();
            this.frameLength = first.frameLength();
            this.chunks = new byte[first.total()][];
            this.createdAtMillis = createdAtMillis;
        }

        private boolean accepts(WireMessage.SidebandFragment fragment) {
            return messageId == fragment.messageId()
                && total == fragment.total()
                && frameLength == fragment.frameLength();
        }

        private boolean add(WireMessage.SidebandFragment fragment) {
            if (!accepts(fragment)) {
                return false;
            }
            int index = fragment.index();
            if (index < 0 || index >= chunks.length || chunks[index] != null) {
                return false;
            }
            byte[] chunk = fragment.chunk();
            if (receivedBytes + chunk.length > frameLength) {
                return false;
            }
            chunks[index] = chunk;
            received++;
            receivedBytes += chunk.length;
            return true;
        }

        private boolean isComplete() {
            return received == total && receivedBytes == frameLength;
        }

        private byte[] assemble() {
            byte[] frame = new byte[frameLength];
            int offset = 0;
            for (byte[] chunk : chunks) {
                System.arraycopy(chunk, 0, frame, offset, chunk.length);
                offset += chunk.length;
            }
            return frame;
        }

        private long createdAtMillis() {
            return createdAtMillis;
        }
    }
}
