package art.arcane.wormholes.portal.rtp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

public final class RtpTraversalRequestTest
{
	@Test
	public void requestStartsPreparingAndDispatchesBeforeSuccess()
	{
		AtomicInteger cleanupCount = new AtomicInteger();
		RtpTraversalRequest request = RtpTraversalRequest.preparing(41L, cleanupCount::incrementAndGet);

		assertEquals(41L, request.generation());
		assertEquals(RtpTraversalRequest.State.PREPARING, request.state());
		assertFalse(request.isTerminal());
		assertTrue(request.markDispatched(41L));
		assertEquals(RtpTraversalRequest.State.DISPATCHED, request.state());
		assertTrue(request.markSucceeded(41L));
		assertEquals(RtpTraversalRequest.State.SUCCEEDED, request.state());
		assertTrue(request.isTerminal());
		assertEquals(1, cleanupCount.get());
	}

	@Test
	public void dispatchedRequestCanFailAndCleansUpOnce()
	{
		AtomicInteger cleanupCount = new AtomicInteger();
		RtpTraversalRequest request = RtpTraversalRequest.preparing(7L, cleanupCount::incrementAndGet);

		assertTrue(request.markDispatched(7L));
		assertTrue(request.markFailed(7L));
		assertFalse(request.markFailed(7L));
		assertFalse(request.markSucceeded(7L));
		assertEquals(RtpTraversalRequest.State.FAILED, request.state());
		assertEquals(1, cleanupCount.get());
	}

	@Test
	public void preparingRequestCanTimeOutOrCancel()
	{
		AtomicInteger timeoutCleanup = new AtomicInteger();
		RtpTraversalRequest timedOut = RtpTraversalRequest.preparing(9L, timeoutCleanup::incrementAndGet);
		AtomicInteger cancelCleanup = new AtomicInteger();
		RtpTraversalRequest cancelled = RtpTraversalRequest.preparing(9L, cancelCleanup::incrementAndGet);

		assertTrue(timedOut.timeoutPreparing(9L));
		assertEquals(RtpTraversalRequest.State.TIMED_OUT, timedOut.state());
		assertEquals(1, timeoutCleanup.get());
		assertTrue(cancelled.cancel(9L));
		assertEquals(RtpTraversalRequest.State.CANCELLED, cancelled.state());
		assertEquals(1, cancelCleanup.get());
	}

	@Test
	public void dispatchedTimeoutReconcilesToSuccessOrFailure()
	{
		AtomicInteger successCleanup = new AtomicInteger();
		RtpTraversalRequest successful = RtpTraversalRequest.preparing(13L, successCleanup::incrementAndGet);
		AtomicInteger failureCleanup = new AtomicInteger();
		RtpTraversalRequest failed = RtpTraversalRequest.preparing(13L, failureCleanup::incrementAndGet);

		assertTrue(successful.markDispatched(13L));
		assertTrue(successful.reconcileDispatchedTimeout(13L, true));
		assertEquals(RtpTraversalRequest.State.SUCCEEDED, successful.state());
		assertEquals(1, successCleanup.get());
		assertTrue(failed.markDispatched(13L));
		assertTrue(failed.reconcileDispatchedTimeout(13L, false));
		assertEquals(RtpTraversalRequest.State.FAILED, failed.state());
		assertEquals(1, failureCleanup.get());
	}

	@Test
	public void invalidGraphEdgesAreRejected()
	{
		RtpTraversalRequest preparing = RtpTraversalRequest.preparing(21L, () -> {
		});
		RtpTraversalRequest dispatched = RtpTraversalRequest.preparing(21L, () -> {
		});

		assertFalse(preparing.markSucceeded(21L));
		assertFalse(preparing.markFailed(21L));
		assertFalse(preparing.reconcileDispatchedTimeout(21L, true));
		assertTrue(dispatched.markDispatched(21L));
		assertFalse(dispatched.timeoutPreparing(21L));
		assertFalse(dispatched.cancel(21L));
		assertFalse(dispatched.markDispatched(21L));
	}

	@Test
	public void staleGenerationCannotAdvanceOrTerminalizeRequest()
	{
		AtomicInteger cleanupCount = new AtomicInteger();
		RtpTraversalRequest request = RtpTraversalRequest.preparing(34L, cleanupCount::incrementAndGet);

		assertFalse(request.markDispatched(35L));
		assertFalse(request.markSucceeded(35L));
		assertFalse(request.markFailed(35L));
		assertFalse(request.timeoutPreparing(35L));
		assertFalse(request.cancel(35L));
		assertFalse(request.reconcileDispatchedTimeout(35L, true));
		assertEquals(RtpTraversalRequest.State.PREPARING, request.state());
		assertEquals(0, cleanupCount.get());
	}

	@Test
	public void concurrentTerminalAttemptsRunCleanupExactlyOnce() throws Exception
	{
		AtomicInteger cleanupCount = new AtomicInteger();
		RtpTraversalRequest request = RtpTraversalRequest.preparing(55L, cleanupCount::incrementAndGet);
		assertTrue(request.markDispatched(55L));
		ExecutorService executor = Executors.newFixedThreadPool(8);
		List<Callable<Boolean>> attempts = new ArrayList<>();
		for (int index = 0; index < 64; index++)
		{
			int operation = index % 3;
			attempts.add(() -> switch (operation)
			{
				case 0 -> request.markSucceeded(55L);
				case 1 -> request.markFailed(55L);
				default -> request.reconcileDispatchedTimeout(55L, true);
			});
		}

		List<Future<Boolean>> results = executor.invokeAll(attempts);
		executor.shutdown();
		assertTrue(executor.awaitTermination(5L, TimeUnit.SECONDS));
		int successfulTransitions = 0;
		for (Future<Boolean> result : results)
		{
			if (result.get())
			{
				successfulTransitions++;
			}
		}

		assertEquals(1, successfulTransitions);
		assertTrue(request.state() == RtpTraversalRequest.State.SUCCEEDED || request.state() == RtpTraversalRequest.State.FAILED);
		assertEquals(1, cleanupCount.get());
	}
}
