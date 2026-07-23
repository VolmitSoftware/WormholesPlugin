package art.arcane.wormholes.portal.rtp;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.Vector;

import art.arcane.wormholes.ProjectionManager;
import art.arcane.wormholes.portal.ILocalPortal;
import art.arcane.wormholes.portal.LocalPortal;
import art.arcane.wormholes.portal.PortalFrame;
import art.arcane.wormholes.portal.PortalStructure;
import art.arcane.wormholes.portal.PortalType;
import art.arcane.wormholes.portal.Traversive;
import art.arcane.wormholes.util.AxisAlignedBB;
import art.arcane.wormholes.util.Direction;

public final class BukkitRtpRuntime implements ProjectionManager.RtpProjectionProvider, AutoCloseable
{
	private static final double SOURCE_CAPTURE_MARGIN = 2.0D;
	private static final double ARRIVAL_TOLERANCE = 0.001D;
	private static final double MINIMUM_ENVELOPE_EXTENT = 0.05D;

	private final RtpService service;
	private final Environment environment;
	private final long attendanceIdleMillis;
	private final Map<UUID, PortalRegistration> registrations;
	private final Map<AttendanceKey, Attendance> attendance;
	private final Map<UUID, ActiveTraversal> activeTraversals;
	private final Set<String> reportedFailures;
	private final AtomicBoolean closed;

	public BukkitRtpRuntime(RtpService service, Environment environment, long attendanceIdleMillis)
	{
		this.service = Objects.requireNonNull(service, "service");
		this.environment = Objects.requireNonNull(environment, "environment");
		if(attendanceIdleMillis <= 0L)
		{
			throw new IllegalArgumentException("attendanceIdleMillis must be positive");
		}
		this.attendanceIdleMillis = attendanceIdleMillis;
		registrations = new ConcurrentHashMap<UUID, PortalRegistration>();
		attendance = new ConcurrentHashMap<AttendanceKey, Attendance>();
		activeTraversals = new ConcurrentHashMap<UUID, ActiveTraversal>();
		reportedFailures = ConcurrentHashMap.newKeySet();
		closed = new AtomicBoolean(false);
	}

	public void synchronize(LocalPortal portal)
	{
		LocalPortal requiredPortal = Objects.requireNonNull(portal, "portal");
		if(closed.get() || requiredPortal.getType() != PortalType.RTP || requiredPortal.getRtpSettings() == null)
		{
			unregister(requiredPortal.getId());
			return;
		}
		PortalRegistration replacement = registration(requiredPortal);
		environment.sourceRegistered(requiredPortal.getId(), requiredPortal.getCenter());
		PortalRegistration previous = registrations.put(requiredPortal.getId(), replacement);
		if(previous != null && replacement.hasSameRouteAs(previous))
		{
			return;
		}
		if(previous != null)
		{
			cancelPortalTraversals(requiredPortal.getId());
		}
		service.register(replacement.registration()).whenComplete((snapshot, failure) ->
		{
			if(failure != null)
			{
				reportFailure("register:" + requiredPortal.getId(), failure);
			}
		});
	}

	public void unregister(UUID portalId)
	{
		UUID requiredPortalId = Objects.requireNonNull(portalId, "portalId");
		PortalRegistration removed = registrations.remove(requiredPortalId);
		removePortalAttendance(requiredPortalId);
		cancelPortalTraversals(requiredPortalId);
		if(removed == null && service.snapshot(requiredPortalId).isEmpty())
		{
			environment.sourceUnregistered(requiredPortalId);
			return;
		}
		service.unregister(requiredPortalId).whenComplete((changed, failure) ->
		{
			environment.sourceUnregistered(requiredPortalId);
			if(failure != null)
			{
				reportFailure("unregister:" + requiredPortalId, failure);
			}
		});
	}

	public void tick(UUID portalId)
	{
		if(closed.get() || !registrations.containsKey(portalId))
		{
			return;
		}
		service.tick(portalId).whenComplete((changed, failure) ->
		{
			if(failure != null)
			{
				reportFailure("tick:" + portalId, failure);
			}
		});
	}

