package art.arcane.wormholes.network;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlayerHandoffRateLimiterTest {
    @Test
    void allowsAtExactCooldownBoundary() {
        PlayerHandoffRateLimiter limiter = new PlayerHandoffRateLimiter();
        UUID playerId = UUID.randomUUID();

        assertTrue(limiter.acquire(playerId, 1_000L, 1_000L).allowed());
        PlayerHandoffRateLimiter.Decision blocked = limiter.acquire(playerId, 1_999L, 1_000L);
        assertFalse(blocked.allowed());
        assertEquals(1L, blocked.retryAfterMillis());
        assertTrue(limiter.acquire(playerId, 2_000L, 1_000L).allowed());
    }

    @Test
    void penaltyExtendsButNeverShortensCooldown() {
        PlayerHandoffRateLimiter limiter = new PlayerHandoffRateLimiter();
        UUID playerId = UUID.randomUUID();

        limiter.acquire(playerId, 1_000L, 1_000L);
        limiter.penalize(playerId, 1_500L, 1_000L);
        limiter.penalize(playerId, 1_600L, 100L);

        assertEquals(500L, limiter.remaining(playerId, 2_000L));
        assertEquals(0L, limiter.remaining(playerId, 2_500L));
    }

    @Test
    void playersHaveIndependentLimits() {
        PlayerHandoffRateLimiter limiter = new PlayerHandoffRateLimiter();

        assertTrue(limiter.acquire(UUID.randomUUID(), 0L, 1_000L).allowed());
        assertTrue(limiter.acquire(UUID.randomUUID(), 0L, 1_000L).allowed());
    }

    @Test
    void simultaneousAttemptsGrantOneLease() throws Exception {
        PlayerHandoffRateLimiter limiter = new PlayerHandoffRateLimiter();
        UUID playerId = UUID.randomUUID();
        int attempts = 16;
        ExecutorService executor = Executors.newFixedThreadPool(attempts);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger accepted = new AtomicInteger();
        List<Future<?>> futures = new ArrayList<>();
        try {
            for (int i = 0; i < attempts; i++) {
                futures.add(executor.submit(() -> {
                    start.await();
                    if (limiter.acquire(playerId, 10_000L, 1_000L).allowed()) {
                        accepted.incrementAndGet();
                    }
                    return null;
                }));
            }
            start.countDown();
            for (Future<?> future : futures) {
                future.get();
            }
        } finally {
            executor.shutdownNow();
        }

        assertEquals(1, accepted.get());
    }

    @Test
    void rejectsMissingPlayerIdentity() {
        PlayerHandoffRateLimiter limiter = new PlayerHandoffRateLimiter();

        assertThrows(NullPointerException.class, () -> limiter.acquire(null, 0L, 1_000L));
    }
}
