package art.arcane.wormholes.portal.rtp;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

public final class RtpService
{
	private static final int MAXIMUM_SEARCH_ATTEMPTS = 32;
	private static final long SEARCH_DEADLINE_MILLIS = 5_000L;
	private static final long SEARCH_RETRY_MILLIS = 1_000L;
	private static final long TRAVERSAL_PREPARATION_DEADLINE_MILLIS = 5_000L;
	private static final long TRAVERSAL_DISPATCH_DEADLINE_MILLIS = 10_000L;

	private final Dependencies dependencies;
	private final ConcurrentMap<UUID, PortalEntry> entries;
	private final ConcurrentMap<UUID, Snapshot> published;

	public RtpService(Dependencies dependencies)
	{
		this.dependencies = Objects.requireNonNull(dependencies, "dependencies");
		this.entries = new ConcurrentHashMap<UUID, PortalEntry>();
		this.published = new ConcurrentHashMap<UUID, Snapshot>();
	}

	public CompletableFuture<Snapshot> register(Registration registration)
	{
		Registration requiredRegistration = Objects.requireNonNull(registration, "registration");
		return onSource(requiredRegistration.portalId(), () -> registerOnSource(requiredRegistration));
	}

	public CompletableFuture<Boolean> unregister(UUID portalId)
	{
		UUID requiredPortalId = Objects.requireNonNull(portalId, "portalId");
		return onSource(requiredPortalId, () -> unregisterOnSource(requiredPortalId));
	}

	public CompletableFuture<Boolean> updateSettings(UUID portalId, RtpSettings settings)
	{
		UUID requiredPortalId = Objects.requireNonNull(portalId, "portalId");
		RtpSettings requiredSettings = Objects.requireNonNull(settings, "settings");
		return onSource(requiredPortalId, () -> updateSettingsOnSource(requiredPortalId, requiredSettings));
	}

	public CompletableFuture<Boolean> touchViewer(UUID portalId, UUID viewerId)
	{
		UUID requiredPortalId = Objects.requireNonNull(portalId, "portalId");
		UUID requiredViewerId = Objects.requireNonNull(viewerId, "viewerId");
		return onSource(requiredPortalId, () -> touchViewerOnSource(requiredPortalId, requiredViewerId));
	}

	public CompletableFuture<Boolean> leaveViewer(UUID portalId, UUID viewerId)
	{
		UUID requiredPortalId = Objects.requireNonNull(portalId, "portalId");
		UUID requiredViewerId = Objects.requireNonNull(viewerId, "viewerId");
		return onSource(requiredPortalId, () -> leaveViewerOnSource(requiredPortalId, requiredViewerId));
	}

	public CompletableFuture<Boolean> tick(UUID portalId)
	{
		UUID requiredPortalId = Objects.requireNonNull(portalId, "portalId");
		return onSource(requiredPortalId, () -> tickOnSource(requiredPortalId));
	}

	public CompletableFuture<Boolean> manualReroll(UUID portalId)
	{
		UUID requiredPortalId = Objects.requireNonNull(portalId, "portalId");
		return onSource(requiredPortalId, () -> manualRerollOnSource(requiredPortalId));
	}

	public CompletableFuture<Set<RtpDestination>> rebuildPool(UUID portalId)
	{
		UUID requiredPortalId = Objects.requireNonNull(portalId, "portalId");
		return onSource(requiredPortalId, () -> rebuildPoolOnSource(requiredPortalId));
	}

	public CompletableFuture<Optional<TraversalPreparation>> claimTraversal(UUID portalId, TraversalActor actor)
	{
		UUID requiredPortalId = Objects.requireNonNull(portalId, "portalId");
		TraversalActor requiredActor = Objects.requireNonNull(actor, "actor");
		CompletableFuture<Optional<TraversalPreparation>> result = new CompletableFuture<Optional<TraversalPreparation>>();
		try
		{
			dependencies.sourceDispatcher().execute(requiredPortalId, () -> beginTraversal(requiredPortalId, requiredActor, result));
		}
		catch(RuntimeException exception)
		{
			result.completeExceptionally(exception);
		}
		return result;
	}

	public CompletableFuture<Boolean> markTraversalDispatched(TraversalPreparation preparation)
	{
		TraversalPreparation requiredPreparation = Objects.requireNonNull(preparation, "preparation");
		return onSource(requiredPreparation.portalId(), () ->
		{
			boolean dispatched = requiredPreparation.request().markDispatched(requiredPreparation.generation());
			if(dispatched)
			{
				dependencies.sourceDispatcher().schedule(
						requiredPreparation.portalId(),
						() -> requiredPreparation.request().reconcileDispatchedTimeout(requiredPreparation.generation(), false),
						TRAVERSAL_DISPATCH_DEADLINE_MILLIS);
			}
			return dispatched;
		});
	}

	public CompletableFuture<Boolean> completeTraversal(TraversalPreparation preparation, boolean succeeded)
	{
		TraversalPreparation requiredPreparation = Objects.requireNonNull(preparation, "preparation");
		return onSource(requiredPreparation.portalId(), () -> succeeded
				? requiredPreparation.request().markSucceeded(requiredPreparation.generation())
				: requiredPreparation.request().markFailed(requiredPreparation.generation()));
	}

	public Optional<Snapshot> snapshot(UUID portalId)
	{
		return Optional.ofNullable(published.get(Objects.requireNonNull(portalId, "portalId")));
	}

	public RtpProjectionView projectionView(UUID portalId, UUID viewerId)
	{
		UUID requiredPortalId = Objects.requireNonNull(portalId, "portalId");
		UUID requiredViewerId = Objects.requireNonNull(viewerId, "viewerId");
		Snapshot snapshot = published.get(requiredPortalId);
		if(snapshot == null)
		{
			return RtpProjectionView.none(requiredViewerId, 0L);
		}
		RtpProjectionView view = snapshot.views().get(requiredViewerId);
		return view == null ? RtpProjectionView.none(requiredViewerId, snapshot.revision()) : view;
	}

	public Optional<RtpRimRenderer.Sample> rimSample(
			UUID portalId,
			UUID viewerId,
			RtpRimRenderer.Phase phase,
			long elapsedMillis)
	{
		UUID requiredPortalId = Objects.requireNonNull(portalId, "portalId");
		UUID requiredViewerId = Objects.requireNonNull(viewerId, "viewerId");
		RtpRimRenderer.Phase requiredPhase = Objects.requireNonNull(phase, "phase");
		Snapshot snapshot = published.get(requiredPortalId);
		if(snapshot == null)
		{
			return Optional.empty();
		}
		RtpProjectionView view = snapshot.views().getOrDefault(
				requiredViewerId,
				RtpProjectionView.none(requiredViewerId, snapshot.revision()));
		RtpRimRenderer.Input input = new RtpRimRenderer.Input(
				requiredViewerId,
				view,
				snapshot.settings().isRimEnabled(),
				snapshot.viewers().contains(requiredViewerId),
				snapshot.settings().getRotationMode(),
				requiredPhase,
				elapsedMillis,
				snapshot.settings().getCycleDurationMillis());
		return dependencies.rimRenderer().calculate(input);
	}

