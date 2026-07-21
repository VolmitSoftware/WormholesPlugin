package art.arcane.wormholes.portal.rtp;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;

import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.util.Vector;

import art.arcane.volmlib.util.bukkit.WorldIdentity;
import art.arcane.volmlib.util.scheduling.FoliaScheduler;
import art.arcane.wormholes.PortalManager;
import art.arcane.wormholes.Wormholes;
import art.arcane.wormholes.chunk.BukkitChunkLeaseProvider;
import art.arcane.wormholes.platform.WormholesPlatform;
import art.arcane.wormholes.portal.ILocalPortal;
import art.arcane.wormholes.portal.LocalPortal;
import art.arcane.wormholes.portal.PortalFrame;
import art.arcane.wormholes.portal.PortalStructure;
import art.arcane.wormholes.portal.Traversive;
import art.arcane.wormholes.util.AxisAlignedBB;
import art.arcane.wormholes.util.Direction;

public final class BukkitRtpEnvironment implements BukkitRtpRuntime.Environment
{
	private static final long MILLIS_PER_TICK = 50L;
	private static final long ATTENDANCE_IDLE_MILLIS = 1_500L;

	private final Wormholes plugin;
	private final PortalManager portalManager;
	private final BukkitRtpCandidateLoader candidateLoader;
	private final RtpSafetyValidator safetyValidator;
	private final WorldGuardRtpDestinationAccessPolicy accessPolicy;
	private final ExecutorService searchExecutor;
	private final ConcurrentHashMap<UUID, Location> sourceAnchors;
	private final AtomicBoolean closed;

	public BukkitRtpEnvironment(Wormholes plugin, PortalManager portalManager)
	{
		this.plugin = Objects.requireNonNull(plugin, "plugin");
		this.portalManager = Objects.requireNonNull(portalManager, "portalManager");
		candidateLoader = new BukkitRtpCandidateLoader(plugin);
		safetyValidator = new RtpSafetyValidator();
		accessPolicy = new WorldGuardRtpDestinationAccessPolicy();
		searchExecutor = Executors.newFixedThreadPool(2, new RtpThreadFactory());
		sourceAnchors = new ConcurrentHashMap<UUID, Location>();
		closed = new AtomicBoolean(false);
	}

	public BukkitRtpRuntime createRuntime()
	{
		RtpService.Dependencies dependencies = new RtpService.Dependencies(
				new SourceDispatcher(),
				this::executeSearch,
				this::nowMillis,
				this::sample,
				candidateLoader::search,
				safetyValidator::validate,
				this::checkAccess,
				this::projection,
				new RtpRimRenderer());
		return new BukkitRtpRuntime(new RtpService(dependencies), this, ATTENDANCE_IDLE_MILLIS);
	}

	@Override
	public long nowMillis()
	{
		return System.currentTimeMillis();
	}

	@Override
	public void sourceRegistered(UUID portalId, Location anchor)
	{
		UUID requiredPortalId = Objects.requireNonNull(portalId, "portalId");
		Location requiredAnchor = Objects.requireNonNull(anchor, "anchor");
		if(requiredAnchor.getWorld() == null)
		{
			throw new IllegalArgumentException("RTP source anchor world is required");
		}
		sourceAnchors.put(requiredPortalId, requiredAnchor.clone());
	}

	@Override
	public void sourceUnregistered(UUID portalId)
	{
		sourceAnchors.remove(Objects.requireNonNull(portalId, "portalId"));
	}

	@Override
	public World resolveWorld(String worldKey)
	{
		return WorldIdentity.resolve(Objects.requireNonNull(worldKey, "worldKey")).orElse(null);
	}

	@Override
	public CompletionStage<RtpService.LoadedCandidate> loadTraversal(
			RtpService.SearchRequest request,
			RtpValidationRequest.EntityEnvelope envelope)
	{
		return candidateLoader.exact(request, envelope);
	}

	@Override
	public CompletionStage<RtpSafetyResult> validate(RtpValidationRequest request)
	{
		return safetyValidator.validate(request);
	}

	@Override
	public CompletionStage<RtpAccessResult> canUse(Player player, RtpDestination destination)
	{
		return accessPolicy.canUse(player, destination);
	}

	@Override
	public boolean scheduleEntity(Entity entity, Runnable command, Runnable retired)
	{
		return WormholesPlatform.scheduleEntity(plugin, entity, command, retired, 0L);
	}

