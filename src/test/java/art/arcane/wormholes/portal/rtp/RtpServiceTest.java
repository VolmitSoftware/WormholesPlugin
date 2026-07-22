package art.arcane.wormholes.portal.rtp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.List;
import java.util.Optional;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntFunction;

import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.junit.jupiter.api.Test;

public final class RtpServiceTest
{
	@Test
	public void sharedAttendanceCoalescesSearchAndPublishesImmutableReadyViews()
	{
		TestHarness harness = new TestHarness(uniqueSampler());
		UUID portalId = uuid("shared-portal");
		UUID viewerId = uuid("shared-viewer");
		RtpSettings settings = settings(RtpAllocationMode.SHARED, RtpRotationMode.STATIC);
		harness.register(portalId, settings);

		harness.service.touchViewer(portalId, viewerId).join();
		harness.service.touchViewer(portalId, viewerId).join();
		harness.service.touchViewer(portalId, viewerId).join();

		assertEquals(1, harness.executor.size());
		harness.executor.runAll();

		RtpService.Snapshot snapshot = harness.service.snapshot(portalId).orElseThrow();
		assertTrue(snapshot.runtime().ready());
		assertEquals(2, snapshot.runtime().candidateCount());
		assertNotNull(snapshot.runtime().active());
		assertNotNull(snapshot.runtime().standby());
		assertNotEquals(snapshot.runtime().active(), snapshot.runtime().standby());
		assertEquals(RtpProjectionView.State.READY, snapshot.views().get(viewerId).state());
		assertEquals(2, harness.loader.loadCount());
		assertEquals(2, harness.validator.validationCount());
		assertThrows(UnsupportedOperationException.class, () -> snapshot.viewers().clear());
		assertThrows(UnsupportedOperationException.class, () -> snapshot.views().clear());
		RtpRimRenderer.Sample rim = harness.service.rimSample(
				portalId,
				viewerId,
				RtpRimRenderer.Phase.READY,
				0L).orElseThrow();
		assertEquals(RtpRimRenderer.Color.GREEN, rim.color());
	}

	@Test
	public void repeatedReadyAttendanceTouchesDoNotRepeatAccessChecks()
	{
		TestHarness harness = new TestHarness(uniqueSampler());
		UUID portalId = uuid("stable-attendance-portal");
		UUID viewerId = uuid("stable-attendance-viewer");
		harness.register(portalId, settings(RtpAllocationMode.SHARED, RtpRotationMode.STATIC));
		harness.service.touchViewer(portalId, viewerId).join();
		harness.executor.runAll();
		int accessChecks = harness.access.calls();

		harness.service.touchViewer(portalId, viewerId).join();
		harness.service.touchViewer(portalId, viewerId).join();
		harness.service.touchViewer(portalId, viewerId).join();

		assertEquals(accessChecks, harness.access.calls());
		assertEquals(RtpProjectionView.State.READY, harness.service.projectionView(portalId, viewerId).state());
	}

	@Test
	public void duplicateColumnsConsumeAttemptsAndCampaignStopsAfterThirtyTwoAttempts()
	{
		CountingSampler duplicateThenUnique = new CountingSampler(attempt -> attempt < 5
				? destination(0L, attempt, 32, 64, 48)
				: destination(0L, attempt, attempt * 16, 64, 48));
		TestHarness successfulHarness = new TestHarness(duplicateThenUnique);
		UUID successfulPortal = uuid("duplicate-then-unique");
		successfulHarness.register(successfulPortal, settings(RtpAllocationMode.SHARED, RtpRotationMode.STATIC));
		successfulHarness.service.touchViewer(successfulPortal, uuid("viewer")).join();

		successfulHarness.executor.runAll();

		assertTrue(successfulHarness.service.snapshot(successfulPortal).orElseThrow().runtime().ready());
		assertEquals(6, duplicateThenUnique.calls());

		CountingSampler alwaysDuplicate = new CountingSampler(attempt -> destination(0L, attempt, 64, 64, 64));
		TestHarness exhaustedHarness = new TestHarness(alwaysDuplicate);
		UUID exhaustedPortal = uuid("always-duplicate");
		exhaustedHarness.register(exhaustedPortal, settings(RtpAllocationMode.SHARED, RtpRotationMode.STATIC));
		exhaustedHarness.service.touchViewer(exhaustedPortal, uuid("viewer")).join();

		exhaustedHarness.executor.runAll();

		RtpRuntimeSnapshot exhausted = exhaustedHarness.service.snapshot(exhaustedPortal).orElseThrow().runtime();
		assertFalse(exhausted.ready());
		assertNotNull(exhausted.active());
		assertEquals(null, exhausted.standby());
		assertFalse(exhausted.searchInFlight());
		assertEquals(33, alwaysDuplicate.calls());
	}

