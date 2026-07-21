package art.arcane.wormholes.portal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicInteger;

import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.Vector;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import art.arcane.wormholes.Wormholes;
import art.arcane.wormholes.portal.rtp.BukkitRtpRuntime;
import art.arcane.wormholes.portal.rtp.RtpAccessResult;
import art.arcane.wormholes.portal.rtp.RtpDestination;
import art.arcane.wormholes.portal.rtp.RtpProjectionView;
import art.arcane.wormholes.portal.rtp.RtpRotationMode;
import art.arcane.wormholes.portal.rtp.RtpSafetyResult;
import art.arcane.wormholes.portal.rtp.RtpService;
import art.arcane.wormholes.portal.rtp.RtpSettings;
import art.arcane.wormholes.portal.rtp.RtpValidationRequest;
import art.arcane.wormholes.util.Cuboid;

public final class RtpLiveRuntimeTest
{
	@AfterEach
	public void clearRuntime()
	{
		BukkitRtpRuntime runtime = Wormholes.rtpRuntime;
		Wormholes.rtpRuntime = null;
		if(runtime != null)
		{
			runtime.close();
		}
	}

	@Test
	public void synchronizeRegistersUpdatesAndUnregistersPortalRuntime()
	{
		Harness harness = new Harness(RtpRotationMode.STATIC);
		LocalPortal portal = harness.portal;

		harness.runtime.synchronize(portal);
		long originalGeneration = harness.service.snapshot(portal.getId()).orElseThrow().generation();

		portal.setRtpSettings(RtpSettings.builder(harness.world).radii(32, 96).build());
		harness.runtime.synchronize(portal);
		long updatedGeneration = harness.service.snapshot(portal.getId()).orElseThrow().generation();
		portal.setType(PortalType.PORTAL);

		assertNotEquals(originalGeneration, updatedGeneration);
		assertTrue(harness.service.snapshot(portal.getId()).isEmpty());
	}

	@Test
	public void projectionAttendanceOpensRtpWithoutTunnelAndIdleSweepClosesIt()
	{
		Harness harness = new Harness(RtpRotationMode.STATIC);
		harness.runtime.synchronize(harness.portal);

		harness.runtime.touch(harness.portal, harness.viewer.player());
		harness.portal.update();

		assertTrue(harness.runtime.isReady(harness.portal.getId()));
		assertTrue(harness.portal.isOpen());
		assertFalse(harness.portal.hasTunnel());

		harness.environment.nowMillis = 2_000L;
		harness.runtime.sweepAttendance();
		harness.portal.update();

		assertFalse(harness.runtime.isReady(harness.portal.getId()));
		assertFalse(harness.portal.isOpen());
	}

	@Test
	public void permissionAndPassengerRejectionsLeaveTravelerUnchanged()
	{
		Harness harness = new Harness(RtpRotationMode.STATIC);
		harness.prepareReady();
		MutableEntity denied = MutableEntity.player(harness.world, harness.sourceLocation(), true);
		MutableEntity passenger = MutableEntity.entity(harness.world, harness.sourceLocation());
		passenger.passengers = List.of(MutableEntity.entity(harness.world, harness.sourceLocation()).entity());
		Location deniedBefore = denied.location.clone();
		Location passengerBefore = passenger.location.clone();

		harness.runtime.traverse(harness.portal, denied.entity(), harness.traversive(denied.entity()));
		harness.runtime.traverse(harness.portal, passenger.entity(), harness.traversive(passenger.entity()));

		assertLocation(deniedBefore, denied.location);
		assertLocation(passengerBefore, passenger.location);
		assertEquals(0, harness.environment.teleports.get());
		assertEquals(0, harness.environment.successes.get());
	}

