package art.arcane.wormholes.portal.rtp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Constructor;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

public final class RtpPortalRuntimeTest
{
	@Test
	public void factoriesRejectNonPositiveDurations()
	{
		assertThrows(IllegalArgumentException.class, () -> RtpPortalRuntime.shared(1L, RtpRotationMode.TIMED, 0L));
		assertThrows(IllegalArgumentException.class, () -> RtpPortalRuntime.perPlayer(1L, 0L));
		assertThrows(IllegalArgumentException.class, () -> RtpPortalRuntime.perPlayer(1L, 1_000L, 0L));
	}

	@Test
	public void sharedRuntimeRequiresActiveAndStandbyAndCoalescesSearchAdmission() throws Exception
	{
		RtpPortalRuntime runtime = RtpPortalRuntime.shared(4L, RtpRotationMode.STATIC, 1_000L);
		ExecutorService executor = Executors.newFixedThreadPool(8);
		List<Callable<Optional<RtpPortalRuntime.SearchTicket>>> attempts = new ArrayList<>();
		for (int index = 0; index < 64; index++)
		{
			attempts.add(runtime::beginSearch);
		}

		List<Future<Optional<RtpPortalRuntime.SearchTicket>>> results = executor.invokeAll(attempts);
		executor.shutdown();
		assertTrue(executor.awaitTermination(5L, TimeUnit.SECONDS));
		RtpPortalRuntime.SearchTicket activeTicket = null;
		int admitted = 0;
		for (Future<Optional<RtpPortalRuntime.SearchTicket>> result : results)
		{
			Optional<RtpPortalRuntime.SearchTicket> ticket = result.get();
			if (ticket.isPresent())
			{
				activeTicket = ticket.get();
				admitted++;
			}
		}
		assertEquals(1, admitted);
		assertNotNull(activeTicket);
		assertEquals(RtpPortalRuntime.SearchPurpose.SHARED_ACTIVE, activeTicket.purpose());

		RtpDestination active = destination("active");
		assertEquals(RtpPortalRuntime.SearchCompletion.ADDED, runtime.completeSearch(activeTicket, active, 100L));
		assertFalse(runtime.snapshot().ready());
		RtpPortalRuntime.SearchTicket standbyTicket = runtime.beginSearch().orElseThrow();
		assertEquals(RtpPortalRuntime.SearchPurpose.SHARED_STANDBY, standbyTicket.purpose());
		RtpDestination standby = destination("standby");
		assertEquals(RtpPortalRuntime.SearchCompletion.ADDED, runtime.completeSearch(standbyTicket, standby, 100L));

		RtpRuntimeSnapshot snapshot = runtime.snapshot();
		assertTrue(snapshot.ready());
		assertSame(active, snapshot.active());
		assertSame(standby, snapshot.standby());
		assertEquals(2, snapshot.candidateCount());
		assertFalse(snapshot.searchInFlight());
	}

	@Test
	public void staticTraversalLeavesSharedPairStable()
	{
		RtpPortalRuntime runtime = RtpPortalRuntime.shared(8L, RtpRotationMode.STATIC, 1_000L);
		RtpDestination active = destination("active");
		RtpDestination standby = destination("standby");
		fillShared(runtime, active, standby, 0L);
		long routeRevision = runtime.snapshot().routeRevision();

		RtpPortalRuntime.TraversalClaim first = runtime.claimShared(uuid("first")).orElseThrow();
		RtpPortalRuntime.TraversalClaim second = runtime.claimShared(uuid("second")).orElseThrow();
		assertTrue(runtime.completeTraversal(first, true, 10L));
		assertTrue(runtime.completeTraversal(second, false, 10L));

		RtpRuntimeSnapshot snapshot = runtime.snapshot();
		assertTrue(snapshot.ready());
		assertSame(active, snapshot.active());
		assertSame(standby, snapshot.standby());
		assertEquals(routeRevision, snapshot.routeRevision());
	}

