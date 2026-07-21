package art.arcane.wormholes.chunk;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.CompletionStage;

public final class ChunkLeaseRegistry<W> {
    private final ChunkLeasePlatform<W> platform;
    private final Options options;
    private final SerializedOwner owner;
    private final Map<ChunkKey, LeaseRecord> records;
    private final Map<UUID, LeaseRecord> ownerRecords;
    private final Map<UUID, Long> worldEpochs;
    private boolean accepting;

    public ChunkLeaseRegistry(ChunkLeasePlatform<W> platform, Options options) {
        this.platform = Objects.requireNonNull(platform);
        this.options = Objects.requireNonNull(options);
        this.owner = new SerializedOwner();
        this.records = new HashMap<>();
        this.ownerRecords = new HashMap<>();
        this.worldEpochs = new HashMap<>();
        this.accepting = true;
    }

    public ChunkLease retain(W world, UUID worldId, int chunkX, int chunkZ) {
        Objects.requireNonNull(world);
        Objects.requireNonNull(worldId);
        ChunkLease lease = new ChunkLease(UUID.randomUUID(), released -> owner.execute(() -> release(released)));
        owner.execute(() -> retain(world, worldId, chunkX, chunkZ, lease));
        return lease;
    }

    public void worldUnloaded(UUID worldId) {
        Objects.requireNonNull(worldId);
        owner.execute(() -> unloadWorld(worldId));
    }

    public void shutdown() {
        owner.execute(this::stop);
    }

    private void retain(W world, UUID worldId, int chunkX, int chunkZ, ChunkLease lease) {
        if (!accepting || !lease.isValid()) {
            lease.terminalize();
            return;
        }
        long worldEpoch = worldEpochs.getOrDefault(worldId, 0L);
        ChunkKey key = new ChunkKey(worldId, worldEpoch, chunkX, chunkZ);
        LeaseRecord record = records.get(key);
        if (record == null) {
            record = new LeaseRecord(key, world);
            records.put(key, record);
            addOwner(record, lease);
            startAdd(record, 1);
            return;
        }
        addOwner(record, lease);
        switch (record.phase) {
            case PRESENT -> lease.completeReady();
            case IDLE_PRESENT -> {
                record.idleRevision++;
                record.phase = Phase.PRESENT;
                lease.completeReady();
            }
            case IDLE_ADDING -> {
                record.idleRevision++;
                record.phase = Phase.ADDING;
            }
            case IDLE_CLEANUP -> {
                record.idleRevision++;
                startAdd(record, 1);
            }
            default -> {
            }
        }
    }

    private void addOwner(LeaseRecord record, ChunkLease lease) {
        record.owners.put(lease.ownerToken(), lease);
        ownerRecords.put(lease.ownerToken(), record);
    }

    private void release(ChunkLease lease) {
        LeaseRecord record = ownerRecords.remove(lease.ownerToken());
        if (record == null || record.owners.remove(lease.ownerToken()) == null || !record.owners.isEmpty()) {
            return;
        }
        switch (record.phase) {
            case ADDING -> scheduleIdleRemoval(record, Phase.IDLE_ADDING);
            case RETRY_ADD, IDLE_CLEANUP -> scheduleIdleRemoval(record, Phase.IDLE_CLEANUP);
            case PRESENT -> scheduleIdleRemoval(record, Phase.IDLE_PRESENT);
            case REMOVING, RETRY_REMOVE, IDLE_ADDING, IDLE_PRESENT -> {
            }
        }
    }

    private void startAdd(LeaseRecord record, int attempt) {
        record.phase = Phase.ADDING;
        long revision = ++record.revision;
        CompletionStage<Boolean> result;
        try {
            result = Objects.requireNonNull(platform.add(record.world, record.key.chunkX(), record.key.chunkZ()));
        } catch (RuntimeException error) {
            finishAdd(record, revision, attempt, null, error);
            return;
        }
        result.whenComplete((added, error) -> owner.execute(() -> finishAdd(record, revision, attempt, added, error)));
    }

    private void finishAdd(LeaseRecord record, long revision, int attempt, Boolean added, Throwable error) {
        if (error != null) {
            platform.reportFailure(error);
        }
        if (!isCurrentAdd(record, revision)) {
            if (error == null
                && Boolean.TRUE.equals(added)
                && records.get(record.key) != record
                && replacementFor(record) == null) {
                removeDetached(record, 1);
            }
            return;
        }
        if (error != null) {
            if (record.owners.isEmpty()) {
                return;
            }
            if (attempt < options.maxAttempts()) {
                scheduleAddRetry(record, attempt + 1);
                return;
            }
            failAdd(record);
            return;
        }
        if (record.owners.isEmpty()) {
            return;
        }
        record.phase = Phase.PRESENT;
        for (ChunkLease lease : record.owners.values()) {
            lease.completeReady();
        }
    }

