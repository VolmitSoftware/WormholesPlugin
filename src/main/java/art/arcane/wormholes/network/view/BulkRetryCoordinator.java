package art.arcane.wormholes.network.view;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

final class BulkRetryCoordinator<K>
{
	@FunctionalInterface
	interface RetryScheduler
	{
		boolean schedule(Runnable retry, long delayTicks);
	}

	private final long maxDelayTicks;
	private final Map<K, CompletableFuture<Boolean>> active = new ConcurrentHashMap<>();

	BulkRetryCoordinator(long maxDelayTicks)
	{
		this.maxDelayTicks = Math.max(1L, maxDelayTicks);
	}

	CompletableFuture<Boolean> run(K key, BooleanSupplier stillActive, Supplier<CompletableFuture<Boolean>> attempt, RetryScheduler scheduler)
	{
		CompletableFuture<Boolean> existing = active.get(key);
		if(existing != null)
		{
			return existing;
		}
		CompletableFuture<Boolean> result = new CompletableFuture<>();
		CompletableFuture<Boolean> prior = active.putIfAbsent(key, result);
		if(prior != null)
		{
			return prior;
		}
		attempt(stillActive, attempt, scheduler, result, 0);
		result.whenComplete((accepted, error) -> active.remove(key, result));
		return result;
	}

	void clear()
	{
		for(CompletableFuture<Boolean> result : active.values())
		{
			result.complete(false);
		}
		active.clear();
	}

	private void attempt(BooleanSupplier stillActive, Supplier<CompletableFuture<Boolean>> attempt,
	                     RetryScheduler scheduler, CompletableFuture<Boolean> result, int attemptNumber)
	{
		if(result.isDone())
		{
			return;
		}
		if(!stillActive.getAsBoolean())
		{
			result.complete(false);
			return;
		}
		CompletableFuture<Boolean> attemptResult;
		try
		{
			attemptResult = attempt.get();
		}
		catch(Throwable error)
		{
			attemptResult = CompletableFuture.failedFuture(error);
		}
		attemptResult.whenComplete((accepted, error) ->
		{
			if(Boolean.TRUE.equals(accepted))
			{
				result.complete(true);
				return;
			}
			if(!stillActive.getAsBoolean())
			{
				result.complete(false);
				return;
			}
			long delayTicks = Math.min(maxDelayTicks, 1L << Math.min(attemptNumber, 5));
			boolean scheduled = scheduler.schedule(
				() -> attempt(stillActive, attempt, scheduler, result, attemptNumber + 1), delayTicks);
			if(!scheduled)
			{
				result.complete(false);
			}
		});
	}
}