	private Snapshot registerOnSource(Registration registration)
	{
		PortalEntry previous = entries.get(registration.portalId());
		long generation = previous == null ? 1L : nextGeneration(previous.generation);
		Set<UUID> viewers = previous == null ? Set.of() : Set.copyOf(previous.viewers);
		PortalEntry replacement = new PortalEntry(registration, generation, createRuntime(generation, registration.settings()));
		replacement.viewers.addAll(viewers);
		entries.put(registration.portalId(), replacement);
		if(previous != null)
		{
			closeEntry(previous);
		}
		activateViewers(replacement);
		markChanged(replacement);
		maintain(replacement);
		return published.get(registration.portalId());
	}

	private boolean unregisterOnSource(UUID portalId)
	{
		PortalEntry entry = entries.remove(portalId);
		if(entry == null)
		{
			return false;
		}
		closeEntry(entry);
		published.remove(portalId);
		return true;
	}

	private boolean updateSettingsOnSource(UUID portalId, RtpSettings settings)
	{
		PortalEntry previous = entries.get(portalId);
		if(previous == null || previous.registration.settings().equals(settings))
		{
			return false;
		}
		Registration registration = new Registration(
				portalId,
				settings,
				previous.registration.centerX(),
				previous.registration.centerZ(),
				previous.registration.seed());
		long generation = nextGeneration(previous.generation);
		PortalEntry replacement = new PortalEntry(
				registration,
				generation,
				createRuntime(generation, settings));
		replacement.viewers.addAll(previous.viewers);
		entries.put(portalId, replacement);
		closeEntry(previous);
		activateViewers(replacement);
		markChanged(replacement);
		maintain(replacement);
		return true;
	}

	private boolean touchViewerOnSource(UUID portalId, UUID viewerId)
	{
		PortalEntry entry = entries.get(portalId);
		if(entry == null)
		{
			return false;
		}
		boolean changed = entry.viewers.add(viewerId);
		entry.idleToken = nextGeneration(entry.idleToken);
		RtpProjectionView currentView = entry.views.get(viewerId);
		boolean refreshAccess = changed
				|| currentView == null
				|| currentView.state() == RtpProjectionView.State.DENIED
				|| currentView.state() == RtpProjectionView.State.FAILED;
		if(refreshAccess)
		{
			entry.accessAttempts.remove(viewerId);
			entry.viewIdentities.remove(viewerId);
			entry.views.remove(viewerId);
		}
		if(entry.runtime.snapshot().allocationMode() == RtpAllocationMode.PER_PLAYER)
		{
			changed = entry.runtime.touchPlayer(viewerId) || changed;
		}
		if(changed || refreshAccess)
		{
			markChanged(entry);
		}
		maintain(entry);
		return changed;
	}

	private boolean leaveViewerOnSource(UUID portalId, UUID viewerId)
	{
		PortalEntry entry = entries.get(portalId);
		if(entry == null)
		{
			return false;
		}
		boolean changed = entry.viewers.remove(viewerId);
		entry.accessAttempts.remove(viewerId);
		entry.viewIdentities.remove(viewerId);
		entry.views.remove(viewerId);
		if(entry.runtime.snapshot().allocationMode() == RtpAllocationMode.PER_PLAYER)
		{
			changed = entry.runtime.leavePlayer(viewerId, dependencies.timeSource().nowMillis()) || changed;
		}
		if(entry.viewers.isEmpty())
		{
			scheduleIdle(entry);
		}
		markChanged(entry);
		maintain(entry);
		return changed;
	}

	private boolean tickOnSource(UUID portalId)
	{
		PortalEntry entry = entries.get(portalId);
		if(entry == null)
		{
			return false;
		}
		maintain(entry);
		return true;
	}

	private boolean manualRerollOnSource(UUID portalId)
	{
		PortalEntry entry = entries.get(portalId);
		if(entry == null || !entry.runtime.startManualReroll())
		{
			return false;
		}
		entry.nextSearchAllowedAtMillis = 0L;
		markChanged(entry);
		maintain(entry);
		return true;
	}

	private Set<RtpDestination> rebuildPoolOnSource(UUID portalId)
	{
		PortalEntry entry = entries.get(portalId);
		if(entry == null || entry.runtime.snapshot().allocationMode() != RtpAllocationMode.PER_PLAYER)
		{
			return Set.of();
		}
		Set<RtpDestination> invalidated = entry.runtime.rebuildPool();
		for(RtpDestination destination : invalidated)
		{
			closeRetention(entry, destination);
		}
		entry.nextSearchAllowedAtMillis = 0L;
		markChanged(entry);
		maintain(entry);
		return invalidated;
	}

	private void activateViewers(PortalEntry entry)
	{
		if(entry.runtime.snapshot().allocationMode() != RtpAllocationMode.PER_PLAYER)
		{
			return;
		}
		for(UUID viewerId : entry.viewers)
		{
			entry.runtime.touchPlayer(viewerId);
		}
	}

	private void maintain(PortalEntry entry)
	{
		if(!isCurrent(entry))
		{
			return;
		}
		long nowMillis = dependencies.timeSource().nowMillis();
		RtpRuntimeSnapshot before = entry.runtime.snapshot();
		if(before.allocationMode() == RtpAllocationMode.PER_PLAYER)
		{
			entry.runtime.expireReservations(nowMillis);
			entry.runtime.rotatePlayerReservations(nowMillis);
			assignReservations(entry, nowMillis);
		}
		else
		{
			entry.runtime.advanceTimedRotation(nowMillis, !entry.viewers.isEmpty());
		}
		RtpRuntimeSnapshot after = entry.runtime.snapshot();
		if(!before.equals(after))
		{
			markChanged(entry);
			pruneSharedRetentions(entry, after);
		}
		if(entry.viewers.isEmpty())
		{
			publish(entry);
			return;
		}
		prepareVisibleDestinations(entry);
		refreshViews(entry);
		ensureSearch(entry);
		publish(entry);
	}

	private void assignReservations(PortalEntry entry, long nowMillis)
	{
		for(UUID viewerId : entry.viewers)
		{
			if(entry.runtime.reservationFor(viewerId).isEmpty())
			{
				entry.runtime.reservePlayer(viewerId, nowMillis);
			}
		}
	}

