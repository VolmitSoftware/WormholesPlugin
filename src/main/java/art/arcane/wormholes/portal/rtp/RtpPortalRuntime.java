package art.arcane.wormholes.portal.rtp;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

public final class RtpPortalRuntime
{
	private static final int PER_PLAYER_HARD_CAP = 16;
	private static final int REQUIRED_FREE_SPARES = 2;
	private static final long NO_LEAVE_DEADLINE = -1L;

	private final long generation;
	private final RtpAllocationMode allocationMode;
	private final RtpRotationMode rotationMode;
	private final long cycleDurationMillis;
	private final long reservationLeaveMillis;
	private final Set<RtpDestination> knownDestinations;
	private final Set<DestinationColumn> knownColumns;
	private final Set<RtpDestination> freeDestinations;
	private final Set<UUID> interestedPlayers;
	private final Map<UUID, Reservation> reservations;
	private final Map<UUID, TraversalClaim> sharedClaims;
	private final Map<UUID, TraversalClaim> playerClaims;
	private final Map<UUID, TraversalClaim> anonymousClaims;

	private RtpDestination active;
	private RtpDestination standby;
	private RtpDestination proposedActive;
	private RtpDestination proposedStandby;
	private SearchTicket activeSearch;
	private long searchEpoch;
	private long nextSearchSequence;
	private long routeRevision;
	private long nextRotationAtMillis;
	private long rerollRollbackDeadlineMillis;
	private boolean timedRotationPending;
	private boolean rerolling;

	private RtpPortalRuntime(long generation, Configuration configuration)
	{
		this.generation = generation;
		this.allocationMode = configuration.allocationMode();
		this.rotationMode = configuration.rotationMode();
		this.cycleDurationMillis = configuration.cycleDurationMillis();
		this.reservationLeaveMillis = configuration.reservationLeaveMillis();
		this.knownDestinations = new LinkedHashSet<>();
		this.knownColumns = new LinkedHashSet<>();
		this.freeDestinations = new LinkedHashSet<>();
		this.interestedPlayers = new LinkedHashSet<>();
		this.reservations = new LinkedHashMap<>();
		this.sharedClaims = new LinkedHashMap<>();
		this.playerClaims = new LinkedHashMap<>();
		this.anonymousClaims = new LinkedHashMap<>();
		this.searchEpoch = 1L;
	}

	public static RtpPortalRuntime shared(long generation, RtpRotationMode rotationMode, long cycleDurationMillis)
	{
		if (cycleDurationMillis <= 0L)
		{
			throw new IllegalArgumentException("Cycle duration must be positive");
		}
		return new RtpPortalRuntime(generation, new Configuration(RtpAllocationMode.SHARED, rotationMode, cycleDurationMillis, 0L));
	}

	public static RtpPortalRuntime perPlayer(long generation, long reservationLeaveMillis)
	{
		if (reservationLeaveMillis <= 0L)
		{
			throw new IllegalArgumentException("Reservation leave duration must be positive");
		}
		return new RtpPortalRuntime(generation, new Configuration(RtpAllocationMode.PER_PLAYER, RtpRotationMode.STATIC, 0L, reservationLeaveMillis));
	}

	public static RtpPortalRuntime perPlayer(long generation, long reservationLeaveMillis, long rotationDurationMillis)
	{
		if(reservationLeaveMillis <= 0L)
		{
			throw new IllegalArgumentException("Reservation leave duration must be positive");
		}
		if(rotationDurationMillis <= 0L)
		{
			throw new IllegalArgumentException("Rotation duration must be positive");
		}
		return new RtpPortalRuntime(generation, new Configuration(
				RtpAllocationMode.PER_PLAYER,
				RtpRotationMode.TIMED,
				rotationDurationMillis,
				reservationLeaveMillis));
	}