	public boolean isReady(UUID portalId)
	{
		if(closed.get())
		{
			return false;
		}
		Optional<RtpService.Snapshot> snapshot = service.snapshot(Objects.requireNonNull(portalId, "portalId"));
		return snapshot.isPresent()
				&& !snapshot.get().viewers().isEmpty()
				&& (snapshot.get().runtime().ready()
						|| snapshot.get().views().values().stream()
								.anyMatch(view -> view.state() == RtpProjectionView.State.READY));
	}

	public Optional<RtpPortalEditorModel.StatusSnapshot> editorStatus(UUID portalId)
	{
		if(closed.get())
		{
			return Optional.empty();
		}
		Optional<RtpService.Snapshot> snapshot = service.snapshot(Objects.requireNonNull(portalId, "portalId"));
		if(snapshot.isEmpty())
		{
			return Optional.empty();
		}
		RtpService.Snapshot current = snapshot.get();
		RtpPortalEditorModel.StatusContext context = new RtpPortalEditorModel.StatusContext(
				environment.resolveWorld(current.settings().getTargetWorldKey()) != null,
				current.integrationAvailable(),
				environment.nowMillis(),
				current.nextSearchAllowedAtMillis());
		return Optional.of(RtpPortalEditorModel.StatusSnapshot.from(current.runtime(), context));
	}

	public CompletableFuture<Boolean> requestManualReroll(UUID portalId)
	{
		if(closed.get())
		{
			return CompletableFuture.completedFuture(Boolean.FALSE);
		}
		return service.manualReroll(Objects.requireNonNull(portalId, "portalId"));
	}

	public CompletableFuture<Set<RtpDestination>> requestPoolRebuild(UUID portalId)
	{
		if(closed.get())
		{
			return CompletableFuture.completedFuture(Set.of());
		}
		return service.rebuildPool(Objects.requireNonNull(portalId, "portalId"));
	}

	@Override
	public boolean supports(ILocalPortal portal)
	{
		return !closed.get() && portal instanceof LocalPortal localPortal && localPortal.getType() == PortalType.RTP;
	}

	@Override
	public ProjectionManager.RtpProjectionResult touch(ILocalPortal portal, Player observer)
	{
		Objects.requireNonNull(portal, "portal");
		Player requiredObserver = Objects.requireNonNull(observer, "observer");
		if(!(portal instanceof LocalPortal localPortal) || !supports(portal))
		{
			return projectionResult(portal, requiredObserver.getUniqueId(), false);
		}
		synchronize(localPortal);
		AttendanceKey key = new AttendanceKey(localPortal.getId(), requiredObserver.getUniqueId());
		attendance.put(key, attendance(localPortal, environment.nowMillis()));
		service.touchViewer(localPortal.getId(), requiredObserver.getUniqueId()).whenComplete((changed, failure) ->
		{
			if(failure != null)
			{
				reportFailure("attendance-touch:" + localPortal.getId(), failure);
			}
		});
		return projectionResult(localPortal, requiredObserver.getUniqueId(), true);
	}

	@Override
	public World resolveTargetWorld(String worldKey)
	{
		return environment.resolveWorld(Objects.requireNonNull(worldKey, "worldKey"));
	}

	@Override
	public void dispatchRim(ILocalPortal portal, Player observer, RtpRimRenderer.Sample sample)
	{
		if(portal instanceof LocalPortal localPortal && !closed.get())
		{
			environment.dispatchRim(localPortal, observer, sample);
		}
	}

	public void leaveViewer(UUID viewerId)
	{
		UUID requiredViewerId = Objects.requireNonNull(viewerId, "viewerId");
		cancelEntityTraversal(requiredViewerId);
		List<AttendanceKey> departures = new ArrayList<AttendanceKey>();
		for(AttendanceKey key : attendance.keySet())
		{
			if(key.viewerId().equals(requiredViewerId))
			{
				departures.add(key);
			}
		}
		for(AttendanceKey key : departures)
		{
			leave(key);
		}
	}

	public void viewerMoved(Player viewer, Location destination)
	{
		Player requiredViewer = Objects.requireNonNull(viewer, "viewer");
		Location requiredDestination = Objects.requireNonNull(destination, "destination");
		UUID viewerId = requiredViewer.getUniqueId();
		List<AttendanceKey> departures = new ArrayList<AttendanceKey>();
		for(Map.Entry<AttendanceKey, Attendance> entry : attendance.entrySet())
		{
			if(entry.getKey().viewerId().equals(viewerId) && !entry.getValue().contains(requiredDestination))
			{
				departures.add(entry.getKey());
			}
		}
		for(AttendanceKey key : departures)
		{
			leave(key);
		}
	}