	private void prepareVisibleDestinations(PortalEntry entry)
	{
		RtpRuntimeSnapshot snapshot = entry.runtime.snapshot();
		if(snapshot.allocationMode() == RtpAllocationMode.SHARED)
		{
			if(snapshot.active() != null)
			{
				ensurePrepared(entry, snapshot.active());
			}
			if(snapshot.standby() != null)
			{
				ensurePrepared(entry, snapshot.standby());
			}
			return;
		}
		for(UUID viewerId : entry.viewers)
		{
			entry.runtime.reservationFor(viewerId).ifPresent(destination -> ensurePrepared(entry, destination));
		}
	}

	private void ensurePrepared(PortalEntry entry, RtpDestination destination)
	{
		if(entry.retentions.containsKey(destination)
				|| entry.preparationAttempts.containsKey(destination)
				|| entry.preparationFailures.contains(destination))
		{
			return;
		}
		ExistingPreparation preparation = new ExistingPreparation(entry.generation, destination);
		entry.preparationAttempts.put(destination, preparation);
		SearchRequest request = new SearchRequest(entry.registration.portalId(), entry.generation, entry.registration.settings(), destination);
		dependencies.searchExecutor().execute(() -> loadExisting(entry, preparation, request));
	}

	private void loadExisting(PortalEntry entry, ExistingPreparation preparation, SearchRequest request)
	{
		CompletionStage<LoadedCandidate> stage;
		try
		{
			stage = Objects.requireNonNull(dependencies.candidateLoader().load(request), "candidate loader stage");
		}
		catch(RuntimeException exception)
		{
			dispatch(entry, () -> finishExistingLoad(entry, preparation, null, exception));
			return;
		}
		stage.whenComplete((loaded, failure) -> dispatchClosing(entry, () -> closeLoaded(loaded), () -> finishExistingLoad(entry, preparation, loaded, failure)));
	}

	private void finishExistingLoad(
			PortalEntry entry,
			ExistingPreparation preparation,
			LoadedCandidate loaded,
			Throwable failure)
	{
		if(!isCurrentPreparation(entry, preparation))
		{
			closeLoaded(loaded);
			return;
		}
		if(failure != null || loaded == null)
		{
			entry.preparationAttempts.remove(preparation.destination);
			entry.preparationFailures.add(preparation.destination);
			markChanged(entry);
			maintain(entry);
			return;
		}
		ManagedRetention retention = new ManagedRetention(loaded.retention());
		preparation.retention = retention;
		dependencies.searchExecutor().execute(() -> validateExisting(entry, preparation, loaded.validationRequest(), retention));
	}

	private void validateExisting(
			PortalEntry entry,
			ExistingPreparation preparation,
			RtpValidationRequest request,
			ManagedRetention retention)
	{
		CompletionStage<RtpSafetyResult> stage;
		try
		{
			stage = Objects.requireNonNull(dependencies.safetyValidator().validate(request), "safety validator stage");
		}
		catch(RuntimeException exception)
		{
			dispatchClosing(entry, retention::close, () -> finishExistingValidation(entry, preparation, retention, null, exception));
			return;
		}
		stage.whenComplete((result, failure) -> dispatchClosing(entry, retention::close, () -> finishExistingValidation(entry, preparation, retention, result, failure)));
	}

	private void finishExistingValidation(
			PortalEntry entry,
			ExistingPreparation preparation,
			ManagedRetention retention,
			RtpSafetyResult result,
			Throwable failure)
	{
		if(!isCurrentPreparation(entry, preparation))
		{
			retention.close();
			return;
		}
		entry.preparationAttempts.remove(preparation.destination);
		if(failure != null || result == null || !result.safe() || !preparation.destination.equals(result.destination()))
		{
			retention.close();
			entry.preparationFailures.add(preparation.destination);
		}
		else
		{
			entry.retentions.put(preparation.destination, retention);
			entry.preparationFailures.remove(preparation.destination);
		}
		markChanged(entry);
		maintain(entry);
	}

	private boolean isCurrentPreparation(PortalEntry entry, ExistingPreparation preparation)
	{
		return isCurrent(entry)
				&& preparation.generation == entry.generation
				&& entry.preparationAttempts.get(preparation.destination) == preparation;
	}

	private void refreshViews(PortalEntry entry)
	{
		for(UUID viewerId : entry.viewers)
		{
			refreshView(entry, viewerId);
		}
	}

	private void refreshView(PortalEntry entry, UUID viewerId)
	{
		RtpRuntimeSnapshot runtimeSnapshot = entry.runtime.snapshot();
		RtpDestination destination = destinationForViewer(entry, viewerId, runtimeSnapshot);
		if(destination == null)
		{
			setWarmingUnlessReady(entry, viewerId, null, 0L);
			return;
		}
		long routeRevision = routeRevision(entry, runtimeSnapshot, destination);
		ViewIdentity identity = new ViewIdentity(destination, routeRevision);
		if(entry.preparationFailures.contains(destination))
		{
			setView(entry, viewerId, ViewState.FAILED, identity, routeRevision);
			pruneSharedRetentions(entry, runtimeSnapshot);
			return;
		}
		if(!destinationReady(entry, runtimeSnapshot, destination))
		{
			setWarmingUnlessReady(entry, viewerId, identity, routeRevision);
			return;
		}
		AccessAttempt existingAttempt = entry.accessAttempts.get(viewerId);
		if(existingAttempt != null && existingAttempt.identity.equals(identity))
		{
			setWarmingUnlessReady(entry, viewerId, identity, routeRevision);
			return;
		}
		ViewIdentity publishedIdentity = entry.viewIdentities.get(viewerId);
		RtpProjectionView publishedView = entry.views.get(viewerId);
		if(identity.equals(publishedIdentity)
				&& publishedView != null
				&& publishedView.state() != RtpProjectionView.State.WARMING)
		{
			return;
		}
		startViewAccess(entry, viewerId, identity, routeRevision);
	}

	private RtpDestination destinationForViewer(PortalEntry entry, UUID viewerId, RtpRuntimeSnapshot snapshot)
	{
		if(snapshot.allocationMode() == RtpAllocationMode.SHARED)
		{
			return snapshot.ready() ? snapshot.active() : null;
		}
		return entry.runtime.reservationFor(viewerId).orElse(null);
	}

	private boolean destinationReady(PortalEntry entry, RtpRuntimeSnapshot snapshot, RtpDestination destination)
	{
		if(!entry.retentions.containsKey(destination))
		{
			return false;
		}
		return snapshot.allocationMode() != RtpAllocationMode.SHARED
				|| snapshot.standby() != null && entry.retentions.containsKey(snapshot.standby());
	}