	@Override
	public CompletionStage<Boolean> teleport(Entity entity, Location target)
	{
		return WormholesPlatform.teleport(plugin, entity, target, PlayerTeleportEvent.TeleportCause.PLUGIN);
	}

	@Override
	public void completeSuccess(
			LocalPortal portal,
			Entity entity,
			Traversive traversive,
			PortalFrame targetFrame,
			Location target)
	{
		portal.completeRtpTraversal(entity, traversive, targetFrame, target);
	}

	@Override
	public void dispatchRim(LocalPortal portal, Player observer, RtpRimRenderer.Sample sample)
	{
		if(!observer.isOnline() || portal.getStructure() == null || portal.getStructure().getWorld() == null
				|| !observer.getWorld().equals(portal.getStructure().getWorld()))
		{
			return;
		}
		AxisAlignedBB area = portal.getStructure().getArea();
		if(area == null)
		{
			return;
		}
		RtpRimRenderer.Color color = sample.color();
		Particle.DustOptions dust = new Particle.DustOptions(
				Color.fromRGB(color.red(), color.green(), color.blue()),
				1.0F);
		double minimumX = Math.min(area.getXa(), area.getXb());
		double maximumX = Math.max(area.getXa(), area.getXb());
		double minimumY = Math.min(area.getYa(), area.getYb());
		double maximumY = Math.max(area.getYa(), area.getYb());
		double minimumZ = Math.min(area.getZa(), area.getZb());
		double maximumZ = Math.max(area.getZa(), area.getZb());
		World world = portal.getStructure().getWorld();
		List<Location> corners = List.of(
				new Location(world, minimumX, minimumY, minimumZ),
				new Location(world, minimumX, minimumY, maximumZ),
				new Location(world, minimumX, maximumY, minimumZ),
				new Location(world, minimumX, maximumY, maximumZ),
				new Location(world, maximumX, minimumY, minimumZ),
				new Location(world, maximumX, minimumY, maximumZ),
				new Location(world, maximumX, maximumY, minimumZ),
				new Location(world, maximumX, maximumY, maximumZ));
		for(Location corner : corners)
		{
			observer.spawnParticle(Particle.DUST, corner, 1, dust);
		}
	}

	@Override
	public void worldUnloaded(UUID worldId)
	{
		UUID requiredWorldId = Objects.requireNonNull(worldId, "worldId");
		candidateLoader.worldUnloaded(requiredWorldId);
		BukkitChunkLeaseProvider.worldUnloaded(requiredWorldId);
	}

	@Override
	public void reportFailure(String context, Throwable failure)
	{
		plugin.getLogger().log(Level.WARNING, "RTP runtime failure in " + context, failure);
	}

	@Override
	public void close()
	{
		if(!closed.compareAndSet(false, true))
		{
			return;
		}
		candidateLoader.close();
		searchExecutor.shutdownNow();
	}

	private void executeSearch(Runnable command)
	{
		if(closed.get())
		{
			throw new RejectedExecutionException("RTP environment is closed");
		}
		searchExecutor.execute(command);
	}

	private RtpDestination sample(RtpService.Registration registration, long generation, int attempt)
	{
		RtpSampler sampler = new RtpSampler(registration.centerX(), registration.centerZ(), registration.seed());
		return sampler.sample(registration.settings(), generation, attempt);
	}

	private CompletionStage<RtpAccessResult> checkAccess(
			UUID portalId,
			Optional<UUID> viewerId,
			RtpDestination destination)
	{
		if(viewerId.isEmpty())
		{
			return java.util.concurrent.CompletableFuture.completedFuture(RtpAccessResult.allowedResult());
		}
		Player player = plugin.getServer().getPlayer(viewerId.get());
		if(player == null || !player.isOnline())
		{
			return java.util.concurrent.CompletableFuture.completedFuture(
					RtpAccessResult.failureResult(new IllegalStateException("RTP viewer is unavailable")));
		}
		return accessPolicy.canUse(player, destination);
	}