	public void sweepAttendance()
	{
		long nowMillis = environment.nowMillis();
		List<AttendanceKey> departures = new ArrayList<AttendanceKey>();
		for(Map.Entry<AttendanceKey, Attendance> entry : attendance.entrySet())
		{
			if(nowMillis - entry.getValue().touchedAtMillis() >= attendanceIdleMillis)
			{
				departures.add(entry.getKey());
			}
		}
		for(AttendanceKey key : departures)
		{
			leave(key);
		}
	}

	public void worldUnloaded(UUID worldId)
	{
		UUID requiredWorldId = Objects.requireNonNull(worldId, "worldId");
		environment.worldUnloaded(requiredWorldId);
		List<UUID> affected = new ArrayList<UUID>();
		for(Map.Entry<UUID, PortalRegistration> entry : registrations.entrySet())
		{
			PortalRegistration registration = entry.getValue();
			if(requiredWorldId.equals(registration.sourceWorldId()) || requiredWorldId.equals(registration.targetWorldId()))
			{
				affected.add(entry.getKey());
			}
		}
		for(UUID portalId : affected)
		{
			unregister(portalId);
		}
	}

	public boolean traverse(LocalPortal portal, Entity entity, Traversive traversive)
	{
		LocalPortal requiredPortal = Objects.requireNonNull(portal, "portal");
		Entity requiredEntity = Objects.requireNonNull(entity, "entity");
		Traversive requiredTraversive = Objects.requireNonNull(traversive, "traversive");
		if(closed.get() || !supports(requiredPortal) || !isReady(requiredPortal.getId())
				|| !sourceEligible(requiredPortal, requiredEntity)
				|| !requiredPortal.beginRtpTraversal(requiredEntity, environment.nowMillis()))
		{
			return false;
		}
		ActiveTraversal activeTraversal = new ActiveTraversal(requiredPortal, requiredEntity);
		if(activeTraversals.putIfAbsent(requiredEntity.getUniqueId(), activeTraversal) != null)
		{
			requiredPortal.cancelRtpTraversal(requiredEntity);
			return false;
		}
		RtpService.TraversalActor actor = requiredEntity instanceof Player
				? RtpService.TraversalActor.player(UUID.randomUUID(), requiredEntity.getUniqueId())
				: RtpService.TraversalActor.anonymous(UUID.randomUUID());
		service.claimTraversal(requiredPortal.getId(), actor).whenComplete((preparation, failure) ->
		{
			if(failure != null || preparation == null || preparation.isEmpty())
			{
				if(failure != null)
				{
					reportFailure("claim:" + requiredPortal.getId(), failure);
				}
				activeTraversals.remove(requiredEntity.getUniqueId(), activeTraversal);
				requiredPortal.cancelRtpTraversal(requiredEntity);
				return;
			}
			RtpService.TraversalPreparation admitted = preparation.get();
			boolean scheduled = environment.scheduleEntity(requiredEntity,
					() -> guardStage(requiredPortal, requiredEntity, admitted, null,
							() -> prepareTraversal(requiredPortal, requiredEntity, requiredTraversive, admitted)),
					() -> failTraversal(requiredPortal, requiredEntity, admitted, null));
			if(!scheduled)
			{
				failTraversal(requiredPortal, requiredEntity, admitted,
						new IllegalStateException("Entity scheduler rejected RTP traversal preparation"));
			}
		});
		return true;
	}

	public void abortTraversal(UUID entityId)
	{
		cancelEntityTraversal(Objects.requireNonNull(entityId, "entityId"));
	}

	private void cancelPortalTraversals(UUID portalId)
	{
		UUID requiredPortalId = Objects.requireNonNull(portalId, "portalId");
		for(Map.Entry<UUID, ActiveTraversal> entry : List.copyOf(activeTraversals.entrySet()))
		{
			ActiveTraversal traversal = entry.getValue();
			if(traversal.portal().getId().equals(requiredPortalId) && activeTraversals.remove(entry.getKey(), traversal))
			{
				traversal.portal().cancelRtpTraversal(traversal.entity());
			}
		}
	}