    private void scheduleAddRetry(LeaseRecord record, int nextAttempt) {
        record.phase = Phase.RETRY_ADD;
        long revision = ++record.revision;
        boolean scheduled = platform.schedule(
            () -> owner.execute(() -> retryAdd(record, revision, nextAttempt)),
            options.retryDelayMillis()
        );
        if (!scheduled) {
            failAdd(record);
        }
    }

    private void retryAdd(LeaseRecord record, long revision, int attempt) {
        if (!isCurrent(record, revision, Phase.RETRY_ADD) || record.owners.isEmpty()) {
            return;
        }
        startAdd(record, attempt);
    }

    private void failAdd(LeaseRecord record) {
        terminalize(record);
        scheduleIdleRemoval(record, Phase.IDLE_CLEANUP);
    }

    private void scheduleIdleRemoval(LeaseRecord record, Phase idlePhase) {
        record.phase = idlePhase;
        if (idlePhase != Phase.IDLE_ADDING) {
            record.revision++;
        }
        long idleRevision = ++record.idleRevision;
        boolean scheduled = platform.schedule(
            () -> owner.execute(() -> beginRemoval(record, idleRevision, idlePhase)),
            options.idleDelayMillis()
        );
        if (!scheduled) {
            beginRemoval(record, idleRevision, idlePhase);
        }
    }

    private void beginRemoval(LeaseRecord record, long idleRevision, Phase idlePhase) {
        if (records.get(record.key) != record
            || record.idleRevision != idleRevision
            || record.phase != idlePhase
            || !record.owners.isEmpty()) {
            return;
        }
        startRemove(record, 1);
    }

    private void startRemove(LeaseRecord record, int attempt) {
        record.phase = Phase.REMOVING;
        long revision = ++record.revision;
        CompletionStage<Boolean> result;
        try {
            result = Objects.requireNonNull(platform.remove(record.world, record.key.chunkX(), record.key.chunkZ()));
        } catch (RuntimeException error) {
            finishRemoval(record, revision, attempt, error);
            return;
        }
        result.whenComplete((removed, error) -> owner.execute(() -> finishRemoval(record, revision, attempt, error)));
    }

    private void finishRemoval(LeaseRecord record, long revision, int attempt, Throwable error) {
        if (error != null) {
            platform.reportFailure(error);
        }
        if (!isCurrent(record, revision, Phase.REMOVING)) {
            return;
        }
        if (error != null) {
            if (attempt < options.maxAttempts()) {
                scheduleRemoveRetry(record, attempt + 1);
                return;
            }
            failRemoval(record);
            return;
        }
        if (record.owners.isEmpty()) {
            records.remove(record.key, record);
            return;
        }
        startAdd(record, 1);
    }

    private void scheduleRemoveRetry(LeaseRecord record, int nextAttempt) {
        record.phase = Phase.RETRY_REMOVE;
        long revision = ++record.revision;
        boolean scheduled = platform.schedule(
            () -> owner.execute(() -> retryRemove(record, revision, nextAttempt)),
            options.retryDelayMillis()
        );
        if (!scheduled) {
            platform.reportFailure(new IllegalStateException("Chunk lease removal retry scheduling rejected"));
            startRemove(record, nextAttempt);
        }
    }

    private void retryRemove(LeaseRecord record, long revision, int attempt) {
        if (!isCurrent(record, revision, Phase.RETRY_REMOVE)) {
            return;
        }
        startRemove(record, attempt);
    }

    private void failRemoval(LeaseRecord record) {
        terminalize(record);
        records.remove(record.key, record);
    }

    private void unloadWorld(UUID worldId) {
        worldEpochs.put(worldId, worldEpochs.getOrDefault(worldId, 0L) + 1L);
        List<LeaseRecord> removedRecords = new ArrayList<>();
        for (LeaseRecord record : records.values()) {
            if (record.key.worldId().equals(worldId)) {
                removedRecords.add(record);
            }
        }
        for (LeaseRecord record : removedRecords) {
            records.remove(record.key, record);
            record.revision++;
            terminalize(record);
            removeDetached(record, 1);
        }
    }

    private void stop() {
        if (!accepting) {
            return;
        }
        accepting = false;
        List<LeaseRecord> removedRecords = new ArrayList<>(records.values());
        records.clear();
        worldEpochs.clear();
        for (LeaseRecord record : removedRecords) {
            record.revision++;
            terminalize(record);
            removeDetached(record, 1);
        }
    }