	@Test
	public void searchCampaignTimesOutAtFiveSecondsAndClosesLatePreparation()
	{
		TestHarness harness = new TestHarness(uniqueSampler());
		UUID portalId = uuid("timeout-portal");
		harness.loader.holdNext();
		harness.register(portalId, settings(RtpAllocationMode.SHARED, RtpRotationMode.STATIC));
		harness.service.touchViewer(portalId, uuid("viewer")).join();
		harness.executor.runNext();
		assertEquals(1, harness.loader.pendingCount());
		assertTrue(harness.service.snapshot(portalId).orElseThrow().runtime().searchInFlight());

		harness.advance(4_999L);
		assertTrue(harness.service.snapshot(portalId).orElseThrow().runtime().searchInFlight());
		harness.advance(1L);

		assertFalse(harness.service.snapshot(portalId).orElseThrow().runtime().searchInFlight());
		CountingRetention lateRetention = harness.loader.completeNext();
		harness.executor.runAll();
		assertEquals(1, lateRetention.closeCount());
		assertEquals(0, harness.service.snapshot(portalId).orElseThrow().runtime().candidateCount());
	}

	@Test
	public void searchCompletionAtDeadlineIsRejectedBeforeTimerDelivery()
	{
		TestHarness harness = new TestHarness(uniqueSampler());
		UUID portalId = uuid("deadline-boundary-portal");
		harness.loader.holdNext();
		harness.register(portalId, settings(RtpAllocationMode.SHARED, RtpRotationMode.STATIC));
		harness.service.touchViewer(portalId, uuid("viewer")).join();
		harness.executor.runNext();

		harness.clock.advance(5_000L);
		CountingRetention lateRetention = harness.loader.completeNext();
		harness.executor.runAll();

		assertEquals(1, lateRetention.closeCount());
		assertEquals(0, harness.service.snapshot(portalId).orElseThrow().runtime().candidateCount());
		assertFalse(harness.service.snapshot(portalId).orElseThrow().runtime().searchInFlight());
	}

	@Test
	public void perPlayerAttendanceReservesReleasesConsumesAndRefills()
	{
		TestHarness harness = new TestHarness(uniqueSampler());
		UUID portalId = uuid("private-portal");
		UUID playerId = uuid("private-player");
		RtpSettings settings = settings(RtpAllocationMode.PER_PLAYER, RtpRotationMode.STATIC);
		harness.register(portalId, settings);
		harness.service.touchViewer(portalId, playerId).join();
		harness.executor.runAll();

		RtpRuntimeSnapshot ready = harness.service.snapshot(portalId).orElseThrow().runtime();
		assertEquals(3, ready.candidateCount());
		assertEquals(1, ready.reservedPlayers());
		assertEquals(2, ready.freeCandidates());
		assertEquals(RtpProjectionView.State.READY, harness.service.projectionView(portalId, playerId).state());

		harness.service.leaveViewer(portalId, playerId).join();
		harness.advance(4_999L);
		harness.service.tick(portalId).join();
		assertEquals(1, harness.service.snapshot(portalId).orElseThrow().runtime().reservedPlayers());
		harness.advance(1L);
		harness.service.tick(portalId).join();
		assertEquals(0, harness.service.snapshot(portalId).orElseThrow().runtime().reservedPlayers());
		assertEquals(3, harness.service.snapshot(portalId).orElseThrow().runtime().freeCandidates());

		harness.service.touchViewer(portalId, playerId).join();
		harness.executor.runAll();
		RtpService.TraversalPreparation preparation = harness.service.claimTraversal(
				portalId,
				RtpService.TraversalActor.player(uuid("private-claim"), playerId)).join().orElseThrow();
		assertTrue(harness.service.markTraversalDispatched(preparation).join());
		assertTrue(harness.service.completeTraversal(preparation, true).join());
		harness.executor.runAll();

		RtpRuntimeSnapshot refilled = harness.service.snapshot(portalId).orElseThrow().runtime();
		assertEquals(3, refilled.candidateCount());
		assertEquals(1, refilled.reservedPlayers());
		assertEquals(2, refilled.freeCandidates());
	}