	private void cancelEntityTraversal(UUID entityId)
	{
		ActiveTraversal traversal = activeTraversals.remove(Objects.requireNonNull(entityId, "entityId"));
		if(traversal != null)
		{
			traversal.portal().cancelRtpTraversal(traversal.entity());
		}
	}

	@Override
	public void close()
	{
		if(!closed.compareAndSet(false, true))
		{
			return;
		}
		List<UUID> portalIds = List.copyOf(registrations.keySet());
		attendance.clear();
		registrations.clear();
		for(ActiveTraversal traversal : List.copyOf(activeTraversals.values()))
		{
			traversal.portal().cancelRtpTraversal(traversal.entity());
		}
		activeTraversals.clear();
		for(UUID portalId : portalIds)
		{
			service.unregister(portalId).whenComplete((changed, failure) ->
			{
				environment.sourceUnregistered(portalId);
				if(failure != null)
				{
					reportFailure("shutdown-unregister:" + portalId, failure);
				}
			});
		}
		environment.close();
	}

	private void prepareTraversal(
			LocalPortal portal,
			Entity entity,
			Traversive traversive,
			RtpService.TraversalPreparation preparation)
	{
		if(!sourceEligible(portal, entity) || !portal.canContinueRtpTraversal(entity))
		{
			failTraversal(portal, entity, preparation, null);
			return;
		}
		Optional<RtpService.Snapshot> snapshot = service.snapshot(portal.getId());
		if(snapshot.isEmpty() || snapshot.get().generation() != preparation.generation())
		{
			failTraversal(portal, entity, preparation, null);
			return;
		}
		RtpValidationRequest.EntityEnvelope envelope = entityEnvelope(entity);
		RtpService.SearchRequest request = new RtpService.SearchRequest(
				portal.getId(),
				preparation.generation(),
				snapshot.get().settings(),
				preparation.claim().destination());
		CompletionStage<RtpService.LoadedCandidate> loadStage;
		try
		{
			loadStage = Objects.requireNonNull(environment.loadTraversal(request, envelope), "traversal load stage");
		}
		catch(RuntimeException exception)
		{
			failTraversal(portal, entity, preparation, exception);
			return;
		}
		loadStage.whenComplete((loaded, loadFailure) ->
		{
			if(loadFailure != null || loaded == null)
			{
				failTraversal(portal, entity, preparation, loadFailure);
				return;
			}
			RetainedTraversal retained = new RetainedTraversal(loaded.retention());
			validateTraversal(portal, entity, traversive, preparation, loaded.validationRequest(), retained);
		});
	}

	private void validateTraversal(
			LocalPortal portal,
			Entity entity,
			Traversive traversive,
			RtpService.TraversalPreparation preparation,
			RtpValidationRequest validationRequest,
			RetainedTraversal retained)
	{
		CompletionStage<RtpSafetyResult> validationStage;
		try
		{
			validationStage = Objects.requireNonNull(environment.validate(validationRequest), "traversal validation stage");
		}
		catch(RuntimeException exception)
		{
			retained.close();
			failTraversal(portal, entity, preparation, exception);
			return;
		}
		validationStage.whenComplete((safety, validationFailure) ->
		{
			if(validationFailure != null || safety == null || !safety.safe()
					|| !preparation.claim().destination().equals(safety.destination()))
			{
				retained.close();
				failTraversal(portal, entity, preparation, validationFailure);
				return;
			}
			checkTraversalAccess(portal, entity, traversive, preparation, validationRequest.entityEnvelope(), retained);
		});
	}

	private void checkTraversalAccess(
			LocalPortal portal,
			Entity entity,
			Traversive traversive,
			RtpService.TraversalPreparation preparation,
			RtpValidationRequest.EntityEnvelope envelope,
			RetainedTraversal retained)
	{
		if(!(entity instanceof Player player))
		{
			dispatchTraversal(portal, entity, traversive, preparation, envelope, retained);
			return;
		}
		CompletionStage<RtpAccessResult> accessStage;
		try
		{
			accessStage = Objects.requireNonNull(
					environment.canUse(player, preparation.claim().destination()),
					"traversal access stage");
		}
		catch(RuntimeException exception)
		{
			retained.close();
			failTraversal(portal, entity, preparation, exception);
			return;
		}
		accessStage.whenComplete((access, accessFailure) ->
		{
			if(accessFailure != null || access == null || !access.allowed())
			{
				retained.close();
				failTraversal(portal, entity, preparation, accessFailure != null
						? accessFailure : access == null ? null : access.failure().orElse(null));
				return;
			}
			dispatchTraversal(portal, entity, traversive, preparation, envelope, retained);
		});
	}