	public synchronized Optional<SearchTicket> beginSearch()
	{
		if (activeSearch != null)
		{
			return Optional.empty();
		}
		SearchPurpose purpose = nextSearchPurpose();
		if (purpose == null)
		{
			return Optional.empty();
		}
		SearchTicket ticket = new SearchTicket(generation, searchEpoch, ++nextSearchSequence, purpose);
		activeSearch = ticket;
		return Optional.of(ticket);
	}

	public synchronized SearchCompletion completeSearch(SearchTicket ticket, RtpDestination destination, long nowMillis)
	{
		Objects.requireNonNull(ticket);
		Objects.requireNonNull(destination);
		if (!isCurrentSearch(ticket))
		{
			return SearchCompletion.STALE;
		}
		activeSearch = null;
		if (ticket.purpose() != nextSearchPurpose())
		{
			return SearchCompletion.STALE;
		}
		DestinationColumn column = DestinationColumn.from(destination);
		if (knownColumns.contains(column))
		{
			return SearchCompletion.DUPLICATE;
		}
		knownDestinations.add(destination);
		knownColumns.add(column);
		applySearchResult(ticket.purpose(), destination, nowMillis);
		return SearchCompletion.ADDED;
	}

	public synchronized boolean failSearch(SearchTicket ticket, long nowMillis)
	{
		Objects.requireNonNull(ticket);
		if (!isCurrentSearch(ticket))
		{
			return false;
		}
		activeSearch = null;
		if (rerolling && (ticket.purpose() == SearchPurpose.REROLL_ACTIVE || ticket.purpose() == SearchPurpose.REROLL_STANDBY))
		{
			rollbackManualReroll(nowMillis);
		}
		return true;
	}

	public synchronized boolean startManualReroll()
	{
		if (allocationMode != RtpAllocationMode.SHARED || active == null || standby == null || rerolling
				|| timedRotationPending || activeSearch != null || !sharedClaims.isEmpty())
		{
			return false;
		}
		rerolling = true;
		rerollRollbackDeadlineMillis = nextRotationAtMillis;
		nextRotationAtMillis = 0L;
		proposedActive = null;
		proposedStandby = null;
		return true;
	}

	public synchronized boolean advanceTimedRotation(long nowMillis, boolean attended)
	{
		if (allocationMode != RtpAllocationMode.SHARED || rotationMode != RtpRotationMode.TIMED)
		{
			return false;
		}
		if (timedRotationPending)
		{
			if (!attended || !sharedClaims.isEmpty())
			{
				return false;
			}
			promoteTimedStandby();
			return true;
		}
		if (nextRotationAtMillis == 0L || nowMillis < nextRotationAtMillis)
		{
			return false;
		}
		timedRotationPending = true;
		nextRotationAtMillis = 0L;
		if (attended && sharedClaims.isEmpty())
		{
			promoteTimedStandby();
		}
		return true;
	}

	public synchronized boolean touchPlayer(UUID playerId)
	{
		requirePerPlayer();
		Objects.requireNonNull(playerId);
		boolean changed = interestedPlayers.add(playerId);
		Reservation reservation = reservations.get(playerId);
		if (reservation != null && reservation.leaveDeadlineMillis() != NO_LEAVE_DEADLINE)
		{
			reservations.put(playerId, reservation.active());
			changed = true;
		}
		return changed;
	}

	public synchronized boolean leavePlayer(UUID playerId, long nowMillis)
	{
		requirePerPlayer();
		Objects.requireNonNull(playerId);
		boolean changed = interestedPlayers.remove(playerId);
		Reservation reservation = reservations.get(playerId);
		if (reservation != null && reservation.leaveDeadlineMillis() == NO_LEAVE_DEADLINE)
		{
			reservations.put(playerId, reservation.releasing(deadline(nowMillis, reservationLeaveMillis)));
			changed = true;
		}
		return changed;
	}