	@Test
	public void perPlayerTimerPreservesTheOldProjectionUntilTheWarmSwapIsAuthorized()
	{
		TestHarness harness = new TestHarness(uniqueSampler());
		UUID portalId = uuid("private-rotation-portal");
		UUID playerId = uuid("private-rotation-player");
		harness.register(portalId, settings(RtpAllocationMode.PER_PLAYER, RtpRotationMode.TIMED));
		harness.service.touchViewer(portalId, playerId).join();
		harness.executor.runAll();
		RtpProjectionView before = harness.service.projectionView(portalId, playerId);
		harness.access.holdNext();

		harness.advance(15_000L);
		harness.service.tick(portalId).join();

		assertEquals(before, harness.service.projectionView(portalId, playerId));
		assertEquals(1, harness.access.pendingCount());
		harness.access.completeNext(RtpAccessResult.allowedResult());
		RtpProjectionView after = harness.service.projectionView(portalId, playerId);
		assertEquals(RtpProjectionView.State.READY, after.state());
		assertNotEquals(before.readyFor(playerId).orElseThrow(), after.readyFor(playerId).orElseThrow());
	}

	@Test
	public void manualRerollCommitsPairAndSettingsChangeRejectsLateGeneration()
	{
		TestHarness harness = new TestHarness(uniqueSampler());
		UUID portalId = uuid("reroll-portal");
		UUID viewerId = uuid("reroll-viewer");
		RtpSettings originalSettings = settings(RtpAllocationMode.SHARED, RtpRotationMode.STATIC);
		harness.register(portalId, originalSettings);
		harness.service.touchViewer(portalId, viewerId).join();
		harness.executor.runAll();
		RtpService.Snapshot beforeReroll = harness.service.snapshot(portalId).orElseThrow();

		assertTrue(harness.service.manualReroll(portalId).join());
		harness.executor.runAll();

		RtpService.Snapshot afterReroll = harness.service.snapshot(portalId).orElseThrow();
		assertTrue(afterReroll.runtime().ready());
		assertNotEquals(beforeReroll.runtime().active(), afterReroll.runtime().active());
		assertTrue(afterReroll.runtime().routeRevision() > beforeReroll.runtime().routeRevision());

		harness.service.leaveViewer(portalId, viewerId).join();
		harness.loader.holdNext();
		RtpSettings changedSettings = RtpSettings.builder(world())
				.radii(128, 1024)
				.allocationMode(RtpAllocationMode.SHARED)
				.rotationMode(RtpRotationMode.STATIC)
				.build();
		harness.service.updateSettings(portalId, changedSettings).join();
		harness.service.touchViewer(portalId, viewerId).join();
		harness.executor.runNext();
		long updatedGeneration = harness.service.snapshot(portalId).orElseThrow().generation();
		assertTrue(updatedGeneration > afterReroll.generation());

		RtpSettings secondChange = RtpSettings.builder(world())
				.radii(256, 1536)
				.allocationMode(RtpAllocationMode.SHARED)
				.rotationMode(RtpRotationMode.STATIC)
				.build();
		harness.service.updateSettings(portalId, secondChange).join();
		CountingRetention staleRetention = harness.loader.completeNext();
		harness.executor.runAll();

		RtpService.Snapshot finalSnapshot = harness.service.snapshot(portalId).orElseThrow();
		assertTrue(finalSnapshot.generation() > updatedGeneration);
		assertTrue(finalSnapshot.runtime().ready());
		assertEquals(1, staleRetention.closeCount());
		assertEquals(finalSnapshot.generation(), finalSnapshot.runtime().active().generation());
	}