	@Test
	public void onTraversalSuccessPromotesStandbyAndFailureRollsBackClaim()
	{
		RtpPortalRuntime successful = RtpPortalRuntime.shared(12L, RtpRotationMode.ON_TRAVERSAL, 1_000L);
		RtpDestination oldActive = destination("old-active");
		RtpDestination promoted = destination("promoted");
		fillShared(successful, oldActive, promoted, 0L);
		long oldRevision = successful.snapshot().routeRevision();
		RtpPortalRuntime.TraversalClaim successClaim = successful.claimShared(uuid("success")).orElseThrow();
		assertFalse(successful.snapshot().ready());
		assertTrue(successful.claimShared(uuid("blocked")).isEmpty());
		assertTrue(successful.completeTraversal(successClaim, true, 20L));
		RtpRuntimeSnapshot promotedSnapshot = successful.snapshot();
		assertSame(promoted, promotedSnapshot.active());
		assertNull(promotedSnapshot.standby());
		assertFalse(promotedSnapshot.ready());
		assertTrue(promotedSnapshot.routeRevision() > oldRevision);

		RtpPortalRuntime failed = RtpPortalRuntime.shared(13L, RtpRotationMode.ON_TRAVERSAL, 1_000L);
		RtpDestination retainedActive = destination("retained-active");
		RtpDestination retainedStandby = destination("retained-standby");
		fillShared(failed, retainedActive, retainedStandby, 0L);
		long retainedRevision = failed.snapshot().routeRevision();
		RtpPortalRuntime.TraversalClaim failedClaim = failed.claimShared(uuid("failure")).orElseThrow();
		assertTrue(failed.completeTraversal(failedClaim, false, 20L));
		RtpRuntimeSnapshot rolledBack = failed.snapshot();
		assertTrue(rolledBack.ready());
		assertSame(retainedActive, rolledBack.active());
		assertSame(retainedStandby, rolledBack.standby());
		assertEquals(retainedRevision, rolledBack.routeRevision());
	}

	@Test
	public void timedRotationDefersWhileUnattendedThenPromotesOnce()
	{
		RtpPortalRuntime runtime = RtpPortalRuntime.shared(17L, RtpRotationMode.TIMED, 1_000L);
		RtpDestination active = destination("active");
		RtpDestination standby = destination("standby");
		fillShared(runtime, active, standby, 100L);
		assertEquals(1_100L, runtime.snapshot().nextRotationAtMillis());
		assertFalse(runtime.advanceTimedRotation(1_099L, true));
		assertTrue(runtime.advanceTimedRotation(1_100L, false));
		RtpRuntimeSnapshot pending = runtime.snapshot();
		assertFalse(pending.ready());
		assertTrue(pending.timedRotationPending());
		assertSame(active, pending.active());
		assertSame(standby, pending.standby());

		assertTrue(runtime.advanceTimedRotation(1_200L, true));
		RtpRuntimeSnapshot promoted = runtime.snapshot();
		assertSame(standby, promoted.active());
		assertNull(promoted.standby());
		assertFalse(promoted.timedRotationPending());
		assertEquals(0L, promoted.nextRotationAtMillis());
		assertFalse(runtime.advanceTimedRotation(5_000L, true));

		RtpDestination replacement = destination("replacement");
		RtpPortalRuntime.SearchTicket replacementTicket = runtime.beginSearch().orElseThrow();
		assertEquals(RtpPortalRuntime.SearchCompletion.ADDED, runtime.completeSearch(replacementTicket, replacement, 1_250L));
		assertTrue(runtime.snapshot().ready());
		assertEquals(2_250L, runtime.snapshot().nextRotationAtMillis());
	}

