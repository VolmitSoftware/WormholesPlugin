package art.arcane.wormholes.chunk;

import java.util.concurrent.CompletionStage;

public interface ChunkLeasePlatform<W> {
    CompletionStage<Boolean> add(W world, int chunkX, int chunkZ);

    CompletionStage<Boolean> remove(W world, int chunkX, int chunkZ);

    boolean schedule(Runnable command, long delayMillis);

    void reportFailure(Throwable error);
}