	private long routeRevision(PortalEntry entry, RtpRuntimeSnapshot snapshot, RtpDestination destination)
	{
		if(snapshot.allocationMode() == RtpAllocationMode.SHARED)
		{
			return Math.max(1L, snapshot.routeRevision());
		}
		long attemptRevision = (long) destination.attempt() + 1L;
		return attemptRevision > 0L ? attemptRevision : entry.generation;
	}

	private void startViewAccess(PortalEntry entry, UUID viewerId, ViewIdentity identity, long routeRevision)
	{
		AccessAttempt attempt = new AccessAttempt(identity);
		entry.accessAttempts.put(viewerId, attempt);
		setWarmingUnlessReady(entry, viewerId, identity, routeRevision);
		CompletionStage<RtpAccessResult> stage;
		try
		{
			stage = Objects.requireNonNull(
					dependencies.accessChecker().canUse(entry.registration.portalId(), Optional.of(viewerId), identity.destination()),
					"access checker stage");
		}
		catch(RuntimeException exception)
		{
			finishViewAccess(entry, viewerId, attempt, null, exception);
			return;
		}
		stage.whenComplete((result, failure) -> dispatch(entry, () -> finishViewAccess(entry, viewerId, attempt, result, failure)));
	}

	private void finishViewAccess(
			PortalEntry entry,
			UUID viewerId,
			AccessAttempt attempt,
			RtpAccessResult result,
			Throwable failure)
	{
		if(!isCurrent(entry) || !entry.viewers.contains(viewerId) || entry.accessAttempts.get(viewerId) != attempt)
		{
			return;
		}
		entry.accessAttempts.remove(viewerId);
		RtpRuntimeSnapshot runtimeSnapshot = entry.runtime.snapshot();
		RtpDestination currentDestination = destinationForViewer(entry, viewerId, runtimeSnapshot);
		if(currentDestination == null
				|| !attempt.identity.destination().equals(currentDestination)
				|| !destinationReady(entry, runtimeSnapshot, currentDestination))
		{
			setWarmingUnlessReady(entry, viewerId, null, 0L);
			publish(entry);
			return;
		}
		if(failure != null || result == null || result.status() == RtpAccessResult.Status.FAILURE)
		{
			entry.accessIntegrationFailed = true;
			setView(entry, viewerId, ViewState.FAILED, attempt.identity, attempt.identity.routeRevision());
		}
		else if(!result.allowed())
		{
			entry.accessIntegrationFailed = false;
			setView(entry, viewerId, ViewState.DENIED, attempt.identity, attempt.identity.routeRevision());
		}
		else
		{
			entry.accessIntegrationFailed = false;
			setReadyView(entry, viewerId, attempt.identity);
		}
		pruneSharedRetentions(entry, runtimeSnapshot);
		publish(entry);
	}

	private void setReadyView(PortalEntry entry, UUID viewerId, ViewIdentity identity)
	{
		try
		{
			RtpProjectionView.ReadyData readyData = dependencies.projectionFactory().create(
					entry.registration.portalId(),
					viewerId,
					identity.destination(),
					identity.routeRevision());
			long revision = nextRevision(entry);
			entry.views.put(viewerId, RtpProjectionView.ready(viewerId, revision, readyData));
			entry.viewIdentities.put(viewerId, identity);
		}
		catch(RuntimeException exception)
		{
			setView(entry, viewerId, ViewState.FAILED, identity, identity.routeRevision());
		}
	}

	private void setWarmingUnlessReady(
			PortalEntry entry,
			UUID viewerId,
			ViewIdentity identity,
			long routeRevision)
	{
		RtpProjectionView current = entry.views.get(viewerId);
		if(current != null && current.state() == RtpProjectionView.State.READY)
		{
			return;
		}
		setView(entry, viewerId, ViewState.WARMING, identity, routeRevision);
	}

	private void setView(
			PortalEntry entry,
			UUID viewerId,
			ViewState state,
			ViewIdentity identity,
			long routeRevision)
	{
		RtpProjectionView current = entry.views.get(viewerId);
		ViewIdentity currentIdentity = entry.viewIdentities.get(viewerId);
		RtpProjectionView.State requestedState = projectionState(state);
		if(current != null && current.state() == requestedState && Objects.equals(currentIdentity, identity))
		{
			return;
		}
		long revision = nextRevision(entry);
		RtpProjectionView view = switch(state)
		{
			case WARMING -> RtpProjectionView.warming(viewerId, revision);
			case DENIED -> RtpProjectionView.denied(viewerId, revision);
			case FAILED -> RtpProjectionView.failed(viewerId, revision);
		};
		entry.views.put(viewerId, view);
		if(identity == null)
		{
			entry.viewIdentities.remove(viewerId);
		}
		else
		{
			entry.viewIdentities.put(viewerId, new ViewIdentity(identity.destination(), routeRevision));
		}
	}

	private RtpProjectionView.State projectionState(ViewState state)
	{
		return switch(state)
		{
			case WARMING -> RtpProjectionView.State.WARMING;
			case DENIED -> RtpProjectionView.State.DENIED;
			case FAILED -> RtpProjectionView.State.FAILED;
		};
	}

	private void ensureSearch(PortalEntry entry)
	{
		if(entry.viewers.isEmpty() || entry.searchCampaign != null)
		{
			return;
		}
		long nowMillis = dependencies.timeSource().nowMillis();
		if(nowMillis < entry.nextSearchAllowedAtMillis)
		{
			scheduleSearchWake(entry);
			return;
		}
		Optional<RtpPortalRuntime.SearchTicket> ticket = entry.runtime.beginSearch();
		if(ticket.isEmpty())
		{
			return;
		}
		SearchCampaign campaign = new SearchCampaign(
				entry.generation,
				ticket.get(),
				deadline(nowMillis, SEARCH_DEADLINE_MILLIS));
		entry.searchCampaign = campaign;
		markChanged(entry);
		dependencies.sourceDispatcher().schedule(
				entry.registration.portalId(),
				() -> onSearchDeadline(entry, campaign),
				SEARCH_DEADLINE_MILLIS);
		startSearchAttempt(entry, campaign);
	}

	private void scheduleSearchWake(PortalEntry entry)
	{
		if(entry.searchWakeScheduledAtMillis == entry.nextSearchAllowedAtMillis)
		{
			return;
		}
		entry.searchWakeScheduledAtMillis = entry.nextSearchAllowedAtMillis;
		long delayMillis = Math.max(0L, entry.nextSearchAllowedAtMillis - dependencies.timeSource().nowMillis());
		dependencies.sourceDispatcher().schedule(entry.registration.portalId(), () ->
		{
			if(!isCurrent(entry) || entry.searchWakeScheduledAtMillis != entry.nextSearchAllowedAtMillis)
			{
				return;
			}
			entry.searchWakeScheduledAtMillis = 0L;
			maintain(entry);
		}, delayMillis);
	}