	@Test
	public void travelerLeavingSourceBeforeDispatchIsRejectedUnchanged()
	{
		Harness harness = new Harness(RtpRotationMode.STATIC);
		harness.prepareReady();
		MutableEntity traveler = MutableEntity.entity(harness.world, harness.sourceLocation());
		Location initial = traveler.location.clone();
		harness.environment.deferTraversalLoad = true;

		harness.runtime.traverse(harness.portal, traveler.entity(), harness.traversive(traveler.entity()));
		traveler.location = new Location(harness.world, 50.0D, 90.0D, 50.0D);
		harness.environment.completeDeferredLoad();

		assertEquals(0, harness.environment.teleports.get());
		assertEquals(0, harness.environment.successes.get());
		assertFalse(LocalPortal.isTeleportCoolingDown(traveler.id, harness.environment.nowMillis));
		assertFalse(LocalPortal.isReentryLatched(traveler.id));
		assertFalse(initial.equals(traveler.location));
	}

	@Test
	public void successfulTraversalConsumesAndRotatesOnTraversalRoute()
	{
		Harness harness = new Harness(RtpRotationMode.ON_TRAVERSAL);
		harness.prepareReady();
		MutableEntity traveler = MutableEntity.entity(harness.world, harness.sourceLocation());
		RtpDestination activeBefore = harness.service.snapshot(harness.portal.getId()).orElseThrow().runtime().active();

		harness.runtime.traverse(harness.portal, traveler.entity(), harness.traversive(traveler.entity()));

		RtpDestination activeAfter = harness.service.snapshot(harness.portal.getId()).orElseThrow().runtime().active();
		assertEquals(1, harness.environment.teleports.get());
		assertEquals(1, harness.environment.successes.get());
		assertNotEquals(activeBefore, activeAfter);
		assertTrue(LocalPortal.isTeleportCoolingDown(traveler.id, harness.environment.nowMillis));
		assertTrue(LocalPortal.isReentryLatched(traveler.id));
	}

	@Test
	public void traversalPreservesFacingAndVelocityFromEitherPortalSide()
	{
		assertTraversalOrientation(true, new Vector(0.2D, -0.1D, -0.97D), new Vector(0.15D, 0.05D, -0.45D));
		assertTraversalOrientation(false, new Vector(-0.3D, 0.2D, 0.93D), new Vector(-0.1D, -0.05D, 0.4D));
	}

	@Test
	public void traversalPlacesAsymmetricEntityInsideValidatedEnvelope()
	{
		Harness harness = new Harness(RtpRotationMode.STATIC);
		harness.prepareReady();
		MutableEntity traveler = MutableEntity.entity(harness.world, harness.sourceLocation())
				.withEnvelope(-0.2D, 0.8D, -0.5D, 1.5D, -0.7D, 0.1D);

		harness.runtime.traverse(harness.portal, traveler.entity(), harness.traversive(traveler.entity()));

		assertEquals(100.2D, traveler.location.getX(), 1.0E-9D);
		assertEquals(70.5D, traveler.location.getY(), 1.0E-9D);
		assertEquals(100.8D, traveler.location.getZ(), 1.0E-9D);
		BoundingBox arrived = traveler.boundingBox();
		assertEquals(100.0D, arrived.getMinX(), 1.0E-9D);
		assertEquals(101.0D, arrived.getMaxX(), 1.0E-9D);
		assertEquals(70.0D, arrived.getMinY(), 1.0E-9D);
		assertEquals(72.0D, arrived.getMaxY(), 1.0E-9D);
		assertEquals(100.1D, arrived.getMinZ(), 1.0E-9D);
		assertEquals(100.9D, arrived.getMaxZ(), 1.0E-9D);
	}

	@Test
	public void loadSafetyAccessAndTeleportFailuresRestoreRouteWithoutMovement()
	{
		assertFailureLeavesTravelerUnchanged(FailureMode.LOAD);
		assertFailureLeavesTravelerUnchanged(FailureMode.SAFETY);
		assertFailureLeavesTravelerUnchanged(FailureMode.ACCESS);
		assertFailureLeavesTravelerUnchanged(FailureMode.TELEPORT);
	}

	@Test
	public void worldUnloadAndPluginCloseReleaseRuntimeState()
	{
		Harness harness = new Harness(RtpRotationMode.STATIC);
		harness.prepareReady();
		UUID portalId = harness.portal.getId();

		harness.runtime.worldUnloaded(harness.world.getUID());

		assertTrue(harness.service.snapshot(portalId).isEmpty());
		assertEquals(1, harness.environment.worldUnloads.get());

		harness.runtime.synchronize(harness.portal);
		harness.runtime.close();

		assertTrue(harness.service.snapshot(portalId).isEmpty());
		assertEquals(1, harness.environment.closes.get());
	}