	public synchronized int expireReservations(long nowMillis)
	{
		requirePerPlayer();
		int released = 0;
		Iterator<Map.Entry<UUID, Reservation>> iterator = reservations.entrySet().iterator();
		while (iterator.hasNext())
		{
			Map.Entry<UUID, Reservation> entry = iterator.next();
			Reservation reservation = entry.getValue();
			if (reservation.leaveDeadlineMillis() == NO_LEAVE_DEADLINE || nowMillis < reservation.leaveDeadlineMillis())
			{
				continue;
			}
			iterator.remove();
			freeDestinations.add(reservation.destination());
			released++;
		}
		return released;
	}

	public synchronized Optional<RtpDestination> reservePlayer(UUID playerId)
	{
		return reservePlayer(playerId, 0L);
	}

	public synchronized Optional<RtpDestination> reservePlayer(UUID playerId, long nowMillis)
	{
		requirePerPlayer();
		Objects.requireNonNull(playerId);
		Reservation existing = reservations.get(playerId);
		if (existing != null)
		{
			return Optional.of(existing.destination());
		}
		if (!interestedPlayers.contains(playerId) || hasPlayerClaim(playerId) || freeDestinations.size() <= REQUIRED_FREE_SPARES)
		{
			return Optional.empty();
		}
		RtpDestination destination = removeFirstFreeDestination();
		reservations.put(playerId, new Reservation(destination, NO_LEAVE_DEADLINE, nextPlayerRotationDeadline(nowMillis)));
		return Optional.of(destination);
	}

	public synchronized int rotatePlayerReservations(long nowMillis)
	{
		requirePerPlayer();
		if(rotationMode != RtpRotationMode.TIMED || freeDestinations.isEmpty())
		{
			return 0;
		}
		int rotated = 0;
		for(Map.Entry<UUID, Reservation> entry : reservations.entrySet())
		{
			Reservation reservation = entry.getValue();
			if(reservation.leaveDeadlineMillis() != NO_LEAVE_DEADLINE
					|| !interestedPlayers.contains(entry.getKey())
					|| reservation.rotationDeadlineMillis() == 0L
					|| nowMillis < reservation.rotationDeadlineMillis()
					|| freeDestinations.isEmpty())
			{
				continue;
			}
			RtpDestination replacement = removeFirstFreeDestination();
			freeDestinations.add(reservation.destination());
			entry.setValue(new Reservation(replacement, NO_LEAVE_DEADLINE, nextPlayerRotationDeadline(nowMillis)));
			rotated++;
		}
		return rotated;
	}

	public synchronized Optional<RtpDestination> reservationFor(UUID playerId)
	{
		requirePerPlayer();
		Objects.requireNonNull(playerId);
		Reservation reservation = reservations.get(playerId);
		return reservation == null ? Optional.empty() : Optional.of(reservation.destination());
	}

	public synchronized Optional<TraversalClaim> claimShared(UUID claimId)
	{
		requireShared();
		Objects.requireNonNull(claimId);
		if (!sharedReady() || claimIdInUse(claimId))
		{
			return Optional.empty();
		}
		if (rotationMode == RtpRotationMode.ON_TRAVERSAL && !sharedClaims.isEmpty())
		{
			return Optional.empty();
		}
		TraversalClaim claim = new TraversalClaim(claimId, generation, ClaimKind.SHARED, null, active);
		sharedClaims.put(claimId, claim);
		return Optional.of(claim);
	}

	public synchronized Optional<TraversalClaim> claimPlayer(UUID claimId, UUID playerId)
	{
		requirePerPlayer();
		Objects.requireNonNull(claimId);
		Objects.requireNonNull(playerId);
		if (claimIdInUse(claimId) || !interestedPlayers.contains(playerId) || hasPlayerClaim(playerId))
		{
			return Optional.empty();
		}
		Reservation reservation = reservations.get(playerId);
		if (reservation == null || reservation.leaveDeadlineMillis() != NO_LEAVE_DEADLINE)
		{
			return Optional.empty();
		}
		reservations.remove(playerId);
		TraversalClaim claim = new TraversalClaim(claimId, generation, ClaimKind.PLAYER, playerId, reservation.destination());
		playerClaims.put(claimId, claim);
		return Optional.of(claim);
	}