	@Test
	public void manualRerollCommitsAtomicallyOrRestoresOldPair()
	{
		RtpPortalRuntime committed = RtpPortalRuntime.shared(19L, RtpRotationMode.STATIC, 1_000L);
		RtpDestination oldActive = destination("old-active");
		RtpDestination oldStandby = destination("old-standby");
		fillShared(committed, oldActive, oldStandby, 0L);
		long oldRevision = committed.snapshot().routeRevision();
		assertTrue(committed.startManualReroll());
		RtpDestination newActive = destination("new-active");
		RtpDestination newStandby = destination("new-standby");
		RtpPortalRuntime.SearchTicket activeTicket = committed.beginSearch().orElseThrow();
		assertEquals(RtpPortalRuntime.SearchPurpose.REROLL_ACTIVE, activeTicket.purpose());
		assertEquals(RtpPortalRuntime.SearchCompletion.ADDED, committed.completeSearch(activeTicket, newActive, 10L));
		RtpPortalRuntime.SearchTicket standbyTicket = committed.beginSearch().orElseThrow();
		assertEquals(RtpPortalRuntime.SearchPurpose.REROLL_STANDBY, standbyTicket.purpose());
		assertEquals(RtpPortalRuntime.SearchCompletion.ADDED, committed.completeSearch(standbyTicket, newStandby, 10L));
		RtpRuntimeSnapshot committedSnapshot = committed.snapshot();
		assertTrue(committedSnapshot.ready());
		assertSame(newActive, committedSnapshot.active());
		assertSame(newStandby, committedSnapshot.standby());
		assertTrue(committedSnapshot.routeRevision() > oldRevision);

		RtpPortalRuntime rolledBack = RtpPortalRuntime.shared(20L, RtpRotationMode.STATIC, 1_000L);
		RtpDestination rollbackActive = destination("rollback-active");
		RtpDestination rollbackStandby = destination("rollback-standby");
		fillShared(rolledBack, rollbackActive, rollbackStandby, 0L);
		long rollbackRevision = rolledBack.snapshot().routeRevision();
		assertTrue(rolledBack.startManualReroll());
		RtpPortalRuntime.SearchTicket proposalTicket = rolledBack.beginSearch().orElseThrow();
		assertEquals(RtpPortalRuntime.SearchCompletion.ADDED, rolledBack.completeSearch(proposalTicket, destination("proposal"), 10L));
		RtpPortalRuntime.SearchTicket failedTicket = rolledBack.beginSearch().orElseThrow();
		assertTrue(rolledBack.failSearch(failedTicket, 15L));
		RtpRuntimeSnapshot rollbackSnapshot = rolledBack.snapshot();
		assertTrue(rollbackSnapshot.ready());
		assertSame(rollbackActive, rollbackSnapshot.active());
		assertSame(rollbackStandby, rollbackSnapshot.standby());
		assertEquals(rollbackRevision, rollbackSnapshot.routeRevision());
	}

	@Test
	public void candidateUniquenessCoversSharedRollbackAndProposals()
	{
		RtpPortalRuntime runtime = RtpPortalRuntime.shared(24L, RtpRotationMode.STATIC, 1_000L);
		RtpDestination active = destination("active");
		RtpDestination standby = destination("standby");
		fillShared(runtime, active, standby, 0L);
		assertTrue(runtime.startManualReroll());
		RtpPortalRuntime.SearchTicket duplicateTicket = runtime.beginSearch().orElseThrow();
		assertEquals(RtpPortalRuntime.SearchCompletion.DUPLICATE, runtime.completeSearch(duplicateTicket, active, 1L));
		assertTrue(runtime.snapshot().rerolling());
		assertEquals(2, runtime.snapshot().candidateCount());
	}

	@Test
	public void candidateUniquenessUsesWorldColumnAcrossDestinationMetadata()
	{
		RtpPortalRuntime runtime = RtpPortalRuntime.shared(25L, RtpRotationMode.STATIC, 1_000L);
		RtpDestination active = new RtpDestination("minecraft:overworld", 12, 64, -7, 25L, 1);
		RtpDestination duplicateColumn = new RtpDestination("minecraft:overworld", 12, 92, -7, 25L, 2);
		RtpPortalRuntime.SearchTicket activeTicket = runtime.beginSearch().orElseThrow();
		assertEquals(RtpPortalRuntime.SearchCompletion.ADDED, runtime.completeSearch(activeTicket, active, 0L));

		RtpPortalRuntime.SearchTicket standbyTicket = runtime.beginSearch().orElseThrow();

		assertEquals(RtpPortalRuntime.SearchCompletion.DUPLICATE, runtime.completeSearch(standbyTicket, duplicateColumn, 0L));
		assertFalse(runtime.snapshot().ready());
		assertEquals(1, runtime.snapshot().candidateCount());
	}