	@Test
	public void pluginCloseCancelsPreparingTraversalAdmission()
	{
		Harness harness = new Harness(RtpRotationMode.STATIC);
		harness.prepareReady();
		MutableEntity traveler = MutableEntity.entity(harness.world, harness.sourceLocation());
		harness.environment.deferTraversalLoad = true;

		harness.runtime.traverse(harness.portal, traveler.entity(), harness.traversive(traveler.entity()));
		harness.runtime.close();

		assertTrue(harness.portal.beginRtpTraversal(traveler.entity(), harness.environment.nowMillis));
		harness.portal.cancelRtpTraversal(traveler.entity());
	}

	@Test
	public void leaveViewerCancelsInFlightTraversal()
	{
		Harness harness = new Harness(RtpRotationMode.STATIC);
		harness.prepareReady();
		MutableEntity traveler = MutableEntity.entity(harness.world, harness.sourceLocation());
		harness.environment.deferTraversalLoad = true;

		harness.runtime.traverse(harness.portal, traveler.entity(), harness.traversive(traveler.entity()));
		harness.runtime.leaveViewer(traveler.id);

		assertTrue(harness.portal.beginRtpTraversal(traveler.entity(), harness.environment.nowMillis));
		harness.portal.cancelRtpTraversal(traveler.entity());
	}

	@Test
	public void normalPortalAndGatewayRemainOutsideRtpRuntime()
	{
		Harness harness = new Harness(RtpRotationMode.STATIC);
		LocalPortal normal = harness.portal(PortalType.PORTAL, RtpRotationMode.STATIC);
		LocalPortal gateway = harness.portal(PortalType.GATEWAY, RtpRotationMode.STATIC);

		harness.runtime.synchronize(normal);
		harness.runtime.synchronize(gateway);

		assertFalse(harness.runtime.supports(normal));
		assertFalse(harness.runtime.supports(gateway));
		assertTrue(harness.service.snapshot(normal.getId()).isEmpty());
		assertTrue(harness.service.snapshot(gateway.getId()).isEmpty());
	}

	private static void assertFailureLeavesTravelerUnchanged(FailureMode failureMode)
	{
		Harness harness = new Harness(RtpRotationMode.ON_TRAVERSAL);
		harness.prepareReady();
		MutableEntity traveler = failureMode == FailureMode.ACCESS
				? MutableEntity.player(harness.world, harness.sourceLocation(), false)
				: MutableEntity.entity(harness.world, harness.sourceLocation());
		Location before = traveler.location.clone();
		Vector velocityBefore = traveler.velocity.clone();
		RtpDestination activeBefore = harness.service.snapshot(harness.portal.getId()).orElseThrow().runtime().active();
		harness.environment.failureMode = failureMode;

		harness.runtime.traverse(harness.portal, traveler.entity(), harness.traversive(traveler.entity()));

		RtpDestination activeAfter = harness.service.snapshot(harness.portal.getId()).orElseThrow().runtime().active();
		assertLocation(before, traveler.location);
		assertEquals(velocityBefore, traveler.velocity);
		assertEquals(activeBefore, activeAfter);
		assertEquals(0, harness.environment.successes.get());
		assertFalse(LocalPortal.isTeleportCoolingDown(traveler.id, harness.environment.nowMillis));
		assertFalse(LocalPortal.isReentryLatched(traveler.id));
		harness.runtime.close();
	}

	private static void assertTraversalOrientation(boolean frontSide, Vector look, Vector velocity)
	{
		Harness harness = new Harness(RtpRotationMode.STATIC);
		try
		{
			harness.prepareReady();
			MutableEntity traveler = MutableEntity.entity(harness.world, harness.sourceLocation());
			Vector normalizedLook = look.clone().normalize();
			Traversive traversive = harness.traversive(traveler.entity(), frontSide, velocity, normalizedLook);

			harness.runtime.traverse(harness.portal, traveler.entity(), traversive);

			assertVector(normalizedLook, traveler.location.getDirection());
			assertVector(velocity, traveler.velocity);
		}
		finally
		{
			harness.runtime.close();
		}
	}