	private void onSearchDeadline(PortalEntry entry, SearchCampaign campaign)
	{
		if(!isCurrentCampaign(entry, campaign))
		{
			return;
		}
		failCampaign(entry, campaign);
		maintain(entry);
	}

	private void startSearchAttempt(PortalEntry entry, SearchCampaign campaign)
	{
		if(!isCurrentCampaign(entry, campaign))
		{
			return;
		}
		long nowMillis = dependencies.timeSource().nowMillis();
		if(campaign.attemptsStarted >= MAXIMUM_SEARCH_ATTEMPTS || nowMillis >= campaign.deadlineMillis)
		{
			failCampaign(entry, campaign);
			maintain(entry);
			return;
		}
		int attempt = nextAttempt(entry);
		campaign.attemptsStarted++;
		dependencies.searchExecutor().execute(() -> sampleAndLoad(entry, campaign, attempt));
	}

	private void sampleAndLoad(PortalEntry entry, SearchCampaign campaign, int attempt)
	{
		RtpDestination destination;
		try
		{
			destination = Objects.requireNonNull(
					dependencies.sampler().sample(entry.registration, entry.generation, attempt),
					"sampled destination");
		}
		catch(RuntimeException exception)
		{
			dispatch(entry, () -> continueCampaign(entry, campaign));
			return;
		}
		SearchRequest request = new SearchRequest(
				entry.registration.portalId(),
				entry.generation,
				entry.registration.settings(),
				destination);
		CompletionStage<LoadedCandidate> stage;
		try
		{
			stage = Objects.requireNonNull(dependencies.candidateLoader().load(request), "candidate loader stage");
		}
		catch(RuntimeException exception)
		{
			dispatch(entry, () -> continueCampaign(entry, campaign));
			return;
		}
		stage.whenComplete((loaded, failure) -> dispatchClosing(entry, () -> closeLoaded(loaded), () -> finishSearchLoad(entry, campaign, loaded, failure)));
	}

	private void finishSearchLoad(
			PortalEntry entry,
			SearchCampaign campaign,
			LoadedCandidate loaded,
			Throwable failure)
	{
		if(!isCurrentCampaign(entry, campaign))
		{
			closeLoaded(loaded);
			return;
		}
		if(dependencies.timeSource().nowMillis() >= campaign.deadlineMillis)
		{
			closeLoaded(loaded);
			failCampaign(entry, campaign);
			maintain(entry);
			return;
		}
		if(failure != null || loaded == null)
		{
			continueCampaign(entry, campaign);
			return;
		}
		ManagedRetention retention = new ManagedRetention(loaded.retention());
		campaign.activeRetention = retention;
		dependencies.searchExecutor().execute(() -> validateSearch(entry, campaign, loaded.validationRequest(), retention));
	}

	private void validateSearch(
			PortalEntry entry,
			SearchCampaign campaign,
			RtpValidationRequest request,
			ManagedRetention retention)
	{
		CompletionStage<RtpSafetyResult> stage;
		try
		{
			stage = Objects.requireNonNull(dependencies.safetyValidator().validate(request), "safety validator stage");
		}
		catch(RuntimeException exception)
		{
			dispatchClosing(entry, retention::close, () -> finishSearchValidation(entry, campaign, retention, null, exception));
			return;
		}
		stage.whenComplete((result, failure) -> dispatchClosing(entry, retention::close, () -> finishSearchValidation(entry, campaign, retention, result, failure)));
	}

	private void finishSearchValidation(
			PortalEntry entry,
			SearchCampaign campaign,
			ManagedRetention retention,
			RtpSafetyResult result,
			Throwable failure)
	{
		if(campaign.activeRetention == retention)
		{
			campaign.activeRetention = null;
		}
		if(!isCurrentCampaign(entry, campaign))
		{
			retention.close();
			return;
		}
		if(dependencies.timeSource().nowMillis() >= campaign.deadlineMillis)
		{
			retention.close();
			failCampaign(entry, campaign);
			maintain(entry);
			return;
		}
		if(failure != null || result == null || !result.safe())
		{
			retention.close();
			continueCampaign(entry, campaign);
			return;
		}
		RtpPortalRuntime.SearchCompletion completion = entry.runtime.completeSearch(
				campaign.ticket,
				result.destination(),
				dependencies.timeSource().nowMillis());
		if(completion == RtpPortalRuntime.SearchCompletion.ADDED)
		{
			entry.retentions.put(result.destination(), retention);
			entry.searchCampaign = null;
			entry.nextSearchAllowedAtMillis = 0L;
			pruneSharedRetentions(entry, entry.runtime.snapshot());
			markChanged(entry);
			maintain(entry);
			return;
		}
		retention.close();
		if(completion == RtpPortalRuntime.SearchCompletion.STALE)
		{
			entry.searchCampaign = null;
			markChanged(entry);
			maintain(entry);
			return;
		}
		Optional<RtpPortalRuntime.SearchTicket> renewed = entry.runtime.beginSearch();
		if(renewed.isEmpty())
		{
			entry.searchCampaign = null;
			markChanged(entry);
			maintain(entry);
			return;
		}
		campaign.ticket = renewed.get();
		continueCampaign(entry, campaign);
	}

	private void continueCampaign(PortalEntry entry, SearchCampaign campaign)
	{
		if(!isCurrentCampaign(entry, campaign))
		{
			return;
		}
		startSearchAttempt(entry, campaign);
	}

	private void failCampaign(PortalEntry entry, SearchCampaign campaign)
	{
		if(!isCurrentCampaign(entry, campaign))
		{
			return;
		}
		if(campaign.activeRetention != null)
		{
			campaign.activeRetention.close();
			campaign.activeRetention = null;
		}
		entry.runtime.failSearch(campaign.ticket, dependencies.timeSource().nowMillis());
		entry.searchCampaign = null;
		entry.nextSearchAllowedAtMillis = deadline(dependencies.timeSource().nowMillis(), SEARCH_RETRY_MILLIS);
		pruneSharedRetentions(entry, entry.runtime.snapshot());
		markChanged(entry);
	}

	private boolean isCurrentCampaign(PortalEntry entry, SearchCampaign campaign)
	{
		return isCurrent(entry)
				&& campaign.generation == entry.generation
				&& entry.searchCampaign == campaign;
	}