	private void dispatchTraversal(
			LocalPortal portal,
			Entity entity,
			Traversive traversive,
			RtpService.TraversalPreparation preparation,
			RtpValidationRequest.EntityEnvelope envelope,
			RetainedTraversal retained)
	{
		boolean scheduled = environment.scheduleEntity(entity, () -> guardStage(portal, entity, preparation, retained, () ->
		{
			if(!sourceEligible(portal, entity) || !portal.canContinueRtpTraversal(entity))
			{
				retained.close();
				failTraversal(portal, entity, preparation, null);
				return;
			}
			World targetWorld = environment.resolveWorld(preparation.claim().destination().worldKey());
			if(targetWorld == null)
			{
				retained.close();
				failTraversal(portal, entity, preparation, null);
				return;
			}
			PortalFrame targetFrame = targetFrameFor(portal.getFrame());
			Location target = targetLocation(targetWorld, preparation.claim().destination(), traversive, targetFrame, envelope);
			service.markTraversalDispatched(preparation).whenComplete((marked, markFailure) ->
			{
				if(markFailure != null || !Boolean.TRUE.equals(marked))
				{
					retained.close();
					failTraversal(portal, entity, preparation, markFailure);
					return;
				}
				CompletionStage<Boolean> teleportStage;
				try
				{
					teleportStage = Objects.requireNonNull(environment.teleport(entity, target), "teleport stage");
				}
				catch(RuntimeException exception)
				{
					retained.close();
					failTraversal(portal, entity, preparation, exception);
					return;
				}
				teleportStage.whenComplete((teleported, teleportFailure) ->
				{
					if(teleportFailure != null || !Boolean.TRUE.equals(teleported))
					{
						retained.close();
						failTraversal(portal, entity, preparation, teleportFailure);
						return;
					}
					confirmTraversal(portal, entity, traversive, preparation, targetFrame, target, retained);
				});
			});
		}), () ->
		{
			retained.close();
			failTraversal(portal, entity, preparation, null);
		});
		if(!scheduled)
		{
			retained.close();
			failTraversal(portal, entity, preparation,
					new IllegalStateException("Entity scheduler rejected RTP teleport dispatch"));
		}
	}

	private void confirmTraversal(
			LocalPortal portal,
			Entity entity,
			Traversive traversive,
			RtpService.TraversalPreparation preparation,
			PortalFrame targetFrame,
			Location target,
			RetainedTraversal retained)
	{
		boolean scheduled = environment.scheduleEntity(entity, () -> guardStage(portal, entity, preparation, retained, () ->
		{
			if(!arrivedAt(entity, target) || attached(entity))
			{
				retained.close();
				failTraversal(portal, entity, preparation, null);
				return;
			}
			service.completeTraversal(preparation, true).whenComplete((completed, completionFailure) ->
			{
				retained.close();
				activeTraversals.remove(entity.getUniqueId());
				if(completionFailure != null || !Boolean.TRUE.equals(completed))
				{
					portal.cancelRtpTraversal(entity);
					if(completionFailure != null)
					{
						reportFailure("complete-success:" + portal.getId(), completionFailure);
					}
					return;
				}
				environment.completeSuccess(portal, entity, traversive, targetFrame, target);
			});
		}), () ->
		{
			retained.close();
			failTraversal(portal, entity, preparation, null);
		});
		if(!scheduled)
		{
			retained.close();
			failTraversal(portal, entity, preparation,
					new IllegalStateException("Entity scheduler rejected RTP arrival confirmation"));
		}
	}

	private void guardStage(
			LocalPortal portal,
			Entity entity,
			RtpService.TraversalPreparation preparation,
			RetainedTraversal retained,
			Runnable stage)
	{
		try
		{
			stage.run();
		}
		catch(RuntimeException exception)
		{
			if(retained != null)
			{
				retained.close();
			}
			failTraversal(portal, entity, preparation, exception);
		}
	}