	private static void assertVector(Vector expected, Vector actual)
	{
		assertEquals(expected.getX(), actual.getX(), 1.0E-6D);
		assertEquals(expected.getY(), actual.getY(), 1.0E-6D);
		assertEquals(expected.getZ(), actual.getZ(), 1.0E-6D);
	}

	private static void assertLocation(Location expected, Location actual)
	{
		assertEquals(expected.getWorld(), actual.getWorld());
		assertEquals(expected.getX(), actual.getX());
		assertEquals(expected.getY(), actual.getY());
		assertEquals(expected.getZ(), actual.getZ());
		assertEquals(expected.getYaw(), actual.getYaw());
		assertEquals(expected.getPitch(), actual.getPitch());
	}

	private enum FailureMode
	{
		NONE,
		LOAD,
		SAFETY,
		ACCESS,
		TELEPORT
	}

	private static final class Harness
	{
		private final World world;
		private final LocalPortal portal;
		private final MutableEntity viewer;
		private final FakeEnvironment environment;
		private final RtpService service;
		private final BukkitRtpRuntime runtime;

		private Harness(RtpRotationMode rotationMode)
		{
			world = world("runtime");
			portal = portal(PortalType.RTP, rotationMode);
			viewer = MutableEntity.player(world, sourceLocation(), false);
			environment = new FakeEnvironment(world);
			service = service(environment);
			runtime = new BukkitRtpRuntime(service, environment, 1_000L);
			Wormholes.rtpRuntime = runtime;
		}

		private void prepareReady()
		{
			runtime.synchronize(portal);
			runtime.touch(portal, viewer.player());
			portal.update();
		}

		private LocalPortal portal(PortalType type, RtpRotationMode rotationMode)
		{
			PortalStructure structure = new PortalStructure();
			structure.setWorld(world);
			structure.setArea(new Cuboid(new Location(world, 0.0D, 64.0D, 0.0D), new Location(world, 0.0D, 66.0D, 2.0D)));
			LocalPortal created = new LocalPortal(UUID.randomUUID(), type, structure);
			created.setAmbientAttended(false);
			if(type == PortalType.RTP)
			{
				created.setRtpSettings(RtpSettings.builder(world).radii(16, 64).rotationMode(rotationMode).build());
			}
			return created;
		}

		private Location sourceLocation()
		{
			return new Location(world, 0.5D, 65.0D, 1.0D, 0.0F, 0.0F);
		}

		private Traversive traversive(Entity entity)
		{
			return traversive(entity, true, new Vector(0.0D, 0.0D, -0.4D), new Vector(0.0D, 0.0D, -1.0D));
		}

		private Traversive traversive(Entity entity, boolean frontSide, Vector velocity, Vector look)
		{
			PortalFrame inFrame = portal.getFrame().view(frontSide);
			return new Traversive(entity, inFrame, new Vector(0.5D, 65.0D, 1.0D),
					new Vector(0.5D, 65.0D, 1.0D), velocity, look, frontSide);
		}
	}

	private static RtpService service(FakeEnvironment environment)
	{
		RtpService.SourceDispatcher dispatcher = new RtpService.SourceDispatcher()
		{
			@Override
			public void execute(UUID portalId, Runnable command)
			{
				command.run();
			}

			@Override
			public void schedule(UUID portalId, Runnable command, long delayMillis)
			{
			}
		};
		RtpService.Dependencies dependencies = new RtpService.Dependencies(
				dispatcher,
				Runnable::run,
				() -> environment.nowMillis,
				(registration, generation, attempt) -> new RtpDestination(registration.settings().getTargetWorldKey(),
						100 + attempt * 4, 70, 100 + attempt * 4, generation, attempt),
				request -> CompletableFuture.completedFuture(new RtpService.LoadedCandidate(
						validationRequest(request.destination()), () -> environment.retentionCloses.incrementAndGet())),
				request -> CompletableFuture.completedFuture(RtpSafetyResult.safe(request.destination())),
				(portalId, viewerId, destination) -> CompletableFuture.completedFuture(RtpAccessResult.allowedResult()),
				(portalId, viewerId, destination, routeRevision) -> readyData(portalId, destination, routeRevision),
				new art.arcane.wormholes.portal.rtp.RtpRimRenderer());
		return new RtpService(dependencies);
	}

