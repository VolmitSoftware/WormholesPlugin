package art.arcane.wormholes.chunk;

import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChunkLeaseRegistryTest {
    private static final UUID WORLD_ID = UUID.fromString("10000000-0000-0000-0000-000000000001");
    private static final TestWorld WORLD = new TestWorld(WORLD_ID, "initial");

    @Test
    void addFalseMeansExistingPluginTicketIsReady() {
        ManualPlatform platform = new ManualPlatform();
        platform.addResults.offer(CompletableFuture.completedFuture(false));
        ChunkLeaseRegistry<TestWorld> registry = registry(platform);

        ChunkLease lease = registry.retain(WORLD, WORLD_ID, 4, -7);

        assertTrue(lease.ready().join());
        assertEquals(1, platform.addCalls);
    }

    @Test
    void overlappingOwnersShareOneTicketAndReleaseOnlyTheirOwnLease() {
        ManualPlatform platform = new ManualPlatform();
        ChunkLeaseRegistry<TestWorld> registry = registry(platform);

        ChunkLease first = registry.retain(WORLD, WORLD_ID, 1, 2);
        ChunkLease second = registry.retain(WORLD, WORLD_ID, 1, 2);

        assertTrue(first.ready().join());
        assertTrue(second.ready().join());
        assertEquals(1, platform.addCalls);

        first.close();
        assertEquals(0, platform.scheduled.size());
        assertEquals(0, platform.removeCalls);
        assertTrue(second.ready().join());

        second.close();
        assertEquals(1, platform.scheduled.size());
        platform.runNextScheduled();
        assertEquals(1, platform.removeCalls);
    }

    @Test
    void concurrentFirstOwnersSerializeIntoOnePhysicalAdd() throws Exception {
        ManualPlatform platform = new ManualPlatform();
        ChunkLeaseRegistry<TestWorld> registry = registry(platform);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<ChunkLease> firstFuture = executor.submit(() -> {
                start.await();
                return registry.retain(WORLD, WORLD_ID, 2, 3);
            });
            Future<ChunkLease> secondFuture = executor.submit(() -> {
                start.await();
                return registry.retain(WORLD, WORLD_ID, 2, 3);
            });

            start.countDown();
            ChunkLease first = firstFuture.get(1L, TimeUnit.SECONDS);
            ChunkLease second = secondFuture.get(1L, TimeUnit.SECONDS);

            assertTrue(first.ready().get(1L, TimeUnit.SECONDS));
            assertTrue(second.ready().get(1L, TimeUnit.SECONDS));
            assertEquals(1, platform.addCalls);
            first.close();
            second.close();
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void failedAddRetriesWithoutReplacingTheReadinessFuture() {
        ManualPlatform platform = new ManualPlatform();
        platform.addResults.offer(CompletableFuture.failedFuture(new IllegalStateException("first add failed")));
        platform.addResults.offer(CompletableFuture.completedFuture(true));
        ChunkLeaseRegistry<TestWorld> registry = registry(platform);

        ChunkLease lease = registry.retain(WORLD, WORLD_ID, 12, 13);
        CompletableFuture<Boolean> readiness = lease.ready();

        assertFalse(readiness.isDone());
        assertEquals(1, platform.scheduled.size());
        platform.runNextScheduled();
        assertSame(readiness, lease.ready());
        assertTrue(readiness.join());
        assertEquals(2, platform.addCalls);
    }

    @Test
    void boundedAddRetriesTerminalizeFailedHandle() {
        ManualPlatform platform = new ManualPlatform();
        platform.addResults.offer(CompletableFuture.failedFuture(new IllegalStateException("attempt one")));
        platform.addResults.offer(CompletableFuture.failedFuture(new IllegalStateException("attempt two")));
        platform.addResults.offer(CompletableFuture.failedFuture(new IllegalStateException("attempt three")));
        ChunkLeaseRegistry<TestWorld> registry = registry(platform);
        ChunkLease lease = registry.retain(WORLD, WORLD_ID, 14, 15);

        platform.runNextScheduled();
        platform.runNextScheduled();

        assertFalse(lease.ready().join());
        assertEquals(3, platform.addCalls);
    }

    @Test
    void retainAfterTerminalAddFailureCancelsCleanupAndStartsFreshAdd() {
        ManualPlatform platform = new ManualPlatform();
        platform.addResults.offer(CompletableFuture.failedFuture(new IllegalStateException("attempt one")));
        platform.addResults.offer(CompletableFuture.failedFuture(new IllegalStateException("attempt two")));
        platform.addResults.offer(CompletableFuture.failedFuture(new IllegalStateException("attempt three")));
        platform.addResults.offer(CompletableFuture.completedFuture(true));
        ChunkLeaseRegistry<TestWorld> registry = registry(platform);
        ChunkLease failed = registry.retain(WORLD, WORLD_ID, 14, 16);

        platform.runNextScheduled();
        platform.runNextScheduled();
        assertFalse(failed.ready().join());

        ChunkLease fresh = registry.retain(WORLD, WORLD_ID, 14, 16);

        assertEquals(4, platform.addCalls);
        assertTrue(fresh.ready().join());
        platform.runNextScheduled();
        assertEquals(0, platform.removeCalls);
    }

    @Test
    void retrySchedulerRejectionTerminalizesFailedHandleAndClearsLogicalState() {
        ManualPlatform platform = new ManualPlatform();
        platform.rejectedSchedules = 2;
        platform.addResults.offer(CompletableFuture.failedFuture(new IllegalStateException("add failed")));
        ChunkLeaseRegistry<TestWorld> registry = registry(platform);

        ChunkLease failed = registry.retain(WORLD, WORLD_ID, 16, 17);

        assertFalse(failed.ready().join());
        assertEquals(1, platform.addCalls);
        assertFalse(platform.hasScheduled());

        ChunkLease fresh = registry.retain(WORLD, WORLD_ID, 16, 17);
        assertTrue(fresh.ready().join());
        assertEquals(2, platform.addCalls);
    }

    @Test
    void idleSchedulerRejectionFallsBackToExactOncePhysicalRemoval() {
        ManualPlatform platform = new ManualPlatform();
        platform.rejectedSchedules = 1;
        ChunkLeaseRegistry<TestWorld> registry = registry(platform);
        ChunkLease lease = registry.retain(WORLD, WORLD_ID, 5, 6);

        lease.close();
        lease.close();

        assertEquals(1, platform.removeCalls);
        assertFalse(platform.hasScheduled());
    }

    @Test
    void releaseBeforeReadyTerminalizesOnlyThatHandleAndCleansLateAdd() {
        ManualPlatform platform = new ManualPlatform();
        CompletableFuture<Boolean> pendingAdd = new CompletableFuture<>();
        platform.addResults.offer(pendingAdd);
        ChunkLeaseRegistry<TestWorld> registry = registry(platform);
        ChunkLease lease = registry.retain(WORLD, WORLD_ID, 6, 7);

        lease.close();

        assertFalse(lease.ready().join());
        platform.runNextScheduled();
        assertEquals(1, platform.removeCalls);

        pendingAdd.complete(true);
        assertEquals(2, platform.removeCalls);
    }

    @Test
    void failedRemovalRetriesBeforeLogicalCleanup() {
        ManualPlatform platform = new ManualPlatform();
        platform.removeResults.offer(CompletableFuture.failedFuture(new IllegalStateException("remove failed")));
        platform.removeResults.offer(CompletableFuture.completedFuture(true));
        ChunkLeaseRegistry<TestWorld> registry = registry(platform);
        ChunkLease lease = registry.retain(WORLD, WORLD_ID, 18, 19);
        lease.close();

        platform.runNextScheduled();
        assertEquals(1, platform.removeCalls);
        assertEquals(1, platform.scheduled.size());

        platform.runNextScheduled();
        assertEquals(2, platform.removeCalls);
        assertFalse(platform.hasScheduled());
    }

    @Test
    void removalRetrySchedulerRejectionRunsImmediateBoundedRetry() {
        ManualPlatform platform = new ManualPlatform();
        platform.removeResults.offer(CompletableFuture.failedFuture(new IllegalStateException("remove failed")));
        platform.removeResults.offer(CompletableFuture.completedFuture(true));
        platform.rejectScheduleCall = 2;
        ChunkLeaseRegistry<TestWorld> registry = registry(platform);
        ChunkLease lease = registry.retain(WORLD, WORLD_ID, 18, 21);
        lease.close();

        platform.runNextScheduled();

        assertEquals(2, platform.removeCalls);
        assertEquals(2, platform.reportedFailures);
        assertFalse(platform.hasScheduled());
    }

    @Test
    void unloadRemovalRetrySchedulerRejectionRunsImmediateBoundedRetry() {
        ManualPlatform platform = new ManualPlatform();
        platform.removeResults.offer(CompletableFuture.failedFuture(new IllegalStateException("unload remove failed")));
        platform.removeResults.offer(CompletableFuture.completedFuture(true));
        platform.rejectScheduleCall = 1;
        ChunkLeaseRegistry<TestWorld> registry = registry(platform);
        registry.retain(WORLD, WORLD_ID, 18, 22);

        registry.worldUnloaded(WORLD_ID);

        assertEquals(2, platform.removeCalls);
        assertEquals(2, platform.reportedFailures);
        assertFalse(platform.hasScheduled());
    }

    @Test
    void shutdownRemovalRetrySchedulerRejectionRunsImmediateBoundedRetry() {
        ManualPlatform platform = new ManualPlatform();
        platform.removeResults.offer(CompletableFuture.failedFuture(new IllegalStateException("shutdown remove failed")));
        platform.removeResults.offer(CompletableFuture.completedFuture(true));
        platform.rejectScheduleCall = 1;
        ChunkLeaseRegistry<TestWorld> registry = registry(platform);
        registry.retain(WORLD, WORLD_ID, 18, 23);

        registry.shutdown();

        assertEquals(2, platform.removeCalls);
        assertEquals(2, platform.reportedFailures);
        assertFalse(platform.hasScheduled());
    }

    @Test
    void boundedRemovalFailureClearsLogicalStateForFreshRetain() {
        ManualPlatform platform = new ManualPlatform();
        platform.removeResults.offer(CompletableFuture.failedFuture(new IllegalStateException("attempt one")));
        platform.removeResults.offer(CompletableFuture.failedFuture(new IllegalStateException("attempt two")));
        platform.removeResults.offer(CompletableFuture.failedFuture(new IllegalStateException("attempt three")));
        ChunkLeaseRegistry<TestWorld> registry = registry(platform);
        ChunkLease first = registry.retain(WORLD, WORLD_ID, 18, 20);
        first.close();

        platform.runNextScheduled();
        platform.runNextScheduled();
        platform.runNextScheduled();

        assertEquals(3, platform.removeCalls);
        assertFalse(platform.hasScheduled());
        ChunkLease fresh = registry.retain(WORLD, WORLD_ID, 18, 20);
        assertTrue(fresh.ready().join());
        assertEquals(2, platform.addCalls);
    }

    @Test
    void worldUnloadClearsOwnersAndUsesFreshWorldIncarnation() {
        ManualPlatform platform = new ManualPlatform();
        CompletableFuture<Boolean> oldAdd = new CompletableFuture<>();
        platform.addResults.offer(oldAdd);
        ChunkLeaseRegistry<TestWorld> registry = registry(platform);
        ChunkLease oldLease = registry.retain(WORLD, WORLD_ID, 20, 21);

        registry.worldUnloaded(WORLD_ID);

        assertFalse(oldLease.ready().join());
        assertEquals(1, platform.removeCalls);

        TestWorld reloadedWorld = new TestWorld(WORLD_ID, "reloaded");
        ChunkLease newLease = registry.retain(reloadedWorld, WORLD_ID, 20, 21);
        assertTrue(newLease.ready().join());
        assertEquals(2, platform.addCalls);

        oldAdd.complete(true);
        assertEquals(1, platform.removeCalls);
        assertTrue(newLease.ready().join());
    }

    @Test
    void worldUnloadInvalidatesLeaseAfterReadinessWasAlreadyTrue() {
        ManualPlatform platform = new ManualPlatform();
        ChunkLeaseRegistry<TestWorld> registry = registry(platform);
        ChunkLease lease = registry.retain(WORLD, WORLD_ID, 20, 22);
        assertTrue(lease.ready().join());
        assertTrue(lease.isValid());

        registry.worldUnloaded(WORLD_ID);

        assertFalse(lease.isValid());
    }

    @Test
    void detachedRemovalCompletionReconcilesReplacementRecord() {
        ManualPlatform platform = new ManualPlatform();
        CompletableFuture<Boolean> detachedRemoval = new CompletableFuture<>();
        platform.removeResults.offer(detachedRemoval);
        ChunkLeaseRegistry<TestWorld> registry = registry(platform);
        registry.retain(WORLD, WORLD_ID, 30, 31);

        registry.worldUnloaded(WORLD_ID);
        ChunkLease replacement = registry.retain(new TestWorld(WORLD_ID, "replacement"), WORLD_ID, 30, 31);
        assertTrue(replacement.ready().join());
        assertEquals(2, platform.addCalls);

        detachedRemoval.complete(true);

        assertEquals(3, platform.addCalls);
        assertTrue(replacement.isValid());
    }

    @Test
    void lateSuccessfulAddDoesNotRemoveTicketObservedByReplacementRecord() {
        ManualPlatform platform = new ManualPlatform();
        CompletableFuture<Boolean> oldAdd = new CompletableFuture<>();
        CompletableFuture<Boolean> replacementAdd = new CompletableFuture<>();
        platform.addResults.offer(oldAdd);
        platform.addResults.offer(replacementAdd);
        ChunkLeaseRegistry<TestWorld> registry = registry(platform);
        registry.retain(WORLD, WORLD_ID, 30, 32);

        registry.worldUnloaded(WORLD_ID);
        ChunkLease replacement = registry.retain(new TestWorld(WORLD_ID, "replacement"), WORLD_ID, 30, 32);
        oldAdd.complete(true);

        assertEquals(1, platform.removeCalls);
        replacementAdd.complete(false);
        assertTrue(replacement.ready().join());
        assertEquals(1, platform.removeCalls);
    }

    @Test
    void lateObservedAddFalseDoesNotStartRedundantDetachedRemoval() {
        ManualPlatform platform = new ManualPlatform();
        CompletableFuture<Boolean> oldAdd = new CompletableFuture<>();
        platform.addResults.offer(oldAdd);
        ChunkLeaseRegistry<TestWorld> registry = registry(platform);
        registry.retain(WORLD, WORLD_ID, 30, 33);

        registry.worldUnloaded(WORLD_ID);
        oldAdd.complete(false);

        assertEquals(1, platform.removeCalls);
    }

    @Test
    void worldUnloadReportsLateAddFailureWithoutRestoringLogicalState() {
        ManualPlatform platform = new ManualPlatform();
        CompletableFuture<Boolean> oldAdd = new CompletableFuture<>();
        platform.addResults.offer(oldAdd);
        ChunkLeaseRegistry<TestWorld> registry = registry(platform);
        ChunkLease oldLease = registry.retain(WORLD, WORLD_ID, 30, 31);

        registry.worldUnloaded(WORLD_ID);
        oldAdd.completeExceptionally(new IllegalStateException("late add failed"));

        assertFalse(oldLease.ready().join());
        assertEquals(1, platform.reportedFailures);
        ChunkLease fresh = registry.retain(new TestWorld(WORLD_ID, "later"), WORLD_ID, 30, 31);
        assertTrue(fresh.ready().join());
    }

    @Test
    void worldUnloadRetriesBestEffortPhysicalRemoval() {
        ManualPlatform platform = new ManualPlatform();
        platform.removeResults.offer(CompletableFuture.failedFuture(new IllegalStateException("unload remove failed")));
        platform.removeResults.offer(CompletableFuture.completedFuture(true));
        ChunkLeaseRegistry<TestWorld> registry = registry(platform);
        registry.retain(WORLD, WORLD_ID, 28, 29);

        registry.worldUnloaded(WORLD_ID);

        assertEquals(1, platform.removeCalls);
        assertEquals(1, platform.scheduled.size());
        platform.runNextScheduled();
        assertEquals(2, platform.removeCalls);
    }

    @Test
    void shutdownClearsOwnersAndRejectsNewRetains() {
        ManualPlatform platform = new ManualPlatform();
        ChunkLeaseRegistry<TestWorld> registry = registry(platform);
        ChunkLease present = registry.retain(WORLD, WORLD_ID, 22, 23);
        CompletableFuture<Boolean> pendingAdd = new CompletableFuture<>();
        platform.addResults.offer(pendingAdd);
        ChunkLease pending = registry.retain(WORLD, WORLD_ID, 24, 25);

        registry.shutdown();

        assertTrue(present.ready().join());
        assertFalse(pending.ready().join());
        assertEquals(2, platform.removeCalls);

        ChunkLease rejected = registry.retain(WORLD, WORLD_ID, 26, 27);
        assertFalse(rejected.ready().join());
        assertEquals(2, platform.addCalls);
    }

    @Test
    void duplicateCloseProducesExactlyOneRemoval() {
        ManualPlatform platform = new ManualPlatform();
        ChunkLeaseRegistry<TestWorld> registry = registry(platform);
        ChunkLease lease = registry.retain(WORLD, WORLD_ID, 8, 9);

        lease.close();
        lease.close();

        assertEquals(1, platform.scheduled.size());
        platform.runNextScheduled();
        assertEquals(1, platform.removeCalls);
        assertFalse(platform.hasScheduled());
    }

    @Test
    void retouchWhileAddIsPendingCancelsOriginalDelayedRemoval() {
        ManualPlatform platform = new ManualPlatform();
        CompletableFuture<Boolean> pendingAdd = new CompletableFuture<>();
        platform.addResults.offer(pendingAdd);
        ChunkLeaseRegistry<TestWorld> registry = registry(platform);
        ChunkLease first = registry.retain(WORLD, WORLD_ID, -4, 10);
        first.close();

        ChunkLease second = registry.retain(WORLD, WORLD_ID, -4, 10);
        assertFalse(second.ready().isDone());
        second.close();

        platform.runNextScheduled();
        assertEquals(0, platform.removeCalls);

        pendingAdd.complete(true);
        platform.runNextScheduled();
        assertEquals(1, platform.removeCalls);
    }

    @Test
    void retouchCancelsDelayedRemoval() {
        ManualPlatform platform = new ManualPlatform();
        ChunkLeaseRegistry<TestWorld> registry = registry(platform);
        ChunkLease first = registry.retain(WORLD, WORLD_ID, -3, 11);
        first.close();

        ChunkLease second = registry.retain(WORLD, WORLD_ID, -3, 11);
        assertTrue(second.ready().join());
        assertEquals(1, platform.addCalls);

        platform.runNextScheduled();
        assertEquals(0, platform.removeCalls);

        second.close();
        platform.runNextScheduled();
        assertEquals(1, platform.removeCalls);
    }

    private static ChunkLeaseRegistry<TestWorld> registry(ManualPlatform platform) {
        return new ChunkLeaseRegistry<>(platform, new ChunkLeaseRegistry.Options(20L, 1L, 3));
    }

    private record TestWorld(UUID id, String incarnation) {
    }

    private static final class ManualPlatform implements ChunkLeasePlatform<TestWorld> {
        private final Queue<CompletableFuture<Boolean>> addResults = new ArrayDeque<>();
        private final Queue<CompletableFuture<Boolean>> removeResults = new ArrayDeque<>();
        private final Queue<Runnable> scheduled = new ArrayDeque<>();
        private int addCalls;
        private int removeCalls;
        private int rejectedSchedules;
        private int rejectScheduleCall;
        private int scheduleCalls;
        private int reportedFailures;

        @Override
        public CompletionStage<Boolean> add(TestWorld world, int chunkX, int chunkZ) {
            addCalls++;
            CompletableFuture<Boolean> result = addResults.poll();
            return result == null ? CompletableFuture.completedFuture(true) : result;
        }

        @Override
        public CompletionStage<Boolean> remove(TestWorld world, int chunkX, int chunkZ) {
            removeCalls++;
            CompletableFuture<Boolean> result = removeResults.poll();
            return result == null ? CompletableFuture.completedFuture(true) : result;
        }

        @Override
        public boolean schedule(Runnable command, long delayMillis) {
            scheduleCalls++;
            if (scheduleCalls == rejectScheduleCall) {
                return false;
            }
            if (rejectedSchedules > 0) {
                rejectedSchedules--;
                return false;
            }
            return scheduled.offer(command);
        }

        @Override
        public void reportFailure(Throwable error) {
            reportedFailures++;
        }

        private boolean hasScheduled() {
            return !scheduled.isEmpty();
        }

        private void runNextScheduled() {
            scheduled.remove().run();
        }
    }
}
