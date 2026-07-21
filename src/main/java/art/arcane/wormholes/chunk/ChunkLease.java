package art.arcane.wormholes.chunk;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

public final class ChunkLease implements AutoCloseable {
    private final UUID ownerToken;
    private final CompletableFuture<Boolean> readiness;
    private final AtomicBoolean live;
    private final AtomicReference<Consumer<ChunkLease>> release;

    ChunkLease(UUID ownerToken, Consumer<ChunkLease> release) {
        this.ownerToken = ownerToken;
        this.readiness = new CompletableFuture<>();
        this.live = new AtomicBoolean(true);
        this.release = new AtomicReference<>(release);
    }

    public CompletableFuture<Boolean> ready() {
        return readiness;
    }

    @Override
    public void close() {
        if (!live.compareAndSet(true, false)) {
            return;
        }
        readiness.complete(false);
        Consumer<ChunkLease> releaseAction = release.getAndSet(null);
        if (releaseAction != null) {
            releaseAction.accept(this);
        }
    }

    UUID ownerToken() {
        return ownerToken;
    }

    public boolean isValid() {
        return live.get();
    }

    void completeReady() {
        if (live.get()) {
            readiness.complete(true);
        }
    }

    void terminalize() {
        live.set(false);
        release.set(null);
        readiness.complete(false);
    }
}