	public synchronized Optional<TraversalClaim> claimAnonymous(UUID claimId)
	{
		requirePerPlayer();
		Objects.requireNonNull(claimId);
		if (claimIdInUse(claimId) || freeDestinations.isEmpty())
		{
			return Optional.empty();
		}
		RtpDestination destination = removeFirstFreeDestination();
		TraversalClaim claim = new TraversalClaim(claimId, generation, ClaimKind.ANONYMOUS, null, destination);
		anonymousClaims.put(claimId, claim);
		return Optional.of(claim);
	}

	public synchronized boolean completeTraversal(TraversalClaim claim, boolean succeeded, long nowMillis)
	{
		Objects.requireNonNull(claim);
		if (claim.generation() != generation)
		{
			return false;
		}
		return switch (claim.kind())
		{
			case SHARED -> completeSharedTraversal(claim, succeeded);
			case PLAYER -> completePlayerTraversal(claim, succeeded, nowMillis);
			case ANONYMOUS -> completeAnonymousTraversal(claim, succeeded);
		};
	}

	public synchronized Set<RtpDestination> rebuildPool()
	{
		requirePerPlayer();
		Set<RtpDestination> invalidated = Set.copyOf(freeDestinations);
		for (RtpDestination destination : freeDestinations)
		{
			removeKnownDestination(destination);
		}
		freeDestinations.clear();
		invalidateSearch();
		return invalidated;
	}

	public synchronized boolean invalidateDestination(RtpDestination destination)
	{
		Objects.requireNonNull(destination);
		if (!knownDestinations.contains(destination) || rerolling || timedRotationPending || destinationClaimed(destination))
		{
			return false;
		}
		if (destination.equals(active))
		{
			active = null;
		}
		if (destination.equals(standby))
		{
			standby = null;
		}
		freeDestinations.remove(destination);
		Iterator<Map.Entry<UUID, Reservation>> iterator = reservations.entrySet().iterator();
		while (iterator.hasNext())
		{
			if (destination.equals(iterator.next().getValue().destination()))
			{
				iterator.remove();
			}
		}
		removeKnownDestination(destination);
		return true;
	}

	private boolean destinationClaimed(RtpDestination destination)
	{
		for (TraversalClaim claim : sharedClaims.values())
		{
			if (destination.equals(claim.destination()))
			{
				return true;
			}
		}
		for (TraversalClaim claim : playerClaims.values())
		{
			if (destination.equals(claim.destination()))
			{
				return true;
			}
		}
		for (TraversalClaim claim : anonymousClaims.values())
		{
			if (destination.equals(claim.destination()))
			{
				return true;
			}
		}
		return false;
	}

	public synchronized RtpRuntimeSnapshot snapshot()
	{
		int releasingPlayers = 0;
		for (Reservation reservation : reservations.values())
		{
			if (reservation.leaveDeadlineMillis() != NO_LEAVE_DEADLINE)
			{
				releasingPlayers++;
			}
		}
		return new RtpRuntimeSnapshot(
				generation,
				allocationMode,
				rotationMode,
				allocationMode == RtpAllocationMode.SHARED ? sharedReady() : perPlayerReady(),
				active,
				standby,
				routeRevision,
				allocationMode == RtpAllocationMode.PER_PLAYER ? nextPlayerRotationAtMillis() : nextRotationAtMillis,
				timedRotationPending,
				rerolling,
				activeSearch != null,
				knownDestinations.size(),
				targetCandidateCount(),
				interestedPlayers.size(),
				freeDestinations.size(),
				List.copyOf(freeDestinations),
				reservations.size(),
				releasingPlayers,
				sharedClaims.size(),
				playerClaims.size(),
				anonymousClaims.size());
	}