	private void failTraversal(
			LocalPortal portal,
			Entity entity,
			RtpService.TraversalPreparation preparation,
			Throwable failure)
	{
		if(failure != null)
		{
			reportFailure("traversal:" + portal.getId(), failure);
		}
		activeTraversals.remove(entity.getUniqueId());
		portal.cancelRtpTraversal(entity);
		service.completeTraversal(preparation, false).whenComplete((completed, completionFailure) ->
		{
			if(completionFailure != null)
			{
				reportFailure("complete-failure:" + portal.getId(), completionFailure);
			}
		});
	}

	private ProjectionManager.RtpProjectionResult projectionResult(ILocalPortal portal, UUID viewerId, boolean attended)
	{
		Optional<RtpService.Snapshot> optionalSnapshot = service.snapshot(portal.getId());
		if(optionalSnapshot.isEmpty())
		{
			return new ProjectionManager.RtpProjectionResult(
					RtpProjectionView.none(viewerId, 0L),
					portal.isProjecting(),
					false,
					attended,
					RtpRotationMode.STATIC,
					RtpRimRenderer.Phase.PREPARING,
					0L,
					0L);
		}
		RtpService.Snapshot snapshot = optionalSnapshot.get();
		RtpRuntimeSnapshot runtime = snapshot.runtime();
		RtpSettings liveSettings = portal instanceof LocalPortal localPortal && localPortal.getRtpSettings() != null
				? localPortal.getRtpSettings() : snapshot.settings();
		long durationMillis = liveSettings.getRotationMode() == RtpRotationMode.TIMED
				? liveSettings.getCycleDurationMillis() : 0L;
		long elapsedMillis = durationMillis == 0L || runtime.nextRotationAtMillis() <= 0L
				? 0L : Math.max(0L, durationMillis - Math.max(0L, runtime.nextRotationAtMillis() - environment.nowMillis()));
		RtpProjectionView view = service.projectionView(portal.getId(), viewerId);
		RtpRimRenderer.Phase phase = runtime.ready() || view.state() == RtpProjectionView.State.READY
				? RtpRimRenderer.Phase.READY
				: runtime.sharedClaims() + runtime.playerClaims() + runtime.anonymousClaims() > 0
						? RtpRimRenderer.Phase.CLOSING : RtpRimRenderer.Phase.PREPARING;
		return new ProjectionManager.RtpProjectionResult(
				view,
				portal.isProjecting(),
				liveSettings.isRimEnabled(),
				attended,
				liveSettings.getRotationMode(),
				phase,
				elapsedMillis,
				durationMillis);
	}

	private PortalRegistration registration(LocalPortal portal)
	{
		RtpSettings settings = Objects.requireNonNull(portal.getRtpSettings(), "portal RTP settings");
		Location center = Objects.requireNonNull(portal.getCenter(), "portal center");
		double centerX = settings.getCenterMode() == RtpCenterMode.CUSTOM
				? Objects.requireNonNull(settings.getCustomCenterX(), "custom center X").doubleValue() : center.getX();
		double centerZ = settings.getCenterMode() == RtpCenterMode.CUSTOM
				? Objects.requireNonNull(settings.getCustomCenterZ(), "custom center Z").doubleValue() : center.getZ();
		World sourceWorld = Objects.requireNonNull(portal.getStructure().getWorld(), "portal source world");
		World targetWorld = settings.getTargetWorld();
		RtpService.Registration registration = new RtpService.Registration(
				portal.getId(), settings, centerX, centerZ, portal.getId().getMostSignificantBits() ^ portal.getId().getLeastSignificantBits());
		return new PortalRegistration(
				registration,
				sourceWorld.getUID(),
				targetWorld == null ? null : targetWorld.getUID());
	}

	private Attendance attendance(LocalPortal portal, long touchedAtMillis)
	{
		AxisAlignedBB view = Objects.requireNonNull(portal.getView(), "portal view");
		World world = Objects.requireNonNull(portal.getStructure().getWorld(), "portal source world");
		return new Attendance(
				world.getUID(),
				view.getXa(),
				view.getXb(),
				view.getYa(),
				view.getYb(),
				view.getZa(),
				view.getZb(),
				touchedAtMillis);
	}