    private void removeDetached(LeaseRecord record, int attempt) {
        CompletionStage<Boolean> result;
        try {
            result = Objects.requireNonNull(platform.remove(record.world, record.key.chunkX(), record.key.chunkZ()));
        } catch (RuntimeException error) {
            finishDetachedRemoval(record, attempt, error);
            return;
        }
        result.whenComplete((removed, error) -> owner.execute(() -> finishDetachedRemoval(record, attempt, error)));
    }

    private void finishDetachedRemoval(LeaseRecord record, int attempt, Throwable error) {
        if (error == null) {
            LeaseRecord replacement = replacementFor(record);
            if (replacement != null) {
                startAdd(replacement, 1);
            }
            return;
        }
        platform.reportFailure(error);
        if (attempt >= options.maxAttempts()) {
            return;
        }
        boolean scheduled = platform.schedule(
            () -> owner.execute(() -> removeDetached(record, attempt + 1)),
            options.retryDelayMillis()
        );
        if (!scheduled) {
            platform.reportFailure(new IllegalStateException("Detached chunk lease removal retry scheduling rejected"));
            removeDetached(record, attempt + 1);
        }
    }

    private LeaseRecord replacementFor(LeaseRecord stale) {
        for (LeaseRecord candidate : records.values()) {
            if (candidate == stale || candidate.owners.isEmpty()) {
                continue;
            }
            if (candidate.key.worldId().equals(stale.key.worldId())
                && candidate.key.chunkX() == stale.key.chunkX()
                && candidate.key.chunkZ() == stale.key.chunkZ()) {
                return candidate;
            }
        }
        return null;
    }

    private boolean isCurrent(LeaseRecord record, long revision, Phase phase) {
        return records.get(record.key) == record && record.revision == revision && record.phase == phase;
    }

    private boolean isCurrentAdd(LeaseRecord record, long revision) {
        return records.get(record.key) == record
            && record.revision == revision
            && (record.phase == Phase.ADDING || record.phase == Phase.IDLE_ADDING);
    }

    private void terminalize(LeaseRecord record) {
        for (ChunkLease lease : record.owners.values()) {
            ownerRecords.remove(lease.ownerToken(), record);
            lease.terminalize();
        }
        record.owners.clear();
    }

    public record Options(long idleDelayMillis, long retryDelayMillis, int maxAttempts) {
        public Options {
            if (idleDelayMillis < 0L) {
                throw new IllegalArgumentException("idleDelayMillis must not be negative");
            }
            if (retryDelayMillis < 0L) {
                throw new IllegalArgumentException("retryDelayMillis must not be negative");
            }
            if (maxAttempts < 1) {
                throw new IllegalArgumentException("maxAttempts must be positive");
            }
        }
    }

    private record ChunkKey(UUID worldId, long worldEpoch, int chunkX, int chunkZ) {
    }

    private enum Phase {
        ADDING,
        RETRY_ADD,
        PRESENT,
        IDLE_ADDING,
        IDLE_CLEANUP,
        IDLE_PRESENT,
        REMOVING,
        RETRY_REMOVE
    }

    private final class LeaseRecord {
        private final ChunkKey key;
        private final W world;
        private final Map<UUID, ChunkLease> owners;
        private long revision;
        private long idleRevision;
        private Phase phase;

        private LeaseRecord(ChunkKey key, W world) {
            this.key = key;
            this.world = world;
            this.owners = new HashMap<>();
            this.revision = 0L;
            this.idleRevision = 0L;
            this.phase = Phase.ADDING;
        }
    }

    private static final class SerializedOwner {
        private final Queue<Runnable> commands = new ArrayDeque<>();
        private boolean draining;

        private void execute(Runnable command) {
            synchronized (commands) {
                commands.offer(command);
                if (draining) {
                    return;
                }
                draining = true;
            }
            drain();
        }

        private void drain() {
            Throwable escaped = null;
            while (true) {
                Runnable command;
                synchronized (commands) {
                    command = commands.poll();
                    if (command == null) {
                        draining = false;
                        break;
                    }
                }
                try {
                    command.run();
                } catch (RuntimeException | Error failure) {
                    if (escaped == null) {
                        escaped = failure;
                    } else {
                        escaped.addSuppressed(failure);
                    }
                }
            }
            if (escaped instanceof Error error) {
                throw error;
            }
            if (escaped instanceof RuntimeException runtime) {
                throw runtime;
            }
        }
    }
}