	@Test
	public void manualRerollRetainsThePublishedPairUntilReplacementAccessCompletes()
	{
		TestHarness harness = new TestHarness(uniqueSampler());
		UUID portalId = uuid("seamless-manual-reroll-portal");
		UUID viewerId = uuid("seamless-manual-reroll-viewer");
		harness.register(portalId, settings(RtpAllocationMode.SHARED, RtpRotationMode.STATIC));
		harness.service.touchViewer(portalId, viewerId).join();
		harness.executor.runAll();
		RtpProjectionView before = harness.service.projectionView(portalId, viewerId);
		harness.access.holdNext();

		assertTrue(harness.service.manualReroll(portalId).join());
		harness.executor.runAll();

		assertEquals(before, harness.service.projectionView(portalId, viewerId));
		assertEquals(3, harness.loader.openRetentions());
		assertEquals(1, harness.access.pendingCount());

		harness.access.completeNext(RtpAccessResult.allowedResult());

		RtpProjectionView after = harness.service.projectionView(portalId, viewerId);
		assertEquals(RtpProjectionView.State.READY, after.state());
		assertNotEquals(before.readyFor(viewerId).orElseThrow(), after.readyFor(viewerId).orElseThrow());
		assertEquals(2, harness.loader.openRetentions());
	}

	@Test
	public void accessMustAllowBeforeReadyPublicationAndTraversalPreparation()
	{
		TestHarness harness = new TestHarness(uniqueSampler());
		UUID portalId = uuid("access-portal");
		UUID viewerId = uuid("access-viewer");
		harness.access.holdNext();
		harness.register(portalId, settings(RtpAllocationMode.SHARED, RtpRotationMode.ON_TRAVERSAL));
		harness.service.touchViewer(portalId, viewerId).join();
		harness.executor.runAll();

		assertEquals(RtpProjectionView.State.WARMING, harness.service.projectionView(portalId, viewerId).state());
		harness.access.completeNext(RtpAccessResult.deniedResult());
		assertEquals(RtpProjectionView.State.DENIED, harness.service.projectionView(portalId, viewerId).state());

		harness.service.touchViewer(portalId, viewerId).join();
		assertEquals(RtpProjectionView.State.READY, harness.service.projectionView(portalId, viewerId).state());
		harness.access.holdNext();
		CompletableFuture<Optional<RtpService.TraversalPreparation>> claimFuture = harness.service.claimTraversal(
				portalId,
				RtpService.TraversalActor.player(uuid("claim"), viewerId));
		assertFalse(claimFuture.isDone());
		harness.access.completeNext(RtpAccessResult.allowedResult());

		RtpService.TraversalPreparation preparation = claimFuture.join().orElseThrow();
		assertEquals(RtpTraversalRequest.State.PREPARING, preparation.request().state());
		assertTrue(harness.service.markTraversalDispatched(preparation).join());
		assertTrue(harness.service.completeTraversal(preparation, true).join());
		harness.executor.runAll();
		assertTrue(harness.service.snapshot(portalId).orElseThrow().runtime().ready());
	}

	@Test
	public void dispatchedTraversalTimesOutAndReleasesClaimWhenCompletionNeverArrives()
	{
		TestHarness harness = new TestHarness(uniqueSampler());
		UUID portalId = uuid("dispatch-timeout-portal");
		UUID viewerId = uuid("dispatch-timeout-viewer");
		harness.register(portalId, settings(RtpAllocationMode.SHARED, RtpRotationMode.ON_TRAVERSAL));
		harness.service.touchViewer(portalId, viewerId).join();
		harness.executor.runAll();
		assertEquals(2, harness.loader.openRetentions());

		RtpService.TraversalPreparation preparation = harness.service.claimTraversal(
				portalId,
				RtpService.TraversalActor.player(uuid("dispatch-timeout-claim"), viewerId)).join().orElseThrow();
		assertTrue(harness.service.markTraversalDispatched(preparation).join());
		assertEquals(RtpTraversalRequest.State.DISPATCHED, preparation.request().state());

		harness.advance(10_000L);

		assertEquals(RtpTraversalRequest.State.FAILED, preparation.request().state());
		assertTrue(harness.service.snapshot(portalId).orElseThrow().runtime().ready());
		assertEquals(2, harness.loader.openRetentions());
	}