	private void leave(AttendanceKey key)
	{
		Attendance removed = attendance.remove(key);
		if(removed == null)
		{
			return;
		}
		service.leaveViewer(key.portalId(), key.viewerId()).whenComplete((changed, failure) ->
		{
			if(failure != null)
			{
				reportFailure("attendance-leave:" + key.portalId(), failure);
			}
		});
	}

	private void removePortalAttendance(UUID portalId)
	{
		List<AttendanceKey> removals = new ArrayList<AttendanceKey>();
		for(AttendanceKey key : attendance.keySet())
		{
			if(key.portalId().equals(portalId))
			{
				removals.add(key);
			}
		}
		for(AttendanceKey key : removals)
		{
			attendance.remove(key);
		}
	}

	private boolean sourceEligible(LocalPortal portal, Entity entity)
	{
		if(entity == null || !entity.isValid() || attached(entity) || !physicallyTraversable(entity))
		{
			return false;
		}
		PortalStructure structure = portal.getStructure();
		Location location = entity.getLocation();
		if(structure == null || structure.getWorld() == null || location.getWorld() == null
				|| !structure.getWorld().equals(location.getWorld()))
		{
			return false;
		}
		AxisAlignedBB area = structure.getArea();
		return area != null
				&& location.getX() >= area.getXa() - SOURCE_CAPTURE_MARGIN
				&& location.getX() <= area.getXb() + SOURCE_CAPTURE_MARGIN
				&& location.getY() >= area.getYa() - SOURCE_CAPTURE_MARGIN
				&& location.getY() <= area.getYb() + SOURCE_CAPTURE_MARGIN
				&& location.getZ() >= area.getZa() - SOURCE_CAPTURE_MARGIN
				&& location.getZ() <= area.getZb() + SOURCE_CAPTURE_MARGIN;
	}

	private boolean attached(Entity entity)
	{
		return entity.getVehicle() != null || !entity.getPassengers().isEmpty();
	}

	private RtpValidationRequest.EntityEnvelope entityEnvelope(Entity entity)
	{
		Location location = entity.getLocation();
		BoundingBox box = entity.getBoundingBox();
		double[] x = normalizedEnvelopeAxis(box.getMinX() - location.getX(), box.getMaxX() - location.getX());
		double[] y = normalizedEnvelopeAxis(box.getMinY() - location.getY(), box.getMaxY() - location.getY());
		double[] z = normalizedEnvelopeAxis(box.getMinZ() - location.getZ(), box.getMaxZ() - location.getZ());
		return new RtpValidationRequest.EntityEnvelope(x[0], x[1], y[0], y[1], z[0], z[1]);
	}

	public static boolean physicallyTraversable(Entity entity)
	{
		BoundingBox box = entity.getBoundingBox();
		return box.getMaxX() > box.getMinX() && box.getMaxY() > box.getMinY() && box.getMaxZ() > box.getMinZ();
	}

	static double[] normalizedEnvelopeAxis(double minimum, double maximum)
	{
		if(maximum - minimum >= MINIMUM_ENVELOPE_EXTENT)
		{
			return new double[] {minimum, maximum};
		}
		double center = (minimum + maximum) / 2.0D;
		double half = MINIMUM_ENVELOPE_EXTENT / 2.0D;
		return new double[] {center - half, center + half};
	}

	static PortalFrame targetFrameFor(PortalFrame sourceFrame)
	{
		Direction sourceNormal = sourceFrame.getNormal();
		Direction horizontal = sourceNormal.isVertical() ? sourceFrame.getUp() : sourceNormal;
		if(horizontal.isVertical())
		{
			horizontal = Direction.N;
		}
		return PortalFrame.fromNormalUp(horizontal, Direction.U);
	}

	private Location targetLocation(
			World world,
			RtpDestination destination,
			Traversive traversive,
			PortalFrame targetFrame,
			RtpValidationRequest.EntityEnvelope envelope)
	{
		double centerXOffset = (envelope.minimumXOffset() + envelope.maximumXOffset()) / 2.0D;
		double centerZOffset = (envelope.minimumZOffset() + envelope.maximumZOffset()) / 2.0D;
		Location target = new Location(
				world,
				destination.blockX() + 0.5D - centerXOffset,
				destination.feetY() - envelope.minimumYOffset(),
				destination.blockZ() + 0.5D - centerZOffset);
		Vector look = traversive.getOutLook(targetFrame);
		if(look.lengthSquared() > 1.0E-12D)
		{
			target.setDirection(look);
		}
		return target;
	}

