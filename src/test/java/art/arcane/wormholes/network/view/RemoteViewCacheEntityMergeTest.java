package art.arcane.wormholes.network.view;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RemoteViewCacheEntityMergeTest {
    private static final String PEER = "peer-a";

    private static EntityVisual fullEntity(UUID id, double x) {
        return EntityVisual.full(
            id, "minecraft:zombie",
            x, 64.0D, 0.0D, 1.95D,
            0.0D, 0.0D, 1.0D,
            0.0F, 0.0F,
            0.0D, 0.0D, 0.0D,
            true,
            "", "", "",
            null,
            new byte[0], new byte[0],
            1);
    }

    private static Set<UUID> idsOf(RemoteViewCache.RemoteView view) {
        return view.getEntities().stream().map(EntityVisual::id).collect(Collectors.toSet());
    }

    @Test
    void rateLimitedSubsetDoesNotPruneEntitiesStillPresent() {
        RemoteViewCache cache = new RemoteViewCache();
        UUID portalId = UUID.randomUUID();
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        RemoteViewCache.RemoteView view = cache.getOrCreate(PEER, portalId);

        cache.applyEntities(PEER, portalId, List.of(fullEntity(a, 1.0D), fullEntity(b, 2.0D)), List.of(a, b));
        assertEquals(Set.of(a, b), idsOf(view));

        // Next capture sends only A (rate-limited), but B is still present in the authoritative set.
        cache.applyEntities(PEER, portalId, List.of(fullEntity(a, 1.5D)), List.of(a, b));
        assertEquals(Set.of(a, b), idsOf(view), "entity B must survive a batch that omits it while still present");
    }

    @Test
    void entityAbsentFromPresentSetIsPruned() {
        RemoteViewCache cache = new RemoteViewCache();
        UUID portalId = UUID.randomUUID();
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        RemoteViewCache.RemoteView view = cache.getOrCreate(PEER, portalId);

        cache.applyEntities(PEER, portalId, List.of(fullEntity(a, 1.0D), fullEntity(b, 2.0D)), List.of(a, b));
        assertEquals(Set.of(a, b), idsOf(view));

        // B left the view box: authoritative present set no longer contains it, even though no entity payload is sent.
        cache.applyEntities(PEER, portalId, List.of(), List.of(a));
        assertEquals(Set.of(a), idsOf(view));
    }

    @Test
    void emptyPresentSetClearsAllEntities() {
        RemoteViewCache cache = new RemoteViewCache();
        UUID portalId = UUID.randomUUID();
        UUID a = UUID.randomUUID();
        RemoteViewCache.RemoteView view = cache.getOrCreate(PEER, portalId);

        cache.applyEntities(PEER, portalId, List.of(fullEntity(a, 1.0D)), List.of(a));
        assertTrue(idsOf(view).contains(a));

        cache.applyEntities(PEER, portalId, List.of(), List.of());
        assertTrue(view.getEntities().isEmpty());
    }
}