	@Test
	public void onTraversalKeepsThePreviousProjectionReadyUntilTheReplacementPairIsReady()
	{
		TestHarness harness = new TestHarness(uniqueSampler());
		UUID portalId = uuid("seamless-traversal-portal");
		UUID viewerId = uuid("seamless-traversal-viewer");
		harness.register(portalId, settings(RtpAllocationMode.SHARED, RtpRotationMode.ON_TRAVERSAL));
		harness.service.touchViewer(portalId, viewerId).join();
		harness.executor.runAll();
		RtpProjectionView before = harness.service.projectionView(portalId, viewerId);

		RtpService.TraversalPreparation preparation = harness.service.claimTraversal(
				portalId,
				RtpService.TraversalActor.player(uuid("seamless-claim"), viewerId)).join().orElseThrow();
		assertEquals(RtpProjectionView.State.READY, harness.service.projectionView(portalId, viewerId).state());
		assertTrue(harness.service.markTraversalDispatched(preparation).join());
		assertTrue(harness.service.completeTraversal(preparation, true).join());

		assertFalse(harness.service.snapshot(portalId).orElseThrow().runtime().ready());
		assertEquals(before, harness.service.projectionView(portalId, viewerId));
		assertEquals(2, harness.loader.openRetentions());

		harness.executor.runAll();
		RtpProjectionView after = harness.service.projectionView(portalId, viewerId);
		assertEquals(RtpProjectionView.State.READY, after.state());
		assertNotEquals(before.readyFor(viewerId).orElseThrow(), after.readyFor(viewerId).orElseThrow());
		assertEquals(2, harness.loader.openRetentions());
	}

	@Test
	public void dispatchWatchdogIsNoOpWhenTraversalCompletesBeforeDeadline()
	{
		TestHarness harness = new TestHarness(uniqueSampler());
		UUID portalId = uuid("dispatch-complete-portal");
		UUID viewerId = uuid("dispatch-complete-viewer");
		harness.register(portalId, settings(RtpAllocationMode.SHARED, RtpRotationMode.ON_TRAVERSAL));
		harness.service.touchViewer(portalId, viewerId).join();
		harness.executor.runAll();

		RtpService.TraversalPreparation preparation = harness.service.claimTraversal(
				portalId,
				RtpService.TraversalActor.player(uuid("dispatch-complete-claim"), viewerId)).join().orElseThrow();
		assertTrue(harness.service.markTraversalDispatched(preparation).join());
		assertTrue(harness.service.completeTraversal(preparation, true).join());
		assertEquals(RtpTraversalRequest.State.SUCCEEDED, preparation.request().state());

		harness.advance(10_000L);

		assertEquals(RtpTraversalRequest.State.SUCCEEDED, preparation.request().state());
	}

	@Test
	public void lastViewerIdleGraceKeepsWarmRetentionsAndCancelsStaleCleanup()
	{
		TestHarness harness = new TestHarness(uniqueSampler());
		UUID portalId = uuid("idle-portal");
		UUID viewerId = uuid("idle-viewer");
		harness.register(portalId, settings(RtpAllocationMode.SHARED, RtpRotationMode.STATIC));
		harness.service.touchViewer(portalId, viewerId).join();
		harness.executor.runAll();
		assertEquals(2, harness.loader.openRetentions());
		int sampledBeforeIdle = harness.sampler.calls();

		harness.service.leaveViewer(portalId, viewerId).join();

		assertEquals(2, harness.loader.openRetentions());
		assertTrue(harness.service.snapshot(portalId).orElseThrow().viewers().isEmpty());
		assertEquals(RtpProjectionView.State.NONE, harness.service.projectionView(portalId, viewerId).state());

		harness.advance(29_999L);
		harness.service.touchViewer(portalId, viewerId).join();
		harness.executor.runAll();
		harness.advance(1L);
		assertEquals(sampledBeforeIdle, harness.sampler.calls());
		assertEquals(2, harness.loader.openRetentions());
		assertEquals(RtpProjectionView.State.READY, harness.service.projectionView(portalId, viewerId).state());

		harness.service.leaveViewer(portalId, viewerId).join();
		harness.advance(30_000L);
		assertEquals(0, harness.loader.openRetentions());

		assertTrue(harness.service.unregister(portalId).join());
		assertEquals(0, harness.loader.openRetentions());
		assertTrue(harness.service.snapshot(portalId).isEmpty());
	}