	@Test
	public void perPlayerCapacityTargetsInterestAndReservationsWithHardCap()
	{
		RtpPortalRuntime runtime = RtpPortalRuntime.perPlayer(30L, 100L);
		List<UUID> players = new ArrayList<>();
		for (int index = 0; index < 20; index++)
		{
			UUID playerId = uuid("player-" + index);
			players.add(playerId);
			assertTrue(runtime.touchPlayer(playerId));
		}
		assertEquals(16, runtime.snapshot().targetCandidateCount());
		for (int index = 0; index < 16; index++)
		{
			RtpPortalRuntime.SearchTicket ticket = runtime.beginSearch().orElseThrow();
			assertEquals(RtpPortalRuntime.SearchPurpose.PER_PLAYER_POOL, ticket.purpose());
			assertEquals(RtpPortalRuntime.SearchCompletion.ADDED, runtime.completeSearch(ticket, destination("candidate-" + index), 0L));
		}
		assertTrue(runtime.beginSearch().isEmpty());
		for (int index = 0; index < 14; index++)
		{
			assertTrue(runtime.reservePlayer(players.get(index)).isPresent());
		}
		assertTrue(runtime.reservePlayer(players.get(14)).isEmpty());
		RtpRuntimeSnapshot snapshot = runtime.snapshot();
		assertEquals(16, snapshot.candidateCount());
		assertEquals(14, snapshot.reservedPlayers());
		assertEquals(2, snapshot.freeCandidates());
		assertEquals(2, snapshot.freeEntries().size());
		assertThrows(UnsupportedOperationException.class, () -> snapshot.freeEntries().clear());
	}

	@Test
	public void capacityCountsReleasingReservationsAndUnassignedInterest()
	{
		RtpPortalRuntime runtime = RtpPortalRuntime.perPlayer(37L, 100L);
		UUID departingPlayer = uuid("departing");
		UUID arrivingPlayer = uuid("arriving");
		runtime.touchPlayer(departingPlayer);
		fillPerPlayer(runtime, 3, "mixed-demand");
		runtime.reservePlayer(departingPlayer).orElseThrow();
		runtime.leavePlayer(departingPlayer, 1_000L);

		runtime.touchPlayer(arrivingPlayer);

		assertEquals(4, runtime.snapshot().targetCandidateCount());
		assertTrue(runtime.beginSearch().isPresent());
	}

	@Test
	public void playerLeaveDeadlineReleasesOrRestoresSameReservation()
	{
		RtpPortalRuntime runtime = RtpPortalRuntime.perPlayer(31L, 100L);
		UUID playerId = uuid("player");
		runtime.touchPlayer(playerId);
		fillPerPlayer(runtime, 3, "leave");
		RtpDestination reserved = runtime.reservePlayer(playerId).orElseThrow();
		assertTrue(runtime.leavePlayer(playerId, 1_000L));
		assertEquals(1, runtime.snapshot().releasingPlayers());
		assertEquals(0, runtime.expireReservations(1_099L));
		assertTrue(runtime.touchPlayer(playerId));
		assertSame(reserved, runtime.reservationFor(playerId).orElseThrow());
		assertEquals(0, runtime.snapshot().releasingPlayers());
		assertTrue(runtime.leavePlayer(playerId, 2_000L));
		assertEquals(1, runtime.expireReservations(2_100L));
		assertTrue(runtime.reservationFor(playerId).isEmpty());
		assertEquals(3, runtime.snapshot().freeCandidates());
	}

	@Test
	public void perPlayerRotationSwapsFromTheWarmPoolAtTheConfiguredDeadline()
	{
		RtpPortalRuntime runtime = RtpPortalRuntime.perPlayer(38L, 100L, 1_000L);
		UUID playerId = uuid("rotating-player");
		runtime.touchPlayer(playerId);
		fillPerPlayer(runtime, 3, "rotating");
		RtpDestination initial = runtime.reservePlayer(playerId, 100L).orElseThrow();

		assertEquals(1_100L, runtime.snapshot().nextRotationAtMillis());
		assertEquals(0, runtime.rotatePlayerReservations(1_099L));
		assertEquals(1, runtime.rotatePlayerReservations(1_100L));
		RtpDestination replacement = runtime.reservationFor(playerId).orElseThrow();

		assertFalse(initial.equals(replacement));
		assertTrue(runtime.snapshot().freeEntries().contains(initial));
		assertEquals(2_100L, runtime.snapshot().nextRotationAtMillis());
	}

	@Test
	public void repeatedLeaveDoesNotExtendReservationDeadline()
	{
		RtpPortalRuntime runtime = RtpPortalRuntime.perPlayer(36L, 100L);
		UUID playerId = uuid("player");
		runtime.touchPlayer(playerId);
		fillPerPlayer(runtime, 3, "idempotent-leave");
		runtime.reservePlayer(playerId).orElseThrow();

		assertTrue(runtime.leavePlayer(playerId, 1_000L));
		assertFalse(runtime.leavePlayer(playerId, 1_050L));
		assertEquals(1, runtime.expireReservations(1_100L));
	}