	private RtpProjectionView.ReadyData projection(
			UUID portalId,
			UUID viewerId,
			RtpDestination destination,
			long routeRevision)
	{
		ILocalPortal candidate = portalManager.getLocalPortal(portalId);
		if(!(candidate instanceof LocalPortal portal) || portal.getStructure() == null || portal.getStructure().getWorld() == null)
		{
			throw new IllegalStateException("RTP source portal is unavailable");
		}
		PortalStructure structure = portal.getStructure();
		PortalFrame sourceFrame = portal.getFrame();
		PortalFrame targetFrame = targetFrame(sourceFrame);
		Location center = structure.getCenter();
		AxisAlignedBB area = structure.getArea();
		RtpProjectionView.SourceFrame source = new RtpProjectionView.SourceFrame(
				WorldIdentity.serialize(structure.getWorld()),
				point(center.getX(), center.getY(), center.getZ()),
				vector(sourceFrame.getRight()),
				vector(sourceFrame.getUp()),
				vector(sourceFrame.getNormal().reverse()),
				axisSpan(area, sourceFrame.getRight()),
				axisSpan(area, sourceFrame.getUp()),
				structure.getRevision());
		RtpProjectionView.Target target = new RtpProjectionView.Target(
				destination.worldKey(),
				point(destination.blockX() + 0.5D, destination.feetY(), destination.blockZ() + 0.5D),
				vector(targetFrame.getRight()),
				vector(targetFrame.getUp()),
				vector(targetFrame.getNormal().reverse()));
		return new RtpProjectionView.ReadyData(routeId(portalId, destination), routeRevision, source, target);
	}

	private RtpProjectionView.Point3 point(double x, double y, double z)
	{
		return new RtpProjectionView.Point3(x, y, z);
	}

	private RtpProjectionView.Vector3 vector(Direction direction)
	{
		Vector vector = direction.toVector();
		return new RtpProjectionView.Vector3(vector.getX(), vector.getY(), vector.getZ());
	}

	private double axisSpan(AxisAlignedBB area, Direction direction)
	{
		if(area == null)
		{
			return 1.0D;
		}
		return Math.max(1.0D,
				Math.abs(direction.x()) * area.sizeX()
						+ Math.abs(direction.y()) * area.sizeY()
						+ Math.abs(direction.z()) * area.sizeZ());
	}

	private PortalFrame targetFrame(PortalFrame sourceFrame)
	{
		Direction horizontal = sourceFrame.getNormal().isVertical() ? sourceFrame.getUp() : sourceFrame.getNormal();
		return PortalFrame.fromNormalUp(horizontal.isVertical() ? Direction.N : horizontal, Direction.U);
	}

	private UUID routeId(UUID portalId, RtpDestination destination)
	{
		String value = portalId + ":" + destination.worldKey() + ":" + destination.blockX() + ":"
				+ destination.feetY() + ":" + destination.blockZ() + ":" + destination.generation() + ":" + destination.attempt();
		return UUID.nameUUIDFromBytes(value.getBytes(StandardCharsets.UTF_8));
	}

	private long delayTicks(long delayMillis)
	{
		long normalized = Math.max(0L, delayMillis);
		long ticks = normalized / MILLIS_PER_TICK;
		return normalized % MILLIS_PER_TICK == 0L ? ticks : ticks + 1L;
	}

	private Location sourceAnchor(UUID portalId)
	{
		Location anchor = sourceAnchors.get(portalId);
		if(anchor == null || anchor.getWorld() == null)
		{
			throw new IllegalStateException("RTP source portal is unavailable: " + portalId);
		}
		return anchor.clone();
	}

	private final class SourceDispatcher implements RtpService.SourceDispatcher
	{
		@Override
		public void execute(UUID portalId, Runnable command)
		{
			Location center = sourceAnchor(portalId);
			World world = center.getWorld();
			int chunkX = center.getBlockX() >> 4;
			int chunkZ = center.getBlockZ() >> 4;
			if(FoliaScheduler.isOwnedByCurrentRegion(world, chunkX, chunkZ))
			{
				command.run();
				return;
			}
			if(!FoliaScheduler.runRegion(plugin, center, command))
			{
				throw new RejectedExecutionException("RTP source region rejected operation for " + portalId);
			}
		}

		@Override
		public void schedule(UUID portalId, Runnable command, long delayMillis)
		{
			Location center = sourceAnchor(portalId);
			if(!FoliaScheduler.runRegion(plugin, center, command, delayTicks(delayMillis)))
			{
				throw new RejectedExecutionException("RTP source region rejected delayed operation for " + portalId);
			}
		}
	}

	private static final class RtpThreadFactory implements ThreadFactory
	{
		private int sequence;

		@Override
		public synchronized Thread newThread(Runnable command)
		{
			Thread thread = new Thread(command, "Wormholes-RTP-Search-" + sequence++);
			thread.setDaemon(true);
			return thread;
		}
	}
}