	@Test
	public void idleClosesRetentionWhileExistingDestinationValidationIsQueued()
	{
		TestHarness harness = new TestHarness(uniqueSampler());
		UUID portalId = uuid("queued-validation-portal");
		UUID viewerId = uuid("queued-validation-viewer");
		harness.register(portalId, settings(RtpAllocationMode.SHARED, RtpRotationMode.STATIC));
		harness.service.touchViewer(portalId, viewerId).join();
		harness.executor.runAll();
		harness.service.leaveViewer(portalId, viewerId).join();
		harness.advance(30_000L);
		assertEquals(0, harness.loader.openRetentions());

		harness.service.touchViewer(portalId, viewerId).join();
		harness.executor.runNext();
		assertEquals(1, harness.loader.openRetentions());

		harness.service.leaveViewer(portalId, viewerId).join();

		assertEquals(1, harness.loader.openRetentions());
		harness.advance(30_000L);
		assertEquals(0, harness.loader.openRetentions());
		harness.executor.runAll();
		assertEquals(0, harness.loader.openRetentions());
	}

	private static CountingSampler uniqueSampler()
	{
		return new CountingSampler(attempt -> destination(0L, attempt, attempt * 16, 64, attempt * -16));
	}

	private static RtpDestination destination(long generation, int attempt, int blockX, int feetY, int blockZ)
	{
		return new RtpDestination("minecraft:overworld", blockX, feetY, blockZ, generation, attempt);
	}

	private static RtpSettings settings(RtpAllocationMode allocationMode, RtpRotationMode rotationMode)
	{
		return RtpSettings.builder(world())
				.radii(16, 256)
				.allocationMode(allocationMode)
				.rotationMode(rotationMode)
				.cycleDurationMillis(15_000L)
				.privateReleaseMillis(5_000L)
				.build();
	}

	private static World world()
	{
		NamespacedKey key = NamespacedKey.minecraft("overworld");
		return (World) Proxy.newProxyInstance(World.class.getClassLoader(), new Class<?>[] { World.class }, (proxy, method, arguments) -> switch(method.getName())
		{
			case "getName" -> "overworld";
			case "getKey" -> key;
			case "getUID" -> uuid("overworld");
			case "getMinHeight" -> Integer.valueOf(-64);
			case "getMaxHeight" -> Integer.valueOf(320);
			case "getSeaLevel" -> Integer.valueOf(63);
			case "toString" -> "RtpServiceTestWorld";
			case "hashCode" -> Integer.valueOf(System.identityHashCode(proxy));
			case "equals" -> Boolean.valueOf(proxy == arguments[0]);
			default -> throw new UnsupportedOperationException(method.getName());
		});
	}

	private static UUID uuid(String value)
	{
		return UUID.nameUUIDFromBytes(value.getBytes(StandardCharsets.UTF_8));
	}

	private static RtpValidationRequest validationRequest(RtpDestination destination)
	{
		int chunkX = Math.floorDiv(destination.blockX(), 16);
		int chunkZ = Math.floorDiv(destination.blockZ(), 16);
		List<RtpValidationRequest.BlockSnapshot> blocks = List.of(
				RtpValidationRequest.BlockSnapshot.solid(destination.blockX(), destination.feetY() - 1, destination.blockZ(), "minecraft:stone"),
				RtpValidationRequest.BlockSnapshot.air(destination.blockX(), destination.feetY(), destination.blockZ()),
				RtpValidationRequest.BlockSnapshot.air(destination.blockX(), destination.feetY() + 1, destination.blockZ()));
		RtpValidationRequest.RegionSnapshot region = new RtpValidationRequest.RegionSnapshot(
				"region-" + chunkX + "-" + chunkZ,
				chunkX,
				chunkZ,
				blocks);
		return RtpValidationRequest.builder(destination)
				.worldBounds(-64, 320)
				.worldBorder(new RtpValidationRequest.WorldBorder(-30_000_000.0D, -30_000_000.0D, 30_000_000.0D, 30_000_000.0D))
				.regionSnapshots(List.of(region))
				.build();
	}

	private static final class TestHarness
	{
		private final ManualClock clock;
		private final ManualSourceDispatcher dispatcher;
		private final ManualSearchExecutor executor;
		private final CountingSampler sampler;
		private final ControlledLoader loader;
		private final CountingSafetyValidator validator;
		private final ControlledAccess access;
		private final RtpService service;

		private TestHarness(CountingSampler sampler)
		{
			this.clock = new ManualClock();
			this.dispatcher = new ManualSourceDispatcher(clock);
			this.executor = new ManualSearchExecutor();
			this.sampler = sampler;
			this.loader = new ControlledLoader();
			this.validator = new CountingSafetyValidator();
			this.access = new ControlledAccess();
			RtpService.Dependencies dependencies = new RtpService.Dependencies(
					dispatcher,
					executor,
					clock,
					sampler,
					loader,
					validator,
					access,
					TestHarness::projection,
					new RtpRimRenderer());
			this.service = new RtpService(dependencies);
		}