	private SearchPurpose nextSearchPurpose()
	{
		if (allocationMode == RtpAllocationMode.PER_PLAYER)
		{
			return knownDestinations.size() < targetCandidateCount() && knownDestinations.size() < PER_PLAYER_HARD_CAP
					? SearchPurpose.PER_PLAYER_POOL
					: null;
		}
		if (rerolling)
		{
			if (proposedActive == null)
			{
				return SearchPurpose.REROLL_ACTIVE;
			}
			return proposedStandby == null ? SearchPurpose.REROLL_STANDBY : null;
		}
		if (active == null)
		{
			return SearchPurpose.SHARED_ACTIVE;
		}
		return standby == null ? SearchPurpose.SHARED_STANDBY : null;
	}

	private boolean isCurrentSearch(SearchTicket ticket)
	{
		return ticket.generation() == generation && ticket.epoch() == searchEpoch && ticket.equals(activeSearch);
	}

	private void applySearchResult(SearchPurpose purpose, RtpDestination destination, long nowMillis)
	{
		switch (purpose)
		{
			case SHARED_ACTIVE ->
			{
				active = destination;
				if (routeRevision == 0L)
				{
					routeRevision = 1L;
				}
			}
			case SHARED_STANDBY ->
			{
				standby = destination;
				onSharedPairReady(nowMillis);
			}
			case REROLL_ACTIVE -> proposedActive = destination;
			case REROLL_STANDBY ->
			{
				proposedStandby = destination;
				commitManualReroll(nowMillis);
			}
			case PER_PLAYER_POOL -> freeDestinations.add(destination);
		}
	}

	private void onSharedPairReady(long nowMillis)
	{
		if (rotationMode == RtpRotationMode.TIMED && !timedRotationPending && nextRotationAtMillis == 0L)
		{
			nextRotationAtMillis = deadline(nowMillis, cycleDurationMillis);
		}
	}

	private void commitManualReroll(long nowMillis)
	{
		removeKnownDestination(active);
		removeKnownDestination(standby);
		active = proposedActive;
		standby = proposedStandby;
		proposedActive = null;
		proposedStandby = null;
		rerolling = false;
		rerollRollbackDeadlineMillis = 0L;
		timedRotationPending = false;
		routeRevision = nextRouteRevision();
		nextRotationAtMillis = rotationMode == RtpRotationMode.TIMED ? deadline(nowMillis, cycleDurationMillis) : 0L;
	}

	private void rollbackManualReroll(long nowMillis)
	{
		if (proposedActive != null)
		{
			removeKnownDestination(proposedActive);
		}
		if (proposedStandby != null)
		{
			removeKnownDestination(proposedStandby);
		}
		proposedActive = null;
		proposedStandby = null;
		rerolling = false;
		if (rotationMode != RtpRotationMode.TIMED)
		{
			rerollRollbackDeadlineMillis = 0L;
			return;
		}
		nextRotationAtMillis = rerollRollbackDeadlineMillis;
		rerollRollbackDeadlineMillis = 0L;
		if (nextRotationAtMillis != 0L && nowMillis >= nextRotationAtMillis)
		{
			timedRotationPending = true;
			nextRotationAtMillis = 0L;
		}
	}

	private void promoteTimedStandby()
	{
		if (standby == null)
		{
			return;
		}
		removeKnownDestination(active);
		active = standby;
		standby = null;
		timedRotationPending = false;
		nextRotationAtMillis = 0L;
		routeRevision = nextRouteRevision();
	}

