package art.arcane.wormholes.network.view;

import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BulkRetryCoordinatorTest
{
	@Test
	void rejectedFirstAttemptDelaysCompletionUntilRetryIsAccepted()
	{
		BulkRetryCoordinator<String> coordinator = new BulkRetryCoordinator<>(40L);
		AtomicInteger attempts = new AtomicInteger();
		Queue<Runnable> scheduled = new ArrayDeque<>();
		CompletableFuture<Boolean> result = coordinator.run(
			"peer:chunk",
			() -> true,
			() -> CompletableFuture.completedFuture(attempts.incrementAndGet() > 1),
			(retry, delayTicks) -> scheduled.offer(retry)
		);

		assertFalse(result.isDone());
		assertFalse(scheduled.isEmpty());
		scheduled.remove().run();
		assertTrue(result.join());
		assertTrue(scheduled.isEmpty());
	}

	@Test
	void inactiveSubscriptionStopsRetryWithoutAcceptance()
	{
		BulkRetryCoordinator<String> coordinator = new BulkRetryCoordinator<>(40L);
		CompletableFuture<Boolean> result = coordinator.run(
			"peer:chunk",
			() -> false,
			() -> CompletableFuture.completedFuture(true),
			(retry, delayTicks) -> true
		);

		assertFalse(result.join());
	}

	@Test
	void differentBulkGenerationsDoNotCoalesce()
	{
		BulkRetryCoordinator<ViewServer.BulkRetryKey> coordinator = new BulkRetryCoordinator<>(40L);
		UUID subscriptionId = UUID.randomUUID();
		ViewServer.BulkRetryKey oldGeneration = new ViewServer.BulkRetryKey(subscriptionId, "peer", 7L, 0L);
		ViewServer.BulkRetryKey newGeneration = new ViewServer.BulkRetryKey(subscriptionId, "peer", 7L, 1L);
		CompletableFuture<Boolean> oldAttempt = new CompletableFuture<>();
		AtomicInteger attempts = new AtomicInteger();

		CompletableFuture<Boolean> oldResult = coordinator.run(
			oldGeneration,
			() -> true,
			() -> {
				attempts.incrementAndGet();
				return oldAttempt;
			},
			(retry, delayTicks) -> true
		);
		CompletableFuture<Boolean> newResult = coordinator.run(
			newGeneration,
			() -> true,
			() -> {
				attempts.incrementAndGet();
				return CompletableFuture.completedFuture(true);
			},
			(retry, delayTicks) -> true
		);

		assertNotSame(oldResult, newResult);
		assertTrue(newResult.join());
		assertEquals(2, attempts.get());
		oldAttempt.complete(true);
		assertTrue(oldResult.join());
	}
}