		private void register(UUID portalId, RtpSettings settings)
		{
			RtpService.Registration registration = new RtpService.Registration(portalId, settings, 0.0D, 0.0D, 91L);
			service.register(registration).join();
		}

		private void advance(long millis)
		{
			clock.advance(millis);
			dispatcher.runDue();
		}

		private static RtpProjectionView.ReadyData projection(
				UUID portalId,
				UUID viewerId,
				RtpDestination destination,
				long routeRevision)
		{
			RtpProjectionView.Point3 sourceCenter = new RtpProjectionView.Point3(0.5D, 65.0D, 0.5D);
			RtpProjectionView.Vector3 right = new RtpProjectionView.Vector3(1.0D, 0.0D, 0.0D);
			RtpProjectionView.Vector3 up = new RtpProjectionView.Vector3(0.0D, 1.0D, 0.0D);
			RtpProjectionView.Vector3 forward = new RtpProjectionView.Vector3(0.0D, 0.0D, 1.0D);
			RtpProjectionView.SourceFrame sourceFrame = new RtpProjectionView.SourceFrame(
					"minecraft:overworld",
					sourceCenter,
					right,
					up,
					forward,
					3.0D,
					4.0D,
					routeRevision);
			RtpProjectionView.Target target = new RtpProjectionView.Target(
					destination.worldKey(),
					new RtpProjectionView.Point3(destination.blockX() + 0.5D, destination.feetY(), destination.blockZ() + 0.5D),
					right,
					up,
					forward);
			UUID routeId = uuid(portalId + ":" + viewerId + ":" + destination.blockX() + ":" + destination.blockZ());
			return new RtpProjectionView.ReadyData(routeId, routeRevision, sourceFrame, target);
		}
	}

	private static final class ManualClock implements RtpService.TimeSource
	{
		private long nowMillis;

		@Override
		public long nowMillis()
		{
			return nowMillis;
		}

		private void advance(long millis)
		{
			nowMillis += millis;
		}
	}

	private static final class ManualSourceDispatcher implements RtpService.SourceDispatcher
	{
		private final ManualClock clock;
		private final List<ScheduledCommand> scheduled;

		private ManualSourceDispatcher(ManualClock clock)
		{
			this.clock = clock;
			this.scheduled = new ArrayList<ScheduledCommand>();
		}

		@Override
		public void execute(UUID portalId, Runnable command)
		{
			command.run();
		}

		@Override
		public void schedule(UUID portalId, Runnable command, long delayMillis)
		{
			scheduled.add(new ScheduledCommand(clock.nowMillis() + delayMillis, portalId, command));
		}

		private void runDue()
		{
			scheduled.sort(Comparator.comparingLong(ScheduledCommand::deadlineMillis));
			boolean ran;
			do
			{
				ran = false;
				for(int index = 0; index < scheduled.size(); index++)
				{
					ScheduledCommand command = scheduled.get(index);
					if(command.deadlineMillis() > clock.nowMillis())
					{
						continue;
					}
					scheduled.remove(index);
					execute(command.portalId(), command.command());
					ran = true;
					break;
				}
			}
			while(ran);
		}
	}

	private record ScheduledCommand(long deadlineMillis, UUID portalId, Runnable command)
	{
	}

	private static final class ManualSearchExecutor implements RtpService.SearchExecutor
	{
		private final Queue<Runnable> commands = new ArrayDeque<Runnable>();

		@Override
		public void execute(Runnable command)
		{
			commands.add(command);
		}

		private int size()
		{
			return commands.size();
		}

		private void runNext()
		{
			Runnable command = commands.remove();
			command.run();
		}

		private void runAll()
		{
			int iterations = 0;
			while(!commands.isEmpty())
			{
				runNext();
				iterations++;
				if(iterations > 10_000)
				{
					throw new AssertionError("search executor did not become idle");
				}
			}
		}
	}

	private static final class CountingSampler implements RtpService.Sampler
	{
		private final IntFunction<RtpDestination> destinations;
		private final AtomicInteger calls;

		private CountingSampler(IntFunction<RtpDestination> destinations)
		{
			this.destinations = destinations;
			this.calls = new AtomicInteger();
		}