	private static RtpProjectionView.ReadyData readyData(UUID portalId, RtpDestination destination, long routeRevision)
	{
		RtpProjectionView.Vector3 right = new RtpProjectionView.Vector3(1.0D, 0.0D, 0.0D);
		RtpProjectionView.Vector3 up = new RtpProjectionView.Vector3(0.0D, 1.0D, 0.0D);
		RtpProjectionView.Vector3 forward = new RtpProjectionView.Vector3(0.0D, 0.0D, 1.0D);
		RtpProjectionView.SourceFrame source = new RtpProjectionView.SourceFrame(
				"minecraft:runtime", new RtpProjectionView.Point3(0.5D, 65.0D, 1.0D), right, up, forward, 3.0D, 3.0D, 1L);
		RtpProjectionView.Target target = new RtpProjectionView.Target(destination.worldKey(),
				new RtpProjectionView.Point3(destination.blockX() + 0.5D, destination.feetY(), destination.blockZ() + 0.5D),
				right, up, forward);
		return new RtpProjectionView.ReadyData(portalId, routeRevision, source, target);
	}

	private static RtpValidationRequest validationRequest(RtpDestination destination)
	{
		return validationRequest(destination, RtpValidationRequest.EntityEnvelope.baseline());
	}

	private static RtpValidationRequest validationRequest(
			RtpDestination destination,
			RtpValidationRequest.EntityEnvelope envelope)
	{
		return RtpValidationRequest.builder(destination)
				.worldBounds(-64, 320)
				.worldBorder(new RtpValidationRequest.WorldBorder(-30_000_000.0D, -30_000_000.0D, 30_000_000.0D, 30_000_000.0D))
				.regionSnapshots(List.of())
				.entityEnvelope(envelope)
				.build();
	}

	private static World world(String name)
	{
		UUID worldId = uuid("world-" + name);
		NamespacedKey key = new NamespacedKey("minecraft", name);
		InvocationHandler handler = (Object proxy, Method method, Object[] arguments) -> switch(method.getName())
		{
			case "getUID" -> worldId;
			case "getKey" -> key;
			case "getName" -> name;
			case "getMinHeight" -> -64;
			case "getMaxHeight" -> 320;
			case "getSeaLevel" -> 63;
			case "getEnvironment" -> World.Environment.NORMAL;
			case "equals" -> proxy == arguments[0];
			case "hashCode" -> System.identityHashCode(proxy);
			case "toString" -> "RtpRuntimeWorld[" + name + "]";
			default -> defaultValue(method.getReturnType());
		};
		return (World) Proxy.newProxyInstance(RtpLiveRuntimeTest.class.getClassLoader(), new Class<?>[] {World.class}, handler);
	}

	private static UUID uuid(String value)
	{
		return UUID.nameUUIDFromBytes(value.getBytes(StandardCharsets.UTF_8));
	}

	private static Object defaultValue(Class<?> type)
	{
		if(!type.isPrimitive())
		{
			return null;
		}
		if(type == boolean.class)
		{
			return false;
		}
		if(type == byte.class)
		{
			return (byte) 0;
		}
		if(type == short.class)
		{
			return (short) 0;
		}
		if(type == int.class)
		{
			return 0;
		}
		if(type == long.class)
		{
			return 0L;
		}
		if(type == float.class)
		{
			return 0.0F;
		}
		if(type == double.class)
		{
			return 0.0D;
		}
		if(type == char.class)
		{
			return '\0';
		}
		return null;
	}