	@Test
	public void privateTraversalConsumesOnSuccessAndRestoresOnFailure()
	{
		RtpPortalRuntime runtime = RtpPortalRuntime.perPlayer(32L, 100L);
		UUID playerId = uuid("player");
		runtime.touchPlayer(playerId);
		fillPerPlayer(runtime, 3, "private");
		RtpDestination firstReservation = runtime.reservePlayer(playerId).orElseThrow();
		RtpPortalRuntime.TraversalClaim failedClaim = runtime.claimPlayer(uuid("failed-claim"), playerId).orElseThrow();
		assertSame(firstReservation, failedClaim.destination());
		assertTrue(runtime.completeTraversal(failedClaim, false, 10L));
		assertSame(firstReservation, runtime.reservationFor(playerId).orElseThrow());

		RtpPortalRuntime.TraversalClaim successfulClaim = runtime.claimPlayer(uuid("successful-claim"), playerId).orElseThrow();
		assertTrue(runtime.completeTraversal(successfulClaim, true, 20L));
		assertTrue(runtime.reservationFor(playerId).isEmpty());
		assertEquals(2, runtime.snapshot().candidateCount());
		assertEquals(3, runtime.snapshot().targetCandidateCount());
		assertTrue(runtime.beginSearch().isPresent());
	}

	@Test
	public void failedPrivateTraversalAfterLeaveRestoresReservationUntilDeadline()
	{
		RtpPortalRuntime runtime = RtpPortalRuntime.perPlayer(34L, 100L);
		UUID playerId = uuid("departing-player");
		runtime.touchPlayer(playerId);
		fillPerPlayer(runtime, 3, "departing");
		RtpDestination reserved = runtime.reservePlayer(playerId).orElseThrow();
		RtpPortalRuntime.TraversalClaim claim = runtime.claimPlayer(uuid("departing-claim"), playerId).orElseThrow();
		assertTrue(runtime.leavePlayer(playerId, 900L));

		assertTrue(runtime.completeTraversal(claim, false, 1_000L));
		assertSame(reserved, runtime.reservationFor(playerId).orElseThrow());
		assertEquals(1, runtime.snapshot().releasingPlayers());
		assertEquals(0, runtime.expireReservations(1_099L));
		assertEquals(1, runtime.expireReservations(1_100L));
	}

	@Test
	public void nonPlayerClaimsRemainAnonymousAndParticipateInCapacity()
	{
		RtpPortalRuntime runtime = RtpPortalRuntime.perPlayer(33L, 100L);
		fillPerPlayer(runtime, 2, "anonymous");
		RtpPortalRuntime.TraversalClaim claim = runtime.claimAnonymous(uuid("mob-claim")).orElseThrow();
		assertNull(claim.playerId());
		assertEquals(RtpPortalRuntime.ClaimKind.ANONYMOUS, claim.kind());
		assertEquals(1, runtime.snapshot().anonymousClaims());
		assertEquals(3, runtime.snapshot().targetCandidateCount());
		assertTrue(runtime.completeTraversal(claim, false, 1L));
		assertEquals(0, runtime.snapshot().anonymousClaims());
		assertEquals(2, runtime.snapshot().freeCandidates());
	}

	@Test
	public void rebuildInvalidatesUnusedEntriesAndStalesCurrentSearch()
	{
		RtpPortalRuntime runtime = RtpPortalRuntime.perPlayer(35L, 100L);
		UUID playerId = uuid("player");
		runtime.touchPlayer(playerId);
		runtime.touchPlayer(uuid("other-player"));
		runtime.touchPlayer(uuid("third-player"));
		fillPerPlayer(runtime, 4, "rebuild");
		RtpDestination reservation = runtime.reservePlayer(playerId).orElseThrow();
		RtpPortalRuntime.TraversalClaim anonymousClaim = runtime.claimAnonymous(uuid("anonymous-claim")).orElseThrow();
		RtpPortalRuntime.SearchTicket staleTicket = runtime.beginSearch().orElseThrow();

		Set<RtpDestination> invalidated = runtime.rebuildPool();

		assertEquals(2, invalidated.size());
		assertTrue(invalidated.stream().noneMatch(destination -> destination == reservation || destination == anonymousClaim.destination()));
		assertEquals(2, runtime.snapshot().candidateCount());
		assertSame(reservation, runtime.reservationFor(playerId).orElseThrow());
		assertEquals(1, runtime.snapshot().anonymousClaims());
		assertEquals(RtpPortalRuntime.SearchCompletion.STALE, runtime.completeSearch(staleTicket, destination("late"), 0L));
	}

