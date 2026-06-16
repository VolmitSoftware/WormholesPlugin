package art.arcane.wormholes.network.replication;

import art.arcane.wormholes.network.NetworkManager;
import art.arcane.wormholes.network.WireMessage;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public final class HashProbeScheduler {
    private final NetworkManager network;
    private final ChunkReplicationManager replication;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final Map<String, Integer> rotationCursors = new ConcurrentHashMap<>();
    private volatile ScheduledExecutorService scheduler;
    private volatile long intervalSec = 30L;
    private volatile int chunksPerProbe = 16;

    public HashProbeScheduler(NetworkManager network, ChunkReplicationManager replication) {
        this.network = network;
        this.replication = replication;
    }

    public void configure(long intervalSec, int chunksPerProbe) {
        this.intervalSec = Math.max(1L, intervalSec);
        this.chunksPerProbe = Math.max(1, chunksPerProbe);
    }

    public void start() {
        if (!running.compareAndSet(false, true)) {
            return;
        }
        ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "Wormholes-HashProbe");
            thread.setDaemon(true);
            return thread;
        });
        long period = Math.max(1L, intervalSec);
        executor.scheduleWithFixedDelay(this::probeOnce, period, period, TimeUnit.SECONDS);
        scheduler = executor;
    }

    public void stop() {
        if (!running.compareAndSet(true, false)) {
            return;
        }
        ScheduledExecutorService active = scheduler;
        scheduler = null;
        if (active != null) {
            active.shutdownNow();
        }
        rotationCursors.clear();
    }

    public void probeOnce() {
        for (String peerName : currentPeers()) {
            List<Long> chunkKeys = replication.chunksFor(peerName);
            if (chunkKeys.isEmpty()) {
                continue;
            }
            int cursor = rotationCursors.getOrDefault(peerName, 0);
            if (cursor >= chunkKeys.size()) {
                cursor = 0;
            }
            List<ChunkHashProbe.ChunkHashEntry> entries = new ArrayList<>(Math.min(chunksPerProbe, chunkKeys.size()));
            int taken = 0;
            int index = cursor;
            while (taken < chunksPerProbe && taken < chunkKeys.size()) {
                long chunkKey = chunkKeys.get(index % chunkKeys.size());
                long sequence = replication.lastBroadcastSeq(peerName, chunkKey);
                long hash = replication.canonicalHash(peerName, chunkKey);
                entries.add(new ChunkHashProbe.ChunkHashEntry(chunkKey, sequence, hash));
                index++;
                taken++;
            }
            rotationCursors.put(peerName, index % chunkKeys.size());
            if (entries.isEmpty()) {
                continue;
            }
            ChunkHashProbe probe = new ChunkHashProbe(entries);
            network.send(peerName, new WireMessage.ChunkHashProbeMessage(probe));
        }
    }

    private List<String> currentPeers() {
        List<String> names = new ArrayList<>();
        for (NetworkManager.PeerSnapshot snapshot : network.peerSnapshots()) {
            if (snapshot.handshakeComplete() && !snapshot.disconnected()) {
                names.add(snapshot.name());
            }
        }
        return names;
    }
}
