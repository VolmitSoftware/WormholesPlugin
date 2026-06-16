package art.arcane.wormholes.network.view;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RemoteViewReadyTest {
    @Test
    void newViewIsNotReady() {
        RemoteViewCache cache = new RemoteViewCache();
        UUID portalId = UUID.randomUUID();
        cache.getOrCreate("hub", portalId);
        assertFalse(cache.isViewReady(portalId));
    }

    @Test
    void markViewReadyFlipsFlagOnAndIsStickyAcrossUpdates() {
        RemoteViewCache cache = new RemoteViewCache();
        UUID portalId = UUID.randomUUID();
        cache.getOrCreate("hub", portalId);
        cache.markViewReady("hub", portalId);
        assertTrue(cache.isViewReady(portalId));
        RemoteViewCache.RemoteView view = cache.get("hub", portalId);
        assertTrue(view.isViewReady());

        cache.applyTime("hub", portalId, 4);
        assertTrue(cache.isViewReady(portalId));
        assertTrue(view.isViewReady());
    }

    @Test
    void readyFlagDropsOnUnsubscribeAndCacheRemove() {
        RemoteViewCache cache = new RemoteViewCache();
        UUID portalId = UUID.randomUUID();
        cache.markViewReady("hub", portalId);
        assertTrue(cache.isViewReady(portalId));
        cache.remove("hub", portalId);
        assertFalse(cache.isViewReady(portalId));
    }

    @Test
    void readyFlagDoesNotCrossPortals() {
        RemoteViewCache cache = new RemoteViewCache();
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        cache.markViewReady("hub", first);
        assertTrue(cache.isViewReady(first));
        assertFalse(cache.isViewReady(second));
    }
}