	@Test
	public void staleTraversalClaimCannotMutateAnotherGeneration()
	{
		RtpPortalRuntime oldRuntime = RtpPortalRuntime.shared(40L, RtpRotationMode.ON_TRAVERSAL, 1_000L);
		fillShared(oldRuntime, destination("active"), destination("standby"), 0L);
		RtpPortalRuntime.TraversalClaim claim = oldRuntime.claimShared(uuid("claim")).orElseThrow();
		RtpPortalRuntime replacementRuntime = RtpPortalRuntime.shared(41L, RtpRotationMode.ON_TRAVERSAL, 1_000L);
		fillShared(replacementRuntime, destination("replacement-active"), destination("replacement-standby"), 0L);

		assertFalse(replacementRuntime.completeTraversal(claim, true, 0L));
		assertTrue(replacementRuntime.snapshot().ready());
	}

	private void fillShared(RtpPortalRuntime runtime, RtpDestination active, RtpDestination standby, long nowMillis)
	{
		RtpPortalRuntime.SearchTicket activeTicket = runtime.beginSearch().orElseThrow();
		assertEquals(RtpPortalRuntime.SearchCompletion.ADDED, runtime.completeSearch(activeTicket, active, nowMillis));
		RtpPortalRuntime.SearchTicket standbyTicket = runtime.beginSearch().orElseThrow();
		assertEquals(RtpPortalRuntime.SearchCompletion.ADDED, runtime.completeSearch(standbyTicket, standby, nowMillis));
	}

	private void fillPerPlayer(RtpPortalRuntime runtime, int count, String prefix)
	{
		for (int index = 0; index < count; index++)
		{
			RtpPortalRuntime.SearchTicket ticket = runtime.beginSearch().orElseThrow();
			assertEquals(RtpPortalRuntime.SearchCompletion.ADDED, runtime.completeSearch(ticket, destination(prefix + "-" + index), 0L));
		}
	}

	private RtpDestination destination(String name)
	{
		try
		{
			Constructor<?>[] constructors = RtpDestination.class.getDeclaredConstructors();
			if (constructors.length != 1)
			{
				throw new AssertionError("RtpDestination must have one canonical constructor");
			}
			Constructor<?> constructor = constructors[0];
			constructor.setAccessible(true);
			Class<?>[] parameterTypes = constructor.getParameterTypes();
			Object[] arguments = new Object[parameterTypes.length];
			int number = Math.floorMod(name.hashCode(), 10_000) + 1;
			for (int index = 0; index < parameterTypes.length; index++)
			{
				Class<?> parameterType = parameterTypes[index];
				if (parameterType == UUID.class)
				{
					arguments[index] = uuid(name + "-" + index);
				}
				else if (parameterType == String.class)
				{
					arguments[index] = "minecraft:" + name.replace('_', '-');
				}
				else if (parameterType == double.class || parameterType == Double.class)
				{
					arguments[index] = (number % 100) + (index * 0.125D);
				}
				else if (parameterType == long.class || parameterType == Long.class)
				{
					arguments[index] = (long) number + index;
				}
				else if (parameterType == int.class || parameterType == Integer.class)
				{
					arguments[index] = number + index;
				}
				else if (parameterType == boolean.class || parameterType == Boolean.class)
				{
					arguments[index] = false;
				}
				else if (parameterType.isEnum())
				{
					arguments[index] = parameterType.getEnumConstants()[0];
				}
				else
				{
					throw new AssertionError("Unsupported RtpDestination constructor type: " + parameterType.getName());
				}
			}
			return (RtpDestination) constructor.newInstance(arguments);
		}
		catch (ReflectiveOperationException exception)
		{
			throw new AssertionError(exception);
		}
	}

	private UUID uuid(String value)
	{
		return UUID.nameUUIDFromBytes(value.getBytes(StandardCharsets.UTF_8));
	}
}