	private boolean completeSharedTraversal(TraversalClaim claim, boolean succeeded)
	{
		TraversalClaim current = sharedClaims.get(claim.claimId());
		if (!claim.equals(current))
		{
			return false;
		}
		sharedClaims.remove(claim.claimId());
		if (rotationMode == RtpRotationMode.ON_TRAVERSAL && succeeded && claim.destination().equals(active))
		{
			removeKnownDestination(active);
			active = standby;
			standby = null;
			routeRevision = nextRouteRevision();
		}
		if (rotationMode == RtpRotationMode.TIMED && timedRotationPending && sharedClaims.isEmpty())
		{
			promoteTimedStandby();
		}
		return true;
	}

	private boolean completePlayerTraversal(TraversalClaim claim, boolean succeeded, long nowMillis)
	{
		TraversalClaim current = playerClaims.get(claim.claimId());
		if (!claim.equals(current))
		{
			return false;
		}
		playerClaims.remove(claim.claimId());
		if (succeeded)
		{
			removeKnownDestination(claim.destination());
			return true;
		}
		if (interestedPlayers.contains(claim.playerId()))
		{
			reservations.put(claim.playerId(), new Reservation(
					claim.destination(),
					NO_LEAVE_DEADLINE,
					nextPlayerRotationDeadline(nowMillis)));
		}
		else
		{
			reservations.put(claim.playerId(), new Reservation(
					claim.destination(),
					deadline(nowMillis, reservationLeaveMillis),
					nextPlayerRotationDeadline(nowMillis)));
		}
		return true;
	}

	private boolean completeAnonymousTraversal(TraversalClaim claim, boolean succeeded)
	{
		TraversalClaim current = anonymousClaims.get(claim.claimId());
		if (!claim.equals(current))
		{
			return false;
		}
		anonymousClaims.remove(claim.claimId());
		if (succeeded)
		{
			removeKnownDestination(claim.destination());
		}
		else
		{
			freeDestinations.add(claim.destination());
		}
		return true;
	}

	private boolean sharedReady()
	{
		if (active == null || standby == null || rerolling || timedRotationPending)
		{
			return false;
		}
		return rotationMode != RtpRotationMode.ON_TRAVERSAL || sharedClaims.isEmpty();
	}

	private boolean perPlayerReady()
	{
		for (Map.Entry<UUID, Reservation> entry : reservations.entrySet())
		{
			if (entry.getValue().leaveDeadlineMillis() == NO_LEAVE_DEADLINE && interestedPlayers.contains(entry.getKey()))
			{
				return true;
			}
		}
		return false;
	}

	private int targetCandidateCount()
	{
		if (allocationMode == RtpAllocationMode.SHARED)
		{
			return rerolling ? 4 : 2;
		}
		int committed = reservations.size() + playerClaims.size() + anonymousClaims.size();
		int unassignedInterest = 0;
		for (UUID playerId : interestedPlayers)
		{
			if (!reservations.containsKey(playerId) && !hasPlayerClaim(playerId))
			{
				unassignedInterest++;
			}
		}
		return Math.min(PER_PLAYER_HARD_CAP, committed + unassignedInterest + REQUIRED_FREE_SPARES);
	}

	private boolean hasPlayerClaim(UUID playerId)
	{
		for (TraversalClaim claim : playerClaims.values())
		{
			if (playerId.equals(claim.playerId()))
			{
				return true;
			}
		}
		return false;
	}

	private boolean claimIdInUse(UUID claimId)
	{
		return sharedClaims.containsKey(claimId) || playerClaims.containsKey(claimId) || anonymousClaims.containsKey(claimId);
	}

	private RtpDestination removeFirstFreeDestination()
	{
		Iterator<RtpDestination> iterator = freeDestinations.iterator();
		RtpDestination destination = iterator.next();
		iterator.remove();
		return destination;
	}

	private long nextPlayerRotationDeadline(long nowMillis)
	{
		return rotationMode == RtpRotationMode.TIMED ? deadline(nowMillis, cycleDurationMillis) : 0L;
	}

