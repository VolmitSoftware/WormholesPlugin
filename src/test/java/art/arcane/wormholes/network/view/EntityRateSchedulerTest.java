package art.arcane.wormholes.network.view;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EntityRateSchedulerTest {
    private static EntityRateScheduler.Bands defaultBands() {
        return new EntityRateScheduler.Bands(16.0D, 64.0D, 128.0D, 20.0D, 10.0D, 4.0D, 1.0D);
    }

    private static int countSends(EntityRateScheduler scheduler, double distance, int simulatedTicks) {
        double distanceSquared = distance * distance;
        int sends = 0;
        long nextEligible = 0L;
        for (int tick = 0; tick < simulatedTicks; tick++) {
            if (tick >= nextEligible) {
                sends++;
                nextEligible = tick + scheduler.claimSendInterval(distanceSquared);
            }
        }
        return sends;
    }

    @Test
    void nearBandSendsAtTwentyHzOverHundredTicks() {
        EntityRateScheduler scheduler = new EntityRateScheduler(defaultBands());
        int sends = countSends(scheduler, 8.0D, 100);
        assertTrue(sends >= 90 && sends <= 100, "expected ~100 sends at near range, got " + sends);
    }

    @Test
    void midBandSendsAroundTenHzOverHundredTicks() {
        EntityRateScheduler scheduler = new EntityRateScheduler(defaultBands());
        int sends = countSends(scheduler, 50.0D, 100);
        assertTrue(sends >= 40 && sends <= 60, "expected ~50 sends in mid band, got " + sends);
    }

    @Test
    void farBandSendsAroundFourHzOverHundredTicks() {
        EntityRateScheduler scheduler = new EntityRateScheduler(defaultBands());
        int sends = countSends(scheduler, 80.0D, 100);
        assertTrue(sends >= 15 && sends <= 30, "expected ~20 sends in far band, got " + sends);
    }

    @Test
    void veryFarBandSendsAroundOneHzOverHundredTicks() {
        EntityRateScheduler scheduler = new EntityRateScheduler(defaultBands());
        int sends = countSends(scheduler, 200.0D, 100);
        assertTrue(sends >= 3 && sends <= 8, "expected ~5 sends very far, got " + sends);
    }

    @Test
    void intervalShrinksAsDistanceIncreases() {
        EntityRateScheduler scheduler = new EntityRateScheduler(defaultBands());
        long nearInterval = scheduler.claimSendInterval(5.0D * 5.0D);
        long midInterval = scheduler.claimSendInterval(50.0D * 50.0D);
        long farInterval = scheduler.claimSendInterval(100.0D * 100.0D);
        long veryFarInterval = scheduler.claimSendInterval(200.0D * 200.0D);
        assertTrue(nearInterval < midInterval, "near " + nearInterval + " should be less than mid " + midInterval);
        assertTrue(midInterval < farInterval, "mid " + midInterval + " should be less than far " + farInterval);
        assertTrue(farInterval < veryFarInterval, "far " + farInterval + " should be less than very-far " + veryFarInterval);
    }

    @Test
    void claimSendIntervalIncrementsMatchingBandStat() {
        EntityRateScheduler scheduler = new EntityRateScheduler(defaultBands());
        scheduler.claimSendInterval(8.0D * 8.0D);
        scheduler.claimSendInterval(50.0D * 50.0D);
        scheduler.claimSendInterval(80.0D * 80.0D);
        scheduler.claimSendInterval(200.0D * 200.0D);
        EntityRateScheduler.Stats stats = scheduler.snapshot();
        assertEquals(1L, stats.sendNear());
        assertEquals(1L, stats.sendMid());
        assertEquals(1L, stats.sendFar());
        assertEquals(1L, stats.sendVeryFar());
        assertEquals(4L, stats.total());
    }

    @Test
    void entitySendStateStartsEligibleAtTickZero() {
        EntitySendState state = new EntitySendState(new UUID(30L, 1L));
        assertEquals(0L, state.getNextEligibleTick());
        assertTrue(0L >= state.getNextEligibleTick());
    }

    @Test
    void rateDeniedTickDoesNotAdvanceSchedule() {
        EntityRateScheduler scheduler = new EntityRateScheduler(defaultBands());
        EntitySendState state = new EntitySendState(new UUID(31L, 1L));
        state.setNextEligibleTick(10L);
        for (long tick = 5L; tick < 10L; tick++) {
            assertFalse(tick >= state.getNextEligibleTick());
        }
        assertEquals(10L, state.getNextEligibleTick());
        long tick = 10L;
        assertTrue(tick >= state.getNextEligibleTick());
        state.setNextEligibleTick(tick + scheduler.claimSendInterval(8.0D * 8.0D));
        assertEquals(11L, state.getNextEligibleTick());
    }
}