	private static final class FakeEnvironment implements BukkitRtpRuntime.Environment
	{
		private final World world;
		private final AtomicInteger teleports;
		private final AtomicInteger successes;
		private final AtomicInteger worldUnloads;
		private final AtomicInteger closes;
		private final AtomicInteger retentionCloses;
		private long nowMillis;
		private FailureMode failureMode;
		private boolean deferTraversalLoad;
		private CompletableFuture<RtpService.LoadedCandidate> deferredLoad;

		private FakeEnvironment(World world)
		{
			this.world = world;
			teleports = new AtomicInteger();
			successes = new AtomicInteger();
			worldUnloads = new AtomicInteger();
			closes = new AtomicInteger();
			retentionCloses = new AtomicInteger();
			nowMillis = 0L;
			failureMode = FailureMode.NONE;
		}

		@Override
		public long nowMillis()
		{
			return nowMillis;
		}

		@Override
		public void sourceRegistered(UUID portalId, Location anchor)
		{
		}

		@Override
		public void sourceUnregistered(UUID portalId)
		{
		}

		@Override
		public World resolveWorld(String worldKey)
		{
			return world;
		}

		@Override
		public CompletionStage<RtpService.LoadedCandidate> loadTraversal(
				RtpService.SearchRequest request,
				RtpValidationRequest.EntityEnvelope envelope)
		{
			if(failureMode == FailureMode.LOAD)
			{
				return CompletableFuture.failedFuture(new IllegalStateException("load failed"));
			}
			RtpService.LoadedCandidate loaded = new RtpService.LoadedCandidate(
					validationRequest(request.destination(), envelope), () -> retentionCloses.incrementAndGet());
			if(deferTraversalLoad)
			{
				deferredLoad = new CompletableFuture<RtpService.LoadedCandidate>();
				return deferredLoad;
			}
			return CompletableFuture.completedFuture(loaded);
		}

		@Override
		public CompletionStage<RtpSafetyResult> validate(RtpValidationRequest request)
		{
			return CompletableFuture.completedFuture(failureMode == FailureMode.SAFETY
					? RtpSafetyResult.rejected(RtpSafetyResult.Code.HAZARD, request.destination())
					: RtpSafetyResult.safe(request.destination()));
		}

		@Override
		public CompletionStage<RtpAccessResult> canUse(Player player, RtpDestination destination)
		{
			return CompletableFuture.completedFuture(failureMode == FailureMode.ACCESS
					? RtpAccessResult.deniedResult()
					: RtpAccessResult.allowedResult());
		}

		@Override
		public boolean scheduleEntity(Entity entity, Runnable command, Runnable retired)
		{
			command.run();
			return true;
		}

		@Override
		public CompletionStage<Boolean> teleport(Entity entity, Location target)
		{
			teleports.incrementAndGet();
			if(failureMode == FailureMode.TELEPORT)
			{
				return CompletableFuture.completedFuture(Boolean.FALSE);
			}
			MutableEntity mutable = MutableEntity.of(entity);
			mutable.location = target.clone();
			return CompletableFuture.completedFuture(Boolean.TRUE);
		}

		@Override
		public void completeSuccess(LocalPortal portal, Entity entity, Traversive traversive, PortalFrame targetFrame, Location target)
		{
			successes.incrementAndGet();
			portal.completeRtpTraversal(entity, traversive, targetFrame, target);
		}

		@Override
		public void dispatchRim(LocalPortal portal, Player observer, art.arcane.wormholes.portal.rtp.RtpRimRenderer.Sample sample)
		{
		}

		@Override
		public void worldUnloaded(UUID worldId)
		{
			worldUnloads.incrementAndGet();
		}

		@Override
		public void reportFailure(String context, Throwable failure)
		{
		}

		@Override
		public void close()
		{
			closes.incrementAndGet();
		}

		private void completeDeferredLoad()
		{
			RtpDestination destination = new RtpDestination("minecraft:runtime", 100, 70, 100, 1L, 0);
			deferredLoad.complete(new RtpService.LoadedCandidate(validationRequest(destination), () -> retentionCloses.incrementAndGet()));
		}
	}

