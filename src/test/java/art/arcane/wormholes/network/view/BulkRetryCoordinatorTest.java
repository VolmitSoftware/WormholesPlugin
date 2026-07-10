package art.arcane.wormholes.network.view;

import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertFalse;
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
}