		@Override
		public RtpDestination sample(RtpService.Registration registration, long generation, int attempt)
		{
			calls.incrementAndGet();
			RtpDestination sampled = destinations.apply(attempt);
			return new RtpDestination(
					registration.settings().getTargetWorldKey(),
					sampled.blockX(),
					sampled.feetY(),
					sampled.blockZ(),
					generation,
					attempt);
		}

		private int calls()
		{
			return calls.get();
		}
	}

	private static final class ControlledLoader implements RtpService.CandidateLoader
	{
		private final Deque<PendingLoad> pending;
		private final List<CountingRetention> retentions;
		private boolean holdNext;
		private int loadCount;

		private ControlledLoader()
		{
			this.pending = new ArrayDeque<PendingLoad>();
			this.retentions = new ArrayList<CountingRetention>();
		}

		@Override
		public CompletionStage<RtpService.LoadedCandidate> load(RtpService.SearchRequest request)
		{
			loadCount++;
			CountingRetention retention = new CountingRetention();
			retentions.add(retention);
			RtpService.LoadedCandidate loaded = new RtpService.LoadedCandidate(validationRequest(request.destination()), retention);
			if(!holdNext)
			{
				return CompletableFuture.completedFuture(loaded);
			}
			holdNext = false;
			CompletableFuture<RtpService.LoadedCandidate> future = new CompletableFuture<RtpService.LoadedCandidate>();
			pending.addLast(new PendingLoad(future, loaded, retention));
			return future;
		}

		private void holdNext()
		{
			holdNext = true;
		}

		private CountingRetention completeNext()
		{
			PendingLoad load = pending.removeFirst();
			load.future().complete(load.loaded());
			return load.retention();
		}

		private int pendingCount()
		{
			return pending.size();
		}

		private int loadCount()
		{
			return loadCount;
		}

		private int openRetentions()
		{
			int open = 0;
			for(CountingRetention retention : retentions)
			{
				if(retention.closeCount() == 0)
				{
					open++;
				}
			}
			return open;
		}
	}

	private record PendingLoad(
			CompletableFuture<RtpService.LoadedCandidate> future,
			RtpService.LoadedCandidate loaded,
			CountingRetention retention)
	{
	}

	private static final class CountingRetention implements RtpService.Retention
	{
		private final AtomicInteger closeCount = new AtomicInteger();

		@Override
		public void close()
		{
			closeCount.incrementAndGet();
		}

		private int closeCount()
		{
			return closeCount.get();
		}
	}

	private static final class CountingSafetyValidator implements RtpService.SafetyValidator
	{
		private final RtpSafetyValidator validator;
		private final AtomicInteger validationCount;

		private CountingSafetyValidator()
		{
			this.validator = new RtpSafetyValidator();
			this.validationCount = new AtomicInteger();
		}

		@Override
		public CompletionStage<RtpSafetyResult> validate(RtpValidationRequest request)
		{
			validationCount.incrementAndGet();
			return validator.validate(request);
		}

		private int validationCount()
		{
			return validationCount.get();
		}
	}

	private static final class ControlledAccess implements RtpService.AccessChecker
	{
		private final Deque<CompletableFuture<RtpAccessResult>> pending;
		private final AtomicInteger calls;
		private boolean holdNext;

		private ControlledAccess()
		{
			this.pending = new ArrayDeque<CompletableFuture<RtpAccessResult>>();
			this.calls = new AtomicInteger();
		}

		@Override
		public CompletionStage<RtpAccessResult> canUse(
				UUID portalId,
				Optional<UUID> viewerId,
				RtpDestination destination)
		{
			calls.incrementAndGet();
			if(!holdNext)
			{
				return CompletableFuture.completedFuture(RtpAccessResult.allowedResult());
			}
			holdNext = false;
			CompletableFuture<RtpAccessResult> future = new CompletableFuture<RtpAccessResult>();
			pending.addLast(future);
			return future;
		}

		private void holdNext()
		{
			holdNext = true;
		}

		private void completeNext(RtpAccessResult result)
		{
			CompletableFuture<RtpAccessResult> future = pending.removeFirst();
			future.complete(result);
		}

		private int pendingCount()
		{
			return pending.size();
		}

		private int calls()
		{
			return calls.get();
		}
	}
}