	private boolean arrivedAt(Entity entity, Location target)
	{
		Location current = entity.getLocation();
		return current.getWorld() != null
				&& current.getWorld().equals(target.getWorld())
				&& Math.abs(current.getX() - target.getX()) <= ARRIVAL_TOLERANCE
				&& Math.abs(current.getY() - target.getY()) <= ARRIVAL_TOLERANCE
				&& Math.abs(current.getZ() - target.getZ()) <= ARRIVAL_TOLERANCE;
	}

	private void reportFailure(String context, Throwable failure)
	{
		Throwable requiredFailure = Objects.requireNonNull(failure, "failure");
		if(reportedFailures.add(context))
		{
			environment.reportFailure(context, requiredFailure);
		}
	}

	public interface Environment extends AutoCloseable
	{
		long nowMillis();

		void sourceRegistered(UUID portalId, Location anchor);

		void sourceUnregistered(UUID portalId);

		World resolveWorld(String worldKey);

		CompletionStage<RtpService.LoadedCandidate> loadTraversal(
				RtpService.SearchRequest request,
				RtpValidationRequest.EntityEnvelope envelope);

		CompletionStage<RtpSafetyResult> validate(RtpValidationRequest request);

		CompletionStage<RtpAccessResult> canUse(Player player, RtpDestination destination);

		boolean scheduleEntity(Entity entity, Runnable command, Runnable retired);

		CompletionStage<Boolean> teleport(Entity entity, Location target);

		void completeSuccess(LocalPortal portal, Entity entity, Traversive traversive, PortalFrame targetFrame, Location target);

		void dispatchRim(LocalPortal portal, Player observer, RtpRimRenderer.Sample sample);

		void worldUnloaded(UUID worldId);

		void reportFailure(String context, Throwable failure);

		@Override
		void close();
	}

	private record PortalRegistration(
			RtpService.Registration registration,
			UUID sourceWorldId,
			UUID targetWorldId)
	{
		private PortalRegistration
		{
			Objects.requireNonNull(registration, "registration");
			Objects.requireNonNull(sourceWorldId, "sourceWorldId");
		}

		private boolean hasSameRouteAs(PortalRegistration other)
		{
			return registration.portalId().equals(other.registration.portalId())
					&& Double.compare(registration.centerX(), other.registration.centerX()) == 0
					&& Double.compare(registration.centerZ(), other.registration.centerZ()) == 0
					&& registration.seed() == other.registration.seed()
					&& registration.settings().hasSameRouteAs(other.registration.settings())
					&& sourceWorldId.equals(other.sourceWorldId)
					&& Objects.equals(targetWorldId, other.targetWorldId);
		}
	}

	private record AttendanceKey(UUID portalId, UUID viewerId)
	{
		private AttendanceKey
		{
			Objects.requireNonNull(portalId, "portalId");
			Objects.requireNonNull(viewerId, "viewerId");
		}
	}

	private record Attendance(
			UUID worldId,
			double minimumX,
			double maximumX,
			double minimumY,
			double maximumY,
			double minimumZ,
			double maximumZ,
			long touchedAtMillis)
	{
		private boolean contains(Location location)
		{
			return location.getWorld() != null
					&& worldId.equals(location.getWorld().getUID())
					&& location.getX() >= minimumX && location.getX() <= maximumX
					&& location.getY() >= minimumY && location.getY() <= maximumY
					&& location.getZ() >= minimumZ && location.getZ() <= maximumZ;
		}
	}

	private record ActiveTraversal(LocalPortal portal, Entity entity)
	{
		private ActiveTraversal
		{
			Objects.requireNonNull(portal, "portal");
			Objects.requireNonNull(entity, "entity");
		}
	}

	private static final class RetainedTraversal implements RtpService.Retention
	{
		private final RtpService.Retention retention;
		private final AtomicBoolean closed;

		private RetainedTraversal(RtpService.Retention retention)
		{
			this.retention = Objects.requireNonNull(retention, "retention");
			closed = new AtomicBoolean(false);
		}

		@Override
		public void close()
		{
			if(closed.compareAndSet(false, true))
			{
				retention.close();
			}
		}
	}
}