	private void beginTraversal(
			UUID portalId,
			TraversalActor actor,
			CompletableFuture<Optional<TraversalPreparation>> admission)
	{
		PortalEntry entry = entries.get(portalId);
		if(entry == null)
		{
			admission.complete(Optional.empty());
			return;
		}
		Optional<RtpPortalRuntime.TraversalClaim> claim = claim(entry, actor);
		if(claim.isEmpty())
		{
			admission.complete(Optional.empty());
			return;
		}
		RtpPortalRuntime.TraversalClaim admittedClaim = claim.get();
		if(!entry.retentions.containsKey(admittedClaim.destination()))
		{
			entry.runtime.completeTraversal(admittedClaim, false, dependencies.timeSource().nowMillis());
			markChanged(entry);
			maintain(entry);
			admission.complete(Optional.empty());
			return;
		}
		AtomicReference<TraversalPreparation> preparationReference = new AtomicReference<TraversalPreparation>();
		RtpTraversalRequest request = RtpTraversalRequest.preparing(entry.generation, () ->
				dependencies.sourceDispatcher().execute(portalId, () -> finishTraversal(preparationReference.get())));
		TraversalPreparation preparation = new TraversalPreparation(
				portalId,
				entry.generation,
				actor,
				admittedClaim,
				request,
				admission,
				entry);
		preparationReference.set(preparation);
		entry.traversals.add(preparation);
		markChanged(entry);
		publish(entry);
		dependencies.sourceDispatcher().schedule(portalId, () ->
		{
			if(request.timeoutPreparing(entry.generation))
			{
				admission.complete(Optional.empty());
			}
		}, TRAVERSAL_PREPARATION_DEADLINE_MILLIS);
		startTraversalAccess(preparation);
	}

	private Optional<RtpPortalRuntime.TraversalClaim> claim(PortalEntry entry, TraversalActor actor)
	{
		RtpRuntimeSnapshot snapshot = entry.runtime.snapshot();
		if(snapshot.allocationMode() == RtpAllocationMode.SHARED)
		{
			return entry.runtime.claimShared(actor.claimId());
		}
		return actor.playerId().isPresent()
				? entry.runtime.claimPlayer(actor.claimId(), actor.playerId().get())
				: entry.runtime.claimAnonymous(actor.claimId());
	}

	private void startTraversalAccess(TraversalPreparation preparation)
	{
		Optional<UUID> playerId = preparation.actor().playerId();
		if(playerId.isEmpty())
		{
			finishTraversalAccess(preparation, RtpAccessResult.allowedResult(), null);
			return;
		}
		CompletionStage<RtpAccessResult> stage;
		try
		{
			stage = Objects.requireNonNull(
					dependencies.accessChecker().canUse(
							preparation.portalId(),
							playerId,
							preparation.claim().destination()),
					"access checker stage");
		}
		catch(RuntimeException exception)
		{
			finishTraversalAccess(preparation, null, exception);
			return;
		}
		stage.whenComplete((result, failure) -> dependencies.sourceDispatcher().execute(
				preparation.portalId(),
				() -> finishTraversalAccess(preparation, result, failure)));
	}

	private void finishTraversalAccess(
			TraversalPreparation preparation,
			RtpAccessResult result,
			Throwable failure)
	{
		PortalEntry entry = preparation.entry;
		if(preparation.request().state() != RtpTraversalRequest.State.PREPARING)
		{
			preparation.admission.complete(Optional.empty());
			return;
		}
		if(!isCurrent(entry)
				|| entry.generation != preparation.generation()
				|| failure != null
				|| result == null
				|| !result.allowed())
		{
			preparation.request().cancel(preparation.generation());
			preparation.admission.complete(Optional.empty());
			return;
		}
		preparation.admission.complete(Optional.of(preparation));
	}

	private void finishTraversal(TraversalPreparation preparation)
	{
		if(preparation == null || !preparation.terminalized.compareAndSet(false, true))
		{
			return;
		}
		PortalEntry entry = preparation.entry;
		boolean succeeded = preparation.request().state() == RtpTraversalRequest.State.SUCCEEDED;
		entry.runtime.completeTraversal(preparation.claim(), succeeded, dependencies.timeSource().nowMillis());
		entry.traversals.remove(preparation);
		if(succeeded && preparation.claim().kind() != RtpPortalRuntime.ClaimKind.SHARED)
		{
			closeRetention(entry, preparation.claim().destination());
		}
		pruneSharedRetentions(entry, entry.runtime.snapshot());
		if(!preparation.admission.isDone())
		{
			preparation.admission.complete(Optional.empty());
		}
		if(isCurrent(entry))
		{
			markChanged(entry);
			maintain(entry);
		}
		else
		{
			closeAllRetentions(entry);
		}
	}

	private void idleEntry(PortalEntry entry)
	{
		cancelSearch(entry);
		closeAllRetentions(entry);
		entry.preparationFailures.clear();
		entry.accessAttempts.clear();
		entry.viewIdentities.clear();
		entry.views.clear();
	}

	private void scheduleIdle(PortalEntry entry)
	{
		long token = nextGeneration(entry.idleToken);
		entry.idleToken = token;
		dependencies.sourceDispatcher().schedule(
				entry.registration.portalId(),
				() ->
				{
					if(!isCurrent(entry) || !entry.viewers.isEmpty() || entry.idleToken != token)
					{
						return;
					}
					idleEntry(entry);
					markChanged(entry);
					publish(entry);
				},
				entry.registration.settings().getLeaseIdleMillis());
	}

	private void cancelSearch(PortalEntry entry)
	{
		SearchCampaign campaign = entry.searchCampaign;
		if(campaign == null)
		{
			return;
		}
		if(campaign.activeRetention != null)
		{
			campaign.activeRetention.close();
			campaign.activeRetention = null;
		}
		entry.runtime.failSearch(campaign.ticket, dependencies.timeSource().nowMillis());
		entry.searchCampaign = null;
		entry.nextSearchAllowedAtMillis = 0L;
	}

	private void closeEntry(PortalEntry entry)
	{
		entry.closed = true;
		cancelSearch(entry);
		List<TraversalPreparation> traversals = List.copyOf(entry.traversals);
		for(TraversalPreparation preparation : traversals)
		{
			preparation.request().cancel(preparation.generation());
			preparation.admission.complete(Optional.empty());
		}
		entry.accessAttempts.clear();
		closeAllRetentions(entry);
		entry.views.clear();
		entry.viewers.clear();
	}

	private void pruneSharedRetentions(PortalEntry entry, RtpRuntimeSnapshot snapshot)
	{
		if(snapshot.allocationMode() != RtpAllocationMode.SHARED || snapshot.rerolling())
		{
			return;
		}
		Set<RtpDestination> retained = new LinkedHashSet<RtpDestination>();
		if(snapshot.active() != null)
		{
			retained.add(snapshot.active());
		}
		if(snapshot.standby() != null)
		{
			retained.add(snapshot.standby());
		}
		for(ViewIdentity identity : entry.viewIdentities.values())
		{
			retained.add(identity.destination());
		}
		for(AccessAttempt attempt : entry.accessAttempts.values())
		{
			retained.add(attempt.identity().destination());
		}
		for(TraversalPreparation traversal : entry.traversals)
		{
			retained.add(traversal.claim().destination());
		}
		List<RtpDestination> obsolete = new ArrayList<RtpDestination>();
		for(RtpDestination destination : entry.retentions.keySet())
		{
			if(!retained.contains(destination))
			{
				obsolete.add(destination);
			}
		}
		for(RtpDestination destination : obsolete)
		{
			closeRetention(entry, destination);
		}
	}