	private long nextPlayerRotationAtMillis()
	{
		long next = 0L;
		for(Map.Entry<UUID, Reservation> entry : reservations.entrySet())
		{
			Reservation reservation = entry.getValue();
			if(reservation.leaveDeadlineMillis() != NO_LEAVE_DEADLINE
					|| !interestedPlayers.contains(entry.getKey())
					|| reservation.rotationDeadlineMillis() == 0L)
			{
				continue;
			}
			next = next == 0L ? reservation.rotationDeadlineMillis() : Math.min(next, reservation.rotationDeadlineMillis());
		}
		return next;
	}

	private void removeKnownDestination(RtpDestination destination)
	{
		if (knownDestinations.remove(destination))
		{
			knownColumns.remove(DestinationColumn.from(destination));
		}
	}

	private void invalidateSearch()
	{
		activeSearch = null;
		searchEpoch++;
	}

	private long nextRouteRevision()
	{
		return routeRevision == Long.MAX_VALUE ? 1L : routeRevision + 1L;
	}

	private long deadline(long nowMillis, long durationMillis)
	{
		if (durationMillis > Long.MAX_VALUE - nowMillis)
		{
			return Long.MAX_VALUE;
		}
		return nowMillis + durationMillis;
	}

	private void requireShared()
	{
		if (allocationMode != RtpAllocationMode.SHARED)
		{
			throw new IllegalStateException("Runtime is not shared");
		}
	}

	private void requirePerPlayer()
	{
		if (allocationMode != RtpAllocationMode.PER_PLAYER)
		{
			throw new IllegalStateException("Runtime is not per-player");
		}
	}

	public enum SearchPurpose
	{
		SHARED_ACTIVE,
		SHARED_STANDBY,
		REROLL_ACTIVE,
		REROLL_STANDBY,
		PER_PLAYER_POOL
	}

	public enum SearchCompletion
	{
		ADDED,
		DUPLICATE,
		STALE
	}

	public enum ClaimKind
	{
		SHARED,
		PLAYER,
		ANONYMOUS
	}

	public record SearchTicket(long generation, long epoch, long sequence, SearchPurpose purpose)
	{
		public SearchTicket
		{
			Objects.requireNonNull(purpose);
		}
	}

	public record TraversalClaim(UUID claimId, long generation, ClaimKind kind, UUID playerId, RtpDestination destination)
	{
		public TraversalClaim
		{
			Objects.requireNonNull(claimId);
			Objects.requireNonNull(kind);
			Objects.requireNonNull(destination);
			if (kind == ClaimKind.PLAYER && playerId == null)
			{
				throw new IllegalArgumentException("Player claim requires a player ID");
			}
			if (kind != ClaimKind.PLAYER && playerId != null)
			{
				throw new IllegalArgumentException("Only player claims may have a player ID");
			}
		}
	}

	private record DestinationColumn(String worldKey, int blockX, int blockZ)
	{
		private DestinationColumn
		{
			Objects.requireNonNull(worldKey);
		}

		private static DestinationColumn from(RtpDestination destination)
		{
			return new DestinationColumn(destination.worldKey(), destination.blockX(), destination.blockZ());
		}
	}

	private record Configuration(RtpAllocationMode allocationMode, RtpRotationMode rotationMode, long cycleDurationMillis,
			long reservationLeaveMillis)
	{
		private Configuration
		{
			Objects.requireNonNull(allocationMode);
			Objects.requireNonNull(rotationMode);
		}
	}

	private record Reservation(RtpDestination destination, long leaveDeadlineMillis, long rotationDeadlineMillis)
	{
		private Reservation
		{
			Objects.requireNonNull(destination);
		}

		private Reservation active()
		{
			return new Reservation(destination, NO_LEAVE_DEADLINE, rotationDeadlineMillis);
		}

		private Reservation releasing(long deadlineMillis)
		{
			return new Reservation(destination, deadlineMillis, rotationDeadlineMillis);
		}
	}
}
