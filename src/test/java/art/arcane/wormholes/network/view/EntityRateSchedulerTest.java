package art.arcane.wormholes.network.view;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EntityRateSchedulerTest {
    private static EntityRateScheduler.Bands defaultBands() {
        return new EntityRateScheduler.Bands(16.0D, 64.0D, 128.0D, 20.0D, 10.0D, 4.0D, 1.0D);
    }

    private static int countSends(EntityRateScheduler scheduler, String subscriber, UUID entity,
                                  double subscriberX, double subscriberY, double subscriberZ,
                                  double entityX, double entityY, double entityZ,
                                  int simulatedTicks) {
        int sends = 0;
        for (int tick = 0; tick < simulatedTicks; tick++) {
            if (scheduler.shouldSend(subscriber, entity, subscriberX, subscriberY, subscriberZ, entityX, entityY, entityZ, tick)) {
                sends++;
            }
        }
        return sends;
    }

    @Test
    void nearBandSendsAtTwentyHzOverHundredTicks() {
        EntityRateScheduler scheduler = new EntityRateScheduler(defaultBands());
        UUID entity = new UUID(1L, 1L);
        int sends = countSends(scheduler, "sub-near", entity, 0.0D, 0.0D, 0.0D, 8.0D, 0.0D, 0.0D, 100);
        assertTrue(sends >= 90 && sends <= 100, "expected ~100 sends at near range, got " + sends);
    }

    @Test
    void midBandSendsAroundTenHzOverHundredTicks() {
        EntityRateScheduler scheduler = new EntityRateScheduler(defaultBands());
        UUID entity = new UUID(2L, 2L);
        int sends = countSends(scheduler, "sub-mid", entity, 0.0D, 0.0D, 0.0D, 50.0D, 0.0D, 0.0D, 100);
        assertTrue(sends >= 40 && sends <= 60, "expected ~50 sends in mid band, got " + sends);
    }

    @Test
    void farBandSendsAroundFourHzOverHundredTicks() {
        EntityRateScheduler scheduler = new EntityRateScheduler(defaultBands());
        UUID entity = new UUID(3L, 3L);
        int sends = countSends(scheduler, "sub-far", entity, 0.0D, 0.0D, 0.0D, 80.0D, 0.0D, 0.0D, 100);
        assertTrue(sends >= 15 && sends <= 30, "expected ~20 sends in far band, got " + sends);
    }

    @Test
    void veryFarBandSendsAroundOneHzOverHundredTicks() {
        EntityRateScheduler scheduler = new EntityRateScheduler(defaultBands());
        UUID entity = new UUID(4L, 4L);
        int sends = countSends(scheduler, "sub-vf", entity, 0.0D, 0.0D, 0.0D, 200.0D, 0.0D, 0.0D, 100);
        assertTrue(sends >= 3 && sends <= 8, "expected ~5 sends very far, got " + sends);
    }

    @Test
    void intervalTicksForIsPureAndDoesNotMutateState() {
        EntityRateScheduler scheduler = new EntityRateScheduler(defaultBands());
        long first = scheduler.intervalTicksFor(0.0D, 0.0D, 0.0D, 8.0D, 0.0D, 0.0D);
        long second = scheduler.intervalTicksFor(0.0D, 0.0D, 0.0D, 8.0D, 0.0D, 0.0D);
        assertEquals(first, second);
        assertEquals(0, scheduler.trackedEntityCount());
    }

    @Test
    void clearSubscriberRemovesEntries() {
        EntityRateScheduler scheduler = new EntityRateScheduler(defaultBands());
        UUID first = new UUID(10L, 1L);
        UUID second = new UUID(10L, 2L);
        scheduler.shouldSend("alpha", first, 0.0D, 0.0D, 0.0D, 8.0D, 0.0D, 0.0D, 0L);
        scheduler.shouldSend("alpha", second, 0.0D, 0.0D, 0.0D, 8.0D, 0.0D, 0.0D, 0L);
        scheduler.shouldSend("beta", first, 0.0D, 0.0D, 0.0D, 8.0D, 0.0D, 0.0D, 0L);
        assertEquals(3, scheduler.trackedEntityCount());
        scheduler.clearSubscriber("alpha");
        assertEquals(1, scheduler.trackedEntityCount());
    }

    @Test
    void clearEntityRemovesOnlyTargetEntry() {
        EntityRateScheduler scheduler = new EntityRateScheduler(defaultBands());
        UUID entity = new UUID(20L, 1L);
        scheduler.shouldSend("gamma", entity, 0.0D, 0.0D, 0.0D, 8.0D, 0.0D, 0.0D, 0L);
        assertEquals(1, scheduler.trackedEntityCount());
        scheduler.clearEntity("gamma", entity);
        assertEquals(0, scheduler.trackedEntityCount());
    }

    @Test
    void clearAllResetsAllSubscribers() {
        EntityRateScheduler scheduler = new EntityRateScheduler(defaultBands());
        for (int i = 0; i < 5; i++) {
            scheduler.shouldSend("sub-" + i, new UUID((long) i, (long) i), 0.0D, 0.0D, 0.0D, 8.0D, 0.0D, 0.0D, 0L);
        }
        assertEquals(5, scheduler.trackedEntityCount());
        scheduler.clearAll();
        assertEquals(0, scheduler.trackedEntityCount());
    }

    @Test
    void intervalShrinksAsDistanceIncreases() {
        EntityRateScheduler scheduler = new EntityRateScheduler(defaultBands());
        long nearInterval = scheduler.intervalTicksFor(0.0D, 0.0D, 0.0D, 5.0D, 0.0D, 0.0D);
        long midInterval = scheduler.intervalTicksFor(0.0D, 0.0D, 0.0D, 50.0D, 0.0D, 0.0D);
        long farInterval = scheduler.intervalTicksFor(0.0D, 0.0D, 0.0D, 100.0D, 0.0D, 0.0D);
        long veryFarInterval = scheduler.intervalTicksFor(0.0D, 0.0D, 0.0D, 200.0D, 0.0D, 0.0D);
        assertTrue(nearInterval < midInterval, "near " + nearInterval + " should be less than mid " + midInterval);
        assertTrue(midInterval < farInterval, "mid " + midInterval + " should be less than far " + farInterval);
        assertTrue(farInterval < veryFarInterval, "far " + farInterval + " should be less than very-far " + veryFarInterval);
    }
}