	private void closeAllRetentions(PortalEntry entry)
	{
		for(ManagedRetention retention : entry.retentions.values())
		{
			retention.close();
		}
		entry.retentions.clear();
		for(ExistingPreparation preparation : entry.preparationAttempts.values())
		{
			if(preparation.retention != null)
			{
				preparation.retention.close();
			}
		}
		entry.preparationAttempts.clear();
	}

	private void closeRetention(PortalEntry entry, RtpDestination destination)
	{
		ManagedRetention retention = entry.retentions.remove(destination);
		if(retention != null)
		{
			retention.close();
		}
	}

	private void closeLoaded(LoadedCandidate loaded)
	{
		if(loaded != null)
		{
			new ManagedRetention(loaded.retention()).close();
		}
	}

	private RtpPortalRuntime createRuntime(long generation, RtpSettings settings)
	{
		return settings.getAllocationMode() == RtpAllocationMode.SHARED
				? RtpPortalRuntime.shared(generation, settings.getRotationMode(), settings.getCycleDurationMillis())
				: RtpPortalRuntime.perPlayer(
						generation,
						settings.getPrivateReleaseMillis(),
						settings.getCycleDurationMillis());
	}

	private void dispatch(PortalEntry entry, Runnable command)
	{
		dependencies.sourceDispatcher().execute(entry.registration.portalId(), command);
	}

	private void dispatchClosing(PortalEntry entry, Runnable cleanupOnReject, Runnable command)
	{
		try
		{
			dispatch(entry, command);
		}
		catch(RuntimeException | Error failure)
		{
			cleanupOnReject.run();
			throw failure;
		}
	}

	private boolean isCurrent(PortalEntry entry)
	{
		return !entry.closed && entries.get(entry.registration.portalId()) == entry;
	}

	private void publish(PortalEntry entry)
	{
		if(!isCurrent(entry))
		{
			return;
		}
		Snapshot snapshot = new Snapshot(
				entry.registration.portalId(),
				entry.generation,
				entry.revision,
				entry.nextSearchAllowedAtMillis,
				!entry.accessIntegrationFailed,
				entry.registration.settings(),
				entry.runtime.snapshot(),
				Set.copyOf(entry.viewers),
				Map.copyOf(entry.views));
		published.put(entry.registration.portalId(), snapshot);
	}

	private void markChanged(PortalEntry entry)
	{
		nextRevision(entry);
	}

	private long nextRevision(PortalEntry entry)
	{
		entry.revision = entry.revision == Long.MAX_VALUE ? 1L : entry.revision + 1L;
		return entry.revision;
	}

	private int nextAttempt(PortalEntry entry)
	{
		int attempt = entry.nextAttemptOrdinal;
		entry.nextAttemptOrdinal = entry.nextAttemptOrdinal == Integer.MAX_VALUE ? 0 : entry.nextAttemptOrdinal + 1;
		return attempt;
	}

	private long nextGeneration(long current)
	{
		return current == Long.MAX_VALUE ? 1L : current + 1L;
	}

	private long deadline(long nowMillis, long durationMillis)
	{
		return durationMillis > Long.MAX_VALUE - nowMillis ? Long.MAX_VALUE : nowMillis + durationMillis;
	}

	private <T> CompletableFuture<T> onSource(UUID portalId, Supplier<T> operation)
	{
		CompletableFuture<T> result = new CompletableFuture<T>();
		try
		{
			dependencies.sourceDispatcher().execute(portalId, () ->
			{
				try
				{
					result.complete(operation.get());
				}
				catch(RuntimeException exception)
				{
					result.completeExceptionally(exception);
				}
			});
		}
		catch(RuntimeException exception)
		{
			result.completeExceptionally(exception);
		}
		return result;
	}

	public record Dependencies(
			SourceDispatcher sourceDispatcher,
			SearchExecutor searchExecutor,
			TimeSource timeSource,
			Sampler sampler,
			CandidateLoader candidateLoader,
			SafetyValidator safetyValidator,
			AccessChecker accessChecker,
			ProjectionFactory projectionFactory,
			RtpRimRenderer rimRenderer)
	{
		public Dependencies
		{
			Objects.requireNonNull(sourceDispatcher, "sourceDispatcher");
			Objects.requireNonNull(searchExecutor, "searchExecutor");
			Objects.requireNonNull(timeSource, "timeSource");
			Objects.requireNonNull(sampler, "sampler");
			Objects.requireNonNull(candidateLoader, "candidateLoader");
			Objects.requireNonNull(safetyValidator, "safetyValidator");
			Objects.requireNonNull(accessChecker, "accessChecker");
			Objects.requireNonNull(projectionFactory, "projectionFactory");
			Objects.requireNonNull(rimRenderer, "rimRenderer");
		}
	}

	public record Registration(UUID portalId, RtpSettings settings, double centerX, double centerZ, long seed)
	{
		public Registration
		{
			Objects.requireNonNull(portalId, "portalId");
			Objects.requireNonNull(settings, "settings");
			if(!Double.isFinite(centerX) || !Double.isFinite(centerZ))
			{
				throw new IllegalArgumentException("center coordinates must be finite");
			}
		}
	}

	public record SearchRequest(UUID portalId, long generation, RtpSettings settings, RtpDestination destination)
	{
		public SearchRequest
		{
			Objects.requireNonNull(portalId, "portalId");
			Objects.requireNonNull(settings, "settings");
			Objects.requireNonNull(destination, "destination");
		}
	}

	public record LoadedCandidate(RtpValidationRequest validationRequest, Retention retention)
	{
		public LoadedCandidate
		{
			Objects.requireNonNull(validationRequest, "validationRequest");
			Objects.requireNonNull(retention, "retention");
		}
	}

	public record Snapshot(
			UUID portalId,
			long generation,
			long revision,
			long nextSearchAllowedAtMillis,
			boolean integrationAvailable,
			RtpSettings settings,
			RtpRuntimeSnapshot runtime,
			Set<UUID> viewers,
			Map<UUID, RtpProjectionView> views)
	{
		public Snapshot
		{
			Objects.requireNonNull(portalId, "portalId");
			if(nextSearchAllowedAtMillis < 0L)
			{
				throw new IllegalArgumentException("nextSearchAllowedAtMillis must be non-negative");
			}
			Objects.requireNonNull(settings, "settings");
			Objects.requireNonNull(runtime, "runtime");
			viewers = Set.copyOf(Objects.requireNonNull(viewers, "viewers"));
			views = Map.copyOf(Objects.requireNonNull(views, "views"));
		}
	}