	private static final class MutableEntity implements InvocationHandler
	{
		private static final java.util.Map<Entity, MutableEntity> INSTANCES = new java.util.IdentityHashMap<Entity, MutableEntity>();
		private final UUID id;
		private final World world;
		private final boolean player;
		private final boolean permission;
		private final Entity proxy;
		private Location location;
		private Vector velocity;
		private List<Entity> passengers;
		private Entity vehicle;
		private double minimumXOffset;
		private double maximumXOffset;
		private double minimumYOffset;
		private double maximumYOffset;
		private double minimumZOffset;
		private double maximumZOffset;

		private MutableEntity(World world, Location location, boolean player, boolean permission)
		{
			id = UUID.randomUUID();
			this.world = world;
			this.player = player;
			this.permission = permission;
			this.location = location.clone();
			velocity = new Vector(0.1D, -0.2D, 0.3D);
			passengers = List.of();
			minimumXOffset = -0.3D;
			maximumXOffset = 0.3D;
			minimumYOffset = 0.0D;
			maximumYOffset = 1.8D;
			minimumZOffset = -0.3D;
			maximumZOffset = 0.3D;
			Class<?>[] interfaces = player ? new Class<?>[] {Player.class} : new Class<?>[] {Entity.class};
			proxy = (Entity) Proxy.newProxyInstance(RtpLiveRuntimeTest.class.getClassLoader(), interfaces, this);
			INSTANCES.put(proxy, this);
		}

		private static MutableEntity entity(World world, Location location)
		{
			return new MutableEntity(world, location, false, false);
		}

		private static MutableEntity player(World world, Location location, boolean permission)
		{
			return new MutableEntity(world, location, true, permission);
		}

		private static MutableEntity of(Entity entity)
		{
			return INSTANCES.get(entity);
		}

		private MutableEntity withEnvelope(
				double minimumXOffset,
				double maximumXOffset,
				double minimumYOffset,
				double maximumYOffset,
				double minimumZOffset,
				double maximumZOffset)
		{
			this.minimumXOffset = minimumXOffset;
			this.maximumXOffset = maximumXOffset;
			this.minimumYOffset = minimumYOffset;
			this.maximumYOffset = maximumYOffset;
			this.minimumZOffset = minimumZOffset;
			this.maximumZOffset = maximumZOffset;
			return this;
		}

		private BoundingBox boundingBox()
		{
			return new BoundingBox(
					location.getX() + minimumXOffset,
					location.getY() + minimumYOffset,
					location.getZ() + minimumZOffset,
					location.getX() + maximumXOffset,
					location.getY() + maximumYOffset,
					location.getZ() + maximumZOffset);
		}

		private Entity entity()
		{
			return proxy;
		}

		private Player player()
		{
			return (Player) proxy;
		}

		@Override
		public Object invoke(Object instance, Method method, Object[] arguments)
		{
			return switch(method.getName())
			{
				case "getUniqueId" -> id;
				case "getName" -> id.toString();
				case "getWorld" -> location.getWorld();
				case "getLocation" -> getLocation(arguments);
				case "getBoundingBox" -> boundingBox();
				case "getVelocity" -> velocity.clone();
				case "setVelocity" -> setVelocity(arguments);
				case "getPassengers" -> passengers;
				case "getVehicle" -> vehicle;
				case "isValid", "isOnline" -> true;
				case "isOp" -> false;
				case "hasPermission" -> permission;
				case "getFallDistance" -> 0.0F;
				case "hasGravity" -> true;
				case "equals" -> instance == arguments[0];
				case "hashCode" -> System.identityHashCode(instance);
				case "toString" -> player ? "RtpTestPlayer" : "RtpTestEntity";
				default -> defaultValue(method.getReturnType());
			};
		}

		private Object getLocation(Object[] arguments)
		{
			if(arguments == null || arguments.length == 0)
			{
				return location.clone();
			}
			Location output = (Location) arguments[0];
			output.setWorld(location.getWorld());
			output.setX(location.getX());
			output.setY(location.getY());
			output.setZ(location.getZ());
			output.setYaw(location.getYaw());
			output.setPitch(location.getPitch());
			return output;
		}

		private Object setVelocity(Object[] arguments)
		{
			velocity = ((Vector) arguments[0]).clone();
			return null;
		}
	}
}