	public record TraversalActor(UUID claimId, UUID playerIdValue)
	{
		public TraversalActor
		{
			Objects.requireNonNull(claimId, "claimId");
		}

		public static TraversalActor player(UUID claimId, UUID playerId)
		{
			return new TraversalActor(claimId, Objects.requireNonNull(playerId, "playerId"));
		}

		public static TraversalActor anonymous(UUID claimId)
		{
			return new TraversalActor(claimId, null);
		}

		public Optional<UUID> playerId()
		{
			return Optional.ofNullable(playerIdValue);
		}
	}

	public static final class TraversalPreparation
	{
		private final UUID portalId;
		private final long generation;
		private final TraversalActor actor;
		private final RtpPortalRuntime.TraversalClaim claim;
		private final RtpTraversalRequest request;
		private final CompletableFuture<Optional<TraversalPreparation>> admission;
		private final PortalEntry entry;
		private final AtomicBoolean terminalized;

		private TraversalPreparation(
				UUID portalId,
				long generation,
				TraversalActor actor,
				RtpPortalRuntime.TraversalClaim claim,
				RtpTraversalRequest request,
				CompletableFuture<Optional<TraversalPreparation>> admission,
				PortalEntry entry)
		{
			this.portalId = portalId;
			this.generation = generation;
			this.actor = actor;
			this.claim = claim;
			this.request = request;
			this.admission = admission;
			this.entry = entry;
			this.terminalized = new AtomicBoolean(false);
		}

		public UUID portalId()
		{
			return portalId;
		}

		public long generation()
		{
			return generation;
		}

		public TraversalActor actor()
		{
			return actor;
		}

		public RtpPortalRuntime.TraversalClaim claim()
		{
			return claim;
		}

		public RtpTraversalRequest request()
		{
			return request;
		}
	}

	@FunctionalInterface
	public interface SourceDispatcher
	{
		void execute(UUID portalId, Runnable command);

		default void schedule(UUID portalId, Runnable command, long delayMillis)
		{
			throw new UnsupportedOperationException("delayed source dispatch is required");
		}
	}

	@FunctionalInterface
	public interface SearchExecutor
	{
		void execute(Runnable command);
	}

	@FunctionalInterface
	public interface TimeSource
	{
		long nowMillis();
	}

	@FunctionalInterface
	public interface Sampler
	{
		RtpDestination sample(Registration registration, long generation, int attempt);
	}

	@FunctionalInterface
	public interface CandidateLoader
	{
		CompletionStage<LoadedCandidate> load(SearchRequest request);
	}

	@FunctionalInterface
	public interface SafetyValidator
	{
		CompletionStage<RtpSafetyResult> validate(RtpValidationRequest request);
	}

	@FunctionalInterface
	public interface AccessChecker
	{
		CompletionStage<RtpAccessResult> canUse(UUID portalId, Optional<UUID> viewerId, RtpDestination destination);
	}

	@FunctionalInterface
	public interface ProjectionFactory
	{
		RtpProjectionView.ReadyData create(UUID portalId, UUID viewerId, RtpDestination destination, long routeRevision);
	}

	@FunctionalInterface
	public interface Retention
	{
		void close();
	}

	private enum ViewState
	{
		WARMING,
		DENIED,
		FAILED
	}

	private static final class PortalEntry
	{
		private final Registration registration;
		private final long generation;
		private final RtpPortalRuntime runtime;
		private final Set<UUID> viewers;
		private final Map<UUID, RtpProjectionView> views;
		private final Map<UUID, ViewIdentity> viewIdentities;
		private final Map<UUID, AccessAttempt> accessAttempts;
		private final Map<RtpDestination, ManagedRetention> retentions;
		private final Map<RtpDestination, ExistingPreparation> preparationAttempts;
		private final Set<RtpDestination> preparationFailures;
		private final Set<TraversalPreparation> traversals;
		private SearchCampaign searchCampaign;
		private long nextSearchAllowedAtMillis;
		private long searchWakeScheduledAtMillis;
		private long revision;
		private int nextAttemptOrdinal;
		private boolean accessIntegrationFailed;
		private boolean closed;
		private long idleToken;

		private PortalEntry(Registration registration, long generation, RtpPortalRuntime runtime)
		{
			this.registration = registration;
			this.generation = generation;
			this.runtime = runtime;
			this.viewers = new LinkedHashSet<UUID>();
			this.views = new LinkedHashMap<UUID, RtpProjectionView>();
			this.viewIdentities = new LinkedHashMap<UUID, ViewIdentity>();
			this.accessAttempts = new LinkedHashMap<UUID, AccessAttempt>();
			this.retentions = new LinkedHashMap<RtpDestination, ManagedRetention>();
			this.preparationAttempts = new LinkedHashMap<RtpDestination, ExistingPreparation>();
			this.preparationFailures = new LinkedHashSet<RtpDestination>();
			this.traversals = new LinkedHashSet<TraversalPreparation>();
			this.revision = 1L;
		}
	}

	private static final class SearchCampaign
	{
		private final long generation;
		private final long deadlineMillis;
		private RtpPortalRuntime.SearchTicket ticket;
		private ManagedRetention activeRetention;
		private int attemptsStarted;

		private SearchCampaign(long generation, RtpPortalRuntime.SearchTicket ticket, long deadlineMillis)
		{
			this.generation = generation;
			this.ticket = ticket;
			this.deadlineMillis = deadlineMillis;
		}
	}

	private static final class ExistingPreparation
	{
		private final long generation;
		private final RtpDestination destination;
		private ManagedRetention retention;

		private ExistingPreparation(long generation, RtpDestination destination)
		{
			this.generation = generation;
			this.destination = destination;
		}
	}

	private static final class ManagedRetention implements Retention
	{
		private final Retention delegate;
		private final AtomicBoolean closed;

		private ManagedRetention(Retention delegate)
		{
			this.delegate = Objects.requireNonNull(delegate, "delegate");
			this.closed = new AtomicBoolean(false);
		}

		@Override
		public void close()
		{
			if(closed.compareAndSet(false, true))
			{
				delegate.close();
			}
		}
	}

	private record ViewIdentity(RtpDestination destination, long routeRevision)
	{
		private ViewIdentity
		{
			Objects.requireNonNull(destination, "destination");
		}
	}

	private record AccessAttempt(ViewIdentity identity)
	{
		private AccessAttempt
		{
			Objects.requireNonNull(identity, "identity");
		}
	}
}
