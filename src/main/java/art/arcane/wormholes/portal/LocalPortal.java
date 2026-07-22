package art.arcane.wormholes.portal;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.IntConsumer;
import java.util.function.IntSupplier;
import java.util.logging.Level;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.util.Vector;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.title.Title;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;

import art.arcane.wormholes.PortalManager;
import art.arcane.wormholes.Settings;
import art.arcane.wormholes.Wormholes;
import art.arcane.wormholes.geometry.Raycast;
import art.arcane.wormholes.localization.WormholesLocalization;
import art.arcane.wormholes.localization.WormholesMessages;
import art.arcane.wormholes.network.PortalSyncService;
import art.arcane.wormholes.platform.WormholesPlatform;
import art.arcane.wormholes.portal.rtp.RtpAllocationMode;
import art.arcane.wormholes.portal.rtp.RtpPortalEditor;
import art.arcane.wormholes.portal.rtp.RtpPortalEditorModel;
import art.arcane.wormholes.portal.rtp.RtpRotationMode;
import art.arcane.wormholes.portal.rtp.RtpSettings;
import art.arcane.wormholes.service.WormholesAudience;
import art.arcane.wormholes.service.WormholesTelemetry;
import art.arcane.volmlib.util.bukkit.WorldIdentity;
import art.arcane.volmlib.util.localization.LinesKey;
import art.arcane.volmlib.util.localization.MessageArgs;
import art.arcane.volmlib.util.localization.MessageArgument;
import art.arcane.volmlib.util.localization.TextKey;
import art.arcane.volmlib.util.scheduling.AR;
import art.arcane.volmlib.util.scheduling.FoliaScheduler;
import art.arcane.volmlib.util.inventorygui.Element;
import art.arcane.volmlib.util.inventorygui.UIElement;
import art.arcane.volmlib.util.inventorygui.UIPaneDecorator;
import art.arcane.volmlib.util.inventorygui.UIWindow;
import art.arcane.volmlib.util.inventorygui.Window;
import art.arcane.volmlib.util.inventorygui.WindowResolution;
import art.arcane.wormholes.util.Axis;
import art.arcane.wormholes.util.AxisAlignedBB;
import art.arcane.wormholes.util.Direction;
import art.arcane.wormholes.util.F;
import art.arcane.wormholes.util.FinalBoolean;
import art.arcane.volmlib.util.collection.KList;
import art.arcane.wormholes.util.J;
import art.arcane.wormholes.util.JSONObject;
import art.arcane.wormholes.util.M;
import art.arcane.wormholes.util.MSound;
import art.arcane.volmlib.util.data.MaterialBlock;
import art.arcane.wormholes.util.ParticleEffect;
import art.arcane.wormholes.util.PhantomSpinner;
import art.arcane.wormholes.util.RString;
import art.arcane.wormholes.util.VIO;

import net.md_5.bungee.api.ChatColor;

public class LocalPortal extends Portal implements ILocalPortal, IProgressivePortal, IFXPortal, IOwnablePortal, Listener
{
	private static final long REENTRY_LATCH_TTL_MILLIS = 60_000L;
	private static final double REENTRY_EXIT_MARGIN = 2.0D;
	private static final int DEFAULT_NETWORK_VIEW_DEPTH = 64;
	private static final int DEFAULT_NETWORK_VIEW_LATERAL_PAD = 48;
	private static final int DEFAULT_NETWORK_VIEW_HEARTBEAT_TICKS = 60;
	private static final int DEFAULT_NETWORK_VIEW_ENTITY_INTERVAL_TICKS = 10;
	private static final int DEFAULT_NETWORK_VIEW_UNSUBSCRIBE_GRACE_SECONDS = 30;
	private static final String DEFAULT_NETWORK_VIEW_FALLBACK_BLOCK = "minecraft:air";
	private static final ConcurrentHashMap<UUID, Long> TELEPORT_COOLDOWNS = new ConcurrentHashMap<UUID, Long>();
	private static final ConcurrentHashMap<UUID, ReentryLatch> REENTRY_LATCHES = new ConcurrentHashMap<UUID, ReentryLatch>();
	private static final Set<UUID> TELEPORT_IN_FLIGHT = ConcurrentHashMap.newKeySet();

	private PhantomSpinner spinner;
	private PortalStructure structure;
	private volatile PortalType type;
	private UUID owner;
	private volatile ITunnel tunnel;
	private volatile UUID dimensionalCounterpartId;
	private volatile DimensionalPortalKind dimensionalPortalKind;
	private volatile boolean open;
	private volatile boolean ambientAttended = true;
	private boolean progressing;
	private String progress;
	private Player directionChanger;
	private Direction chosenDirection;
	private Vector chosenLook;
	private final AtomicLong dirtyGeneration = new AtomicLong();
	private final AtomicBoolean saveInFlight = new AtomicBoolean();
	private volatile long savedGeneration;
	private ProjectionMode projectionMode;
	private boolean mirrorMode;
	private MirrorRotation mirrorRotation;
	private volatile RtpSettings rtpSettings;
	private final AtomicLong rtpConfigurationRevision = new AtomicLong();
	private AxisAlignedBB view;
	private double viewRange;
	private PortalPermissionMode permissionMode;
	private boolean outgoingTraversalsEnabled;
	private boolean incomingTraversalsEnabled;
	private int networkViewDepth;
	private int networkViewLateralPad;
	private int networkViewHeartbeatTicks;
	private int networkViewEntityIntervalTicks;
	private int networkViewUnsubscribeGraceSeconds;
	private String networkViewFallbackBlock;
	private boolean settingsSyncEnabled;
	private final Map<UUID, UIWindow> openMenus = new ConcurrentHashMap<UUID, UIWindow>();
	private final Map<UUID, RtpEditorSession> rtpEditorSessions = new ConcurrentHashMap<UUID, RtpEditorSession>();
	private final AtomicBoolean destructionStarted = new AtomicBoolean();
	private final AtomicLong effectSequence = new AtomicLong();
	private final Object persistenceLock = new Object();

	public LocalPortal(UUID id, PortalType type, PortalStructure structure)
	{
		super(id, structure.getCenter().toVector());
		this.owner = id;
		spinner = new PhantomSpinner(ChatColor.YELLOW, ChatColor.GOLD, ChatColor.RED);
		this.type = type;
		this.structure = structure;
		open = false;
		progressing = false;
		progress = "Idle";
		tunnel = null;
		dimensionalCounterpartId = null;
		dimensionalPortalKind = DimensionalPortalKind.NONE;
		directionChanger = null;
		chosenDirection = null;
		chosenLook = null;
		setName(F.capitalize(getType().name().toLowerCase()) + " " + id.toString().substring(0, 4));
		savedGeneration = dirtyGeneration.get();
		projectionMode = ProjectionMode.ON;
		mirrorMode = false;
		mirrorRotation = MirrorRotation.DEGREES_0;
		rtpSettings = type == PortalType.RTP ? defaultRtpSettings() : null;
			permissionMode = PortalPermissionMode.BLACKLIST;
			outgoingTraversalsEnabled = true;
			incomingTraversalsEnabled = true;
			networkViewDepth = DEFAULT_NETWORK_VIEW_DEPTH;
			networkViewLateralPad = DEFAULT_NETWORK_VIEW_LATERAL_PAD;
			networkViewHeartbeatTicks = DEFAULT_NETWORK_VIEW_HEARTBEAT_TICKS;
			networkViewEntityIntervalTicks = DEFAULT_NETWORK_VIEW_ENTITY_INTERVAL_TICKS;
			networkViewUnsubscribeGraceSeconds = DEFAULT_NETWORK_VIEW_UNSUBSCRIBE_GRACE_SECONDS;
			networkViewFallbackBlock = DEFAULT_NETWORK_VIEW_FALLBACK_BLOCK;
			settingsSyncEnabled = true;
			view = computeView();
		}

	@Override
	public void saveJSON(JSONObject j)
	{
		super.saveJSON(j);
		j.put("structure", getStructure().toJSON());
		j.put("type", type.name());
		j.put("owner", getOwner().toString());
		j.put("projectionMode", projectionMode.name());
		j.put("mirrorMode", mirrorMode);
		j.put("mirrorRotationDegrees", mirrorRotation.getDegrees());
		if(rtpSettings != null)
		{
			j.put("rtp", rtpSettings.toJson());
		}
			j.put("permissionMode", permissionMode.name());
			j.put("outgoingTraversalsEnabled", outgoingTraversalsEnabled);
			j.put("incomingTraversalsEnabled", incomingTraversalsEnabled);
		if(isGateway())
		{
			j.put("networkViewDepth", networkViewDepth);
			j.put("networkViewLateralPad", networkViewLateralPad);
			j.put("networkViewHeartbeatTicks", networkViewHeartbeatTicks);
			j.put("networkViewEntityIntervalTicks", networkViewEntityIntervalTicks);
			j.put("networkViewUnsubscribeGraceSeconds", networkViewUnsubscribeGraceSeconds);
			j.put("networkViewFallbackBlock", networkViewFallbackBlock);
			j.put("settingsSyncEnabled", settingsSyncEnabled);
		}

		if(tunnel != null)
		{
			j.put("tunnel", getTunnel().toJSON());
		}
		if(dimensionalCounterpartId != null)
		{
			j.put("dimensionalCounterpartId", dimensionalCounterpartId.toString());
		}
		if(dimensionalPortalKind != DimensionalPortalKind.NONE)
		{
			j.put("dimensionalPortalKind", dimensionalPortalKind.name());
		}
	}

	@Override
	public void loadJSON(JSONObject j)
	{
		super.loadJSON(j);
		structure = new PortalStructure();
		structure.loadJSON(j.getJSONObject("structure"));
		if(!hasFrameLoadedFromJson())
		{
			applyFrame(PortalFrame.derive(structure.getArea(), direction));
		}
		type = PortalType.valueOf(j.getString("type"));
		rtpSettings = loadRtpSettings(j);
		owner = UUID.fromString(j.getString("owner"));
		String storedProjectionMode = j.optString("projectionMode", ProjectionMode.ON.name());
		projectionMode = resolveProjectionMode(j);
		mirrorMode = resolveMirrorMode(j);
		MirrorRotation storedMirrorRotation = resolveMirrorRotation(j);
		mirrorRotation = storedMirrorRotation.coherentFor(getFrame());
			permissionMode = resolvePermissionMode(j);
			outgoingTraversalsEnabled = !j.has("outgoingTraversalsEnabled") || j.getBoolean("outgoingTraversalsEnabled");
			incomingTraversalsEnabled = !j.has("incomingTraversalsEnabled") || j.getBoolean("incomingTraversalsEnabled");
			networkViewDepth = readNetworkViewInt(j, "networkViewDepth", DEFAULT_NETWORK_VIEW_DEPTH, 1, 128);
			networkViewLateralPad = readNetworkViewInt(j, "networkViewLateralPad", DEFAULT_NETWORK_VIEW_LATERAL_PAD, 0, 128);
			networkViewHeartbeatTicks = readNetworkViewInt(j, "networkViewHeartbeatTicks", DEFAULT_NETWORK_VIEW_HEARTBEAT_TICKS, 2, 600);
			networkViewEntityIntervalTicks = readNetworkViewInt(j, "networkViewEntityIntervalTicks", DEFAULT_NETWORK_VIEW_ENTITY_INTERVAL_TICKS, 2, 600);
			networkViewUnsubscribeGraceSeconds = readNetworkViewInt(j, "networkViewUnsubscribeGraceSeconds", DEFAULT_NETWORK_VIEW_UNSUBSCRIBE_GRACE_SECONDS, 5, 600);
			networkViewFallbackBlock = normalizeNetworkViewFallbackBlock(j.optString("networkViewFallbackBlock", DEFAULT_NETWORK_VIEW_FALLBACK_BLOCK));
			settingsSyncEnabled = !j.has("settingsSyncEnabled") || j.getBoolean("settingsSyncEnabled");
			view = computeView();

		if(j.has("tunnel"))
		{
			tunnel = ITunnel.createTunnel(j.getJSONObject("tunnel"));
		}
		dimensionalCounterpartId = resolveOptionalUuid(j.optString("dimensionalCounterpartId", ""));
		dimensionalPortalKind = DimensionalPortalKind.fromName(j.optString("dimensionalPortalKind", ""));
		boolean dimensionalStateNormalized = normalizeDimensionalState();
		boolean rtpStateNormalized = normalizeRtpState();
		boolean rtpPersistenceNormalized = requiresRtpPersistenceNormalization(j);
		boolean projectionStateNormalized = !j.has("mirrorMode") || !storedProjectionMode.equals(projectionMode.name());
		if(storedMirrorRotation != mirrorRotation || dimensionalStateNormalized || rtpStateNormalized || rtpPersistenceNormalized || projectionStateNormalized)
		{
			save();
		}
	}

	@Override
	public JSONObject toJSON()
	{
		JSONObject o = new JSONObject();
		saveJSON(o);

		return o;
	}

	@Override
	public PortalStructure getStructure()
	{
		return structure;
	}

	@Override
	public PortalType getType()
	{
		return type;
	}

	public RtpSettings getRtpSettings()
	{
		return rtpSettings;
	}

	public void setRtpSettings(RtpSettings settings)
	{
		RtpSettings requiredSettings = Objects.requireNonNull(settings, "settings");
		World sourceWorld = structure.getWorld();
		if(sourceWorld == null || !WorldIdentity.serialize(sourceWorld).equals(requiredSettings.getSourceWorldKey()))
		{
			throw new IllegalArgumentException("RTP settings source world must match the portal source world");
		}
		Location center = structure.getCenter();
		World world = center == null ? null : center.getWorld();
		if(Wormholes.instance != null && world != null
				&& !FoliaScheduler.isOwnedByCurrentRegion(world, center.getBlockX() >> 4, center.getBlockZ() >> 4))
		{
			boolean scheduled = FoliaScheduler.runRegion(Wormholes.instance, center, () -> applyRtpSettings(requiredSettings));
			if(!scheduled)
			{
				throw new IllegalStateException("RTP settings update could not be routed to the source portal region");
			}
			return;
		}
		applyRtpSettings(requiredSettings);
	}

	@Override
	public void setType(PortalType type)
	{
		PortalType requiredType = Objects.requireNonNull(type, "type");
		if(dimensionalPortalKind.isManagedPortal())
		{
			return;
		}
		if(this.type == requiredType)
		{
			return;
		}

		boolean wasGateway = isGateway();
		boolean rtpTransition = this.type == PortalType.RTP || requiredType == PortalType.RTP;
		if(requiredType == PortalType.RTP)
		{
			open = false;
			mirrorMode = false;
			detachDimensionalPairIdentity();
			tunnel = null;
			if(rtpSettings == null)
			{
				rtpSettings = defaultRtpSettings();
			}
		}
		else if(this.type == PortalType.RTP)
		{
			open = false;
			mirrorMode = false;
			tunnel = null;
		}
		this.type = requiredType;

		if(wasGateway != isGateway())
		{
			detachDimensionalPairIdentity();
			tunnel = null;
		}
		if(rtpTransition)
		{
			advanceRtpConfigurationRevision();
			invalidateProjection();
		}

		save();
		syncGatewayTickets();
		if(Wormholes.rtpRuntime != null)
		{
			Wormholes.rtpRuntime.synchronize(this);
		}
	}

	@Override
	public void update()
	{
		if(type == PortalType.RTP)
		{
			updateRtp();
			return;
		}
		ITunnel activeTunnel = tunnel;
		IPortal destination = activeTunnel == null ? null : activeTunnel.getDestination();
		boolean tunnelPresent = activeTunnel != null && destination != null;
		boolean tunnelValid = tunnelPresent && activeTunnel.isValid();
		if(hasRtpDestination(activeTunnel))
		{
			tunnel = null;
			activeTunnel = null;
			tunnelPresent = false;
			save();
		}
		boolean shouldBeOpen = tunnelValid || (mirrorMode && projectionMode == ProjectionMode.ON);

		if(isOpen())
		{
			if(isAmbientAttended())
			{
				playEffect(PortalEffect.AMBIENT_OPEN);
			}

			updateCaptures(activeTunnel, tunnelPresent);

			if(!shouldBeOpen)
			{
				if(tunnelPresent && activeTunnel.getTunnelType() != TunnelType.UNIVERSAL)
				{
					tunnel = null;
				}

				close();
			}
		}

		else
		{
			if(isAmbientAttended())
			{
				playEffect(PortalEffect.AMBIENT_CLOSED);
			}

			if(shouldBeOpen)
			{
				open();
			}
		}

		if(Settings.DEBUG_RENDERING)
		{
			playEffect(PortalEffect.AMBIENT_DEBUG);
		}
	}

	private void updateRtp()
	{
		if(Wormholes.rtpRuntime == null)
		{
			close();
			return;
		}
		Wormholes.rtpRuntime.synchronize(this);
		Wormholes.rtpRuntime.tick(getId());
		boolean shouldBeOpen = Wormholes.rtpRuntime.isReady(getId());
		if(isOpen())
		{
			if(isAmbientAttended())
			{
				playEffect(PortalEffect.AMBIENT_OPEN);
			}
			if(shouldBeOpen)
			{
				updateCaptures(null, false);
			}
			else
			{
				close();
			}
		}
		else
		{
			if(isAmbientAttended())
			{
				playEffect(PortalEffect.AMBIENT_CLOSED);
			}
			if(shouldBeOpen)
			{
				open();
			}
		}
		if(Settings.DEBUG_RENDERING)
		{
			playEffect(PortalEffect.AMBIENT_DEBUG);
		}
	}

	private static boolean hasRtpDestination(ITunnel activeTunnel)
	{
		return (activeTunnel instanceof LocalTunnel localTunnel && localTunnel.hasRtpDestination())
				|| (activeTunnel instanceof DimensionalTunnel dimensionalTunnel && dimensionalTunnel.hasRtpDestination());
	}

	private void updateCaptures(ITunnel activeTunnel, boolean tunnelPresent)
	{
		boolean rtp = type == PortalType.RTP;
		if(!isOpen() || !tunnelPresent && !rtp)
		{
			return;
		}

		if(mirrorMode)
		{
			return;
		}

		long now = System.currentTimeMillis();
		pruneTeleportCooldowns(now);
		for(Entity i : getStructure().getCaptureZone().getEntities(getStructure().getWorld()))
		{
			UUID entityId = i.getUniqueId();
			if(!rtp && i instanceof Player viewer)
			{
				ArrivalWarmer warmer = Wormholes.arrivalWarmer;
				if(warmer != null)
				{
					warmer.warmImminent(this, viewer);
				}
			}
			ReentryLatch latch = activeReentryLatch(entityId, now);
			if(latch != null && getId().equals(latch.portalId()))
			{
				if(isOccupyingPortal(i))
				{
					if(!latch.armed)
					{
						latch.armed = true;
						Wormholes.v("[latch] " + i.getName() + " inside portal " + getId() + " - reentry latch ARMED (no teleport until they fully leave)");
					}
				}
				else if(latch.armed)
				{
					clearReentryLatch(entityId);
					Wormholes.v("[latch] " + i.getName() + " left portal " + getId() + " - reentry latch CLEARED (eligible again)");
				}
				continue;
			}

			Traversive traversive = rayTeleport(i);
			if(traversive == null)
			{
				continue;
			}

			if(rtp)
			{
				if(Wormholes.rtpRuntime != null)
				{
					Wormholes.rtpRuntime.traverse(this, i, traversive);
				}
				continue;
			}

			if(!canUseTunnel(i, activeTunnel))
			{
				rejectTraversal(i, traversive);
				continue;
			}

			if(isTeleportCoolingDown(entityId, now))
			{
				rejectCooldownTraversal(i, traversive);
				continue;
			}

			markTeleportCooldown(entityId, now);
			Wormholes.v("[cross] " + i.getName() + " crossing portal " + getId() + " -> " + (activeTunnel instanceof UniversalTunnel ? "CROSS-SERVER handoff" : "local teleport"));
			if(!(activeTunnel instanceof UniversalTunnel && i instanceof Player))
			{
				playEffect(PortalEffect.PUSH, traversive.getInPoint().toLocation(getStructure().getWorld()));
			}
			pushTraversive(traversive, activeTunnel);
		}
	}

	private boolean isOccupyingPortal(Entity entity)
	{
		PortalStructure portalStructure = getStructure();
		if(portalStructure == null || portalStructure.getArea() == null)
		{
			return false;
		}
		Location location = entity.getLocation();
		if(location.getWorld() == null || portalStructure.getWorld() == null || !portalStructure.getWorld().equals(location.getWorld()))
		{
			return false;
		}
		AxisAlignedBB area = portalStructure.getArea();
		return location.getX() >= area.getXa() - REENTRY_EXIT_MARGIN && location.getX() <= area.getXb() + REENTRY_EXIT_MARGIN
			&& location.getY() >= area.getYa() - REENTRY_EXIT_MARGIN && location.getY() <= area.getYb() + REENTRY_EXIT_MARGIN
			&& location.getZ() >= area.getZa() - REENTRY_EXIT_MARGIN && location.getZ() <= area.getZb() + REENTRY_EXIT_MARGIN;
	}

	private Traversive rayTeleport(Entity i)
	{
		Vector velocity = Wormholes.traversableManager.getVelocity(i);
		Location end = i.getLocation();
		Location start = end.clone().subtract(velocity);
		Vector crossingVelocity = velocity.lengthSquared() > 1.0E-4D ? velocity : end.getDirection().clone().multiply(0.2D);
		Traversive[] f = new Traversive[1];

		new Raycast(start, end, 0.09)
		{
			@Override
			public boolean shouldContinue(Location l)
			{
				if(getStructure().contains(l))
				{
					f[0] = buildCrossing(i, start, l.toVector(), crossingVelocity);
					return false;
				}

				return true;
			}
		};

		if(f[0] == null && getStructure().contains(end))
		{
			f[0] = buildCrossing(i, start, end.toVector(), crossingVelocity);
		}

		return f[0];
	}

	private Traversive buildCrossing(Entity i, Location start, Vector inPoint, Vector velocity)
	{
		double relX = start.getX() - getOrigin().getX();
		double relY = start.getY() - getOrigin().getY();
		double relZ = start.getZ() - getOrigin().getZ();
		boolean frontSide = ((relX * getFrame().getNormal().x()) + (relY * getFrame().getNormal().y()) + (relZ * getFrame().getNormal().z())) >= 0.0D;
		return new Traversive(i, getFrame().view(frontSide), getOrigin(), inPoint, velocity, start.getDirection(), frontSide);
	}

	private boolean canUseTunnel(Entity entity, ITunnel activeTunnel)
	{
		if(!canDepart(entity))
		{
			return false;
		}
		IPortal destination = activeTunnel == null ? null : activeTunnel.getDestination();
		if(destination == null)
		{
			return false;
		}
		if(destination instanceof ILocalPortal localDestination)
		{
			return localDestination.canArrive(entity);
		}
		if(destination instanceof RemotePortal remoteDestination)
		{
			return remoteDestination.acceptsInboundTraversal(entity);
		}
		return true;
	}

	private void pushTraversive(Traversive traversive, ITunnel activeTunnel)
	{
		if(activeTunnel instanceof UniversalTunnel universal && Wormholes.traversalService != null && traversive.getObject() instanceof Entity entity)
		{
			if(entity instanceof Player player)
			{
				Wormholes.traversalService.beginPlayerHandoff(player, universal, traversive, this);
				return;
			}
			Wormholes.traversalService.beginEntityTransfer(entity, universal, traversive, this);
			return;
		}

		activeTunnel.push(traversive);
	}

	private void rejectTraversal(Entity entity, Traversive traversive)
	{
		bounceRejectedTraversal(entity, traversive);
		notifyPortalDenied(entity);
	}

	private void rejectCooldownTraversal(Entity entity, Traversive traversive)
	{
		bounceRejectedTraversal(entity, traversive);
		if(entity instanceof Player player)
		{
			WormholesAudience.sendActionBar(player, Wormholes.text().component(WormholesMessages.PORTAL_COOLDOWN));
		}
	}

	private void bounceRejectedTraversal(Entity entity, Traversive traversive)
	{
		armRejectedReentry(entity);
		markTeleportCooldown(entity.getUniqueId(), System.currentTimeMillis());
		entity.setVelocity(sourceRejectionVelocity(traversive));
		PortalStructure portalStructure = getStructure();
		World world = portalStructure == null || portalStructure.getWorld() == null ? entity.getWorld() : portalStructure.getWorld();
		playEffect(PortalEffect.REJECT, traversive.getInPoint().toLocation(world));
	}

	private boolean allowsPortalPermission(Entity entity)
	{
		if(!(entity instanceof Player player))
		{
			return true;
		}
		if(player.isOp())
		{
			return true;
		}
		return permissionMode.allows(player, getPermissionNode());
	}

	private void notifyPortalDenied(Entity entity)
	{
		if(entity instanceof Player player)
		{
			WormholesAudience.sendActionBar(player, Wormholes.text().component(WormholesMessages.PORTAL_ACCESS_DENIED));
		}
	}

	@Override
	public void close()
	{
		setOpen(false);
	}

	@Override
	public boolean isOpen()
	{
		return open;
	}

	@Override
	public void open()
	{
		setOpen(true);
	}

	@Override
	public boolean isAmbientAttended()
	{
		return ambientAttended;
	}

	@Override
	public void setAmbientAttended(boolean attended)
	{
		ambientAttended = attended;
	}

	@Override
	public void setOpen(boolean open)
	{
		boolean changed = this.open != open;
		this.open = open;
		if(changed && Wormholes.instance != null && Wormholes.effectManager != null)
		{
			if(open)
			{
				playEffect(PortalEffect.OPEN);
			}
			else
			{
				playEffect(PortalEffect.CLOSE);
			}
		}

		if(changed && isGateway() && Wormholes.portalSyncService != null)
		{
			Wormholes.portalSyncService.broadcastPortal(this);
		}
		if(changed)
		{
			Wormholes.v("QA_EVT {\"event\":\"portal_state\",\"status\":\"info\",\"details\":\"" + (open ? "open" : "closed")
					+ "\",\"context\":{\"portal\":\"" + getId() + "\",\"gateway\":" + isGateway() + "}}");
		}
	}

	public void phase(Axis a, ParticleEffect e, Location l, float scale)
	{
		KList<Vector> vxz = new KList<Vector>();

		for(Direction i : Direction.values())
		{
			if(i.getAxis().equals(a))
			{
				continue;
			}

			vxz.add(i.toVector());
		}

		int k = 1;

		if(M.r(0.7))
		{
			k++;

			if(M.r(0.4))
			{
				k++;

				if(M.r(0.2))
				{
					k++;
				}
			}
		}

		for(int i = 0; i < 64; i++)
		{
			Vector vx = new Vector(0, 0, 0);

			for(int j = 0; j < 18; j++)
			{
				vx.add(vxz.getRandom());
			}

			e.display(vx.clone().normalize(), 0.5f * scale, l, 32);

			if(k > 1)
			{
				e.display(vx.clone().normalize(), 1f * scale, l, 32);

				if(k > 2)
				{
					e.display(vx.clone().normalize(), 1.5f * scale, l, 32);

					if(k > 3)
					{
						e.display(vx.clone().normalize(), 2.0f * scale, l, 32);
					}
				}
			}
		}
	}

	@Override
	public void playEffect(PortalEffect effect, Location location)
	{
		switch(effect)
		{
			case PUSH:
				ParticleEffect.SMOKE.display(0.01f, 6, location, 32);
				if(isPortalSoundEnabled())
				{
					location.getWorld().playSound(location, MSound.ENDERMAN_TELEPORT.bukkitSound(), 0.5f, 1.7f + (float) (Math.random() * 0.2));
					location.getWorld().playSound(location, MSound.ENDERMAN_TELEPORT.bukkitSound(), 0.5f, 1.5f + (float) (Math.random() * 0.2));
					location.getWorld().playSound(location, MSound.ENDERMAN_TELEPORT.bukkitSound(), 0.5f, 1.3f + (float) (Math.random() * 0.2));
				}

				break;
			case REJECT:
				ParticleEffect.SMOKE.display(0.08f, 24, location, 32);
				ParticleEffect.REDSTONE.display(new ParticleEffect.OrdinaryColor(255, 70, 70), location, 32);
				if(isPortalSoundEnabled())
				{
					location.getWorld().playSound(location, MSound.ANVIL_LAND.bukkitSound(), 0.7f, 1.8f);
					location.getWorld().playSound(location, MSound.GLASS.bukkitSound(), 0.6f, 0.7f);
				}
				break;
			case AMBIENT_CLOSED:
				for(int i = 0; i < 1; i++)
				{
					ParticleEffect.TOWN_AURA.display(0f, 1, getStructure().randomLocation(), 16);
				}

				break;
			case AMBIENT_OPEN:
				for(int i = 0; i < 4; i++)
				{
					ParticleEffect.TOWN_AURA.display(0f, 1, getStructure().randomLocation(), 16);
				}

				if(isPortalSoundEnabled() && M.r(0.01))
				{
					getStructure().getCenter().getWorld().playSound(getStructure().getCenter(), Sound.BLOCK_LAVA_AMBIENT, 0.25f, 0.025f);
				}

				if(isPortalSoundEnabled() && M.r(0.01))
				{
					getStructure().getCenter().getWorld().playSound(getStructure().getCenter(), MSound.PORTAL.bukkitSound(), 0.25f, 0.025f);
				}

				break;
			case CLOSE:
				long closeSequence = effectSequence.incrementAndGet();
				AxisAlignedBB closeArea = getStructure().getArea();
				World closeWorld = getStructure().getWorld();
				if(closeArea != null && closeWorld != null)
				{
					Location corner = new Location(closeWorld, Math.min(closeArea.getXa(), closeArea.getXb()), Math.min(closeArea.getYa(), closeArea.getYb()), Math.min(closeArea.getZa(), closeArea.getZb()));
					double sx = Math.abs(closeArea.getXb() - closeArea.getXa());
					double sy = Math.abs(closeArea.getYb() - closeArea.getYa());
					double sz = Math.abs(closeArea.getZb() - closeArea.getZa());
					FoliaScheduler.runRegion(Wormholes.instance, corner,
							() -> Wormholes.effectManager.playPortalClose(
									closeWorld,
									corner,
									sx,
									sy,
									sz,
									() -> effectSequence.get() == closeSequence,
									this::isPortalSoundEnabled), 1L);
				}
				break;
			case OPEN:
				long openSequence = effectSequence.incrementAndGet();
				AxisAlignedBB openArea = getStructure().getArea();
				Location openCenter = getStructure().getCenter();
				World openWorld = getStructure().getWorld();
				if(openArea != null && openCenter != null && openWorld != null)
				{
					double sx = Math.abs(openArea.getXb() - openArea.getXa());
					double sy = Math.abs(openArea.getYb() - openArea.getYa());
					double sz = Math.abs(openArea.getZb() - openArea.getZa());
					FoliaScheduler.runRegion(Wormholes.instance, openCenter,
							() -> Wormholes.effectManager.playPortalOpen(
									openWorld,
									openCenter,
									sx,
									sy,
									sz,
									() -> effectSequence.get() == openSequence,
									this::isPortalSoundEnabled), 1L);
				}
				break;
			case AMBIENT_DEBUG:

				break;
			default:
				break;
		}
	}

	private boolean isPortalSoundEnabled()
	{
		RtpSettings settings = rtpSettings;
		return type != PortalType.RTP || settings == null || settings.isSoundEnabled();
	}

	@Override
	public void playEffect(PortalEffect effect)
	{
		playEffect(effect, null);
	}

	@Override
	public void showProgress(String text)
	{
		progressing = true;
		progress = text;
	}

	@Override
	public void hideProgress()
	{
		progressing = false;
	}

	@Override
	public boolean isShowingProgress()
	{
		return progressing;
	}

	@Override
	public String getCurrentProgress()
	{
		return progress;
	}

	@Override
	public void onLooking(Player p, boolean holdingWand)
	{
		if(holdingWand)
		{
			if(isShowingProgress())
			{
				sendShortTitle(p, spinner.toString() + ChatColor.RESET + ChatColor.GRAY + progress);
			}

			else
			{
				sendShortTitle(p, getRouter(false));
			}
		}
	}

	@Override
	public void onWanded(Player p)
	{
		uiOpenPortalMenu(p);
	}

	@Override
	public boolean isLookingAt(Player p)
	{
		if(directionChanger != null && p.equals(directionChanger))
		{
			return false;
		}

		if(p.getWorld().equals(getStructure().getWorld()))
		{
			if(p.getLocation().distanceSquared(getStructure().getCenter()) < 64)
			{
				FinalBoolean hit = new FinalBoolean(false);

				new Raycast(p.getEyeLocation(), p.getEyeLocation().clone().add(p.getLocation().getDirection().clone().multiply(16)), 0.9)
				{
					@Override
					public boolean shouldContinue(Location l)
					{
						if(getStructure().contains(l))
						{
							hit.set(true);
							return false;
						}

						return true;
					}
				};

				return hit.get();
			}
		}

		return false;
	}

	@Override
	public void setDirection(Direction d)
	{
		applyFrame(getFrame().withNormal(d));
		boolean mirrorRotationChanged = normalizeMirrorRotationForFrame();
		invalidateProjection();
		save();
		if(mirrorRotationChanged)
		{
			broadcastSettingsIfEnabled();
			refreshOpenMenusUnlessApplyingRemote();
		}
		syncGatewayTickets();
	}

	@Override
	public void setFrame(PortalFrame frame)
	{
		applyFrame(frame);
		boolean mirrorRotationChanged = normalizeMirrorRotationForFrame();
		invalidateProjection();
		save();
		if(mirrorRotationChanged)
		{
			broadcastSettingsIfEnabled();
			refreshOpenMenusUnlessApplyingRemote();
		}
		syncGatewayTickets();
	}

	@Override
	public void receive(Traversive t)
	{
		if(t.getType().equals(TraversableType.PLAYER) || t.getType().equals(TraversableType.ENTITY))
		{
			Entity p = (Entity) t.getObject();
			if(!canArrive(p))
			{
				rejectTraversal(p, t);
				return;
			}
			Vector outVelocity = t.getOutVelocity(getFrame());
			Vector outLook = t.getOutLook(getFrame());
			Direction dx = Direction.closest(outVelocity);
			Location exit = t.getOutPoint(getFrame(), getOrigin()).toLocation(getStructure().getWorld());

			Location target = exit.clone().add(dx.toVector().normalize().multiply(1.25));
			target.setDirection(outLook);

			boolean reloadExpected = target.getWorld() != null && !target.getWorld().equals(p.getWorld());

			UUID entityId = p.getUniqueId();
			if(!TELEPORT_IN_FLIGHT.add(entityId))
			{
				return;
			}

			ArrivalWarmer warmer = Wormholes.arrivalWarmer;
			if(warmer != null && target.getWorld() != null)
			{
				int warmRadius = p instanceof Player ? warmer.viewRadius((Player) p) : Settings.ARRIVAL_WARM_RADIUS_CHUNKS;
				warmer.warmAround(target.getWorld(), target.getBlockX(), target.getBlockZ(), warmRadius, Settings.ARRIVAL_WARM_HOLD_MILLIS);
			}

			WormholesPlatform.teleport(Wormholes.instance, p, target, PlayerTeleportEvent.TeleportCause.PLUGIN).whenComplete((success, error) ->
			{
				TELEPORT_IN_FLIGHT.remove(entityId);
				if(error != null || !Boolean.TRUE.equals(success))
				{
					return;
				}
				FoliaScheduler.runEntity(Wormholes.instance, p, () ->
				{
					p.setVelocity(outVelocity);
					WormholesTelemetry.countTraversal();
					markTeleportCooldown(entityId, System.currentTimeMillis());
					latchReentry(entityId, getId());
					playEffect(PortalEffect.PUSH, exit);
					if(p instanceof Player)
					{
						ArrivalTransition.apply((Player) p, reloadExpected);
						if(Wormholes.projectionManager != null)
						{
							Wormholes.projectionManager.reprimeArrival((Player) p);
						}
					}
				});
			});
		}
	}

	@Override
	public Location computeExitTarget(Traversive t)
	{
		Vector outVelocity = t.getOutVelocity(getFrame());
		Vector outLook = t.getOutLook(getFrame());
		Direction dx = Direction.closest(outVelocity);
		Location exit = t.getOutPoint(getFrame(), getOrigin()).toLocation(getStructure().getWorld());
		Location target = exit.clone().add(dx.toVector().normalize().multiply(1.25));
		target.setDirection(outLook);
		return target;
	}

	@Override
	public void completeRemoteArrival(Entity entity, Traversive t)
	{
		if(!canArrive(entity))
		{
			rejectRemoteArrival(entity, t);
			return;
		}
		Vector outVelocity = t.getOutVelocity(getFrame());
		entity.setVelocity(outVelocity);
		markTeleportCooldown(entity.getUniqueId(), System.currentTimeMillis());
		latchReentry(entity.getUniqueId(), getId());
		WormholesTelemetry.countTraversal();
		Wormholes.v("[arrival] completeRemoteArrival " + entity.getName() + " settled near portal " + getId() + ", latched + cooldown set");
		playEffect(PortalEffect.PUSH, entity.getLocation());
		if(entity instanceof Player && Wormholes.projectionManager != null)
		{
			Wormholes.projectionManager.reprimeArrival((Player) entity);
		}
	}

	@Override
	public boolean canCompleteDeparture(Entity entity, Traversive traversive)
	{
		if(entity == null || traversive == null || !entity.isValid() || !isOpen() || !canDepart(entity))
		{
			return false;
		}
		PortalStructure portalStructure = getStructure();
		Location location = entity.getLocation();
		if(portalStructure == null || portalStructure.getWorld() == null || location.getWorld() == null
			|| !portalStructure.getWorld().equals(location.getWorld()))
		{
			return false;
		}
		return remainsCommittedToDeparture(traversive, location.toVector());
	}

	@Override
	public void confirmDeparture(Entity entity, Traversive t)
	{
		if(entity instanceof Player player && isPortalSoundEnabled())
		{
			player.playSound(player.getLocation(), MSound.ENDERMAN_TELEPORT.bukkitSound(), 0.5F, 1.5F);
		}
		PortalStructure portalStructure = getStructure();
		World world = portalStructure == null ? null : portalStructure.getWorld();
		if(world == null)
		{
			return;
		}
		Location location = t.getInPoint().toLocation(world);
		if(!FoliaScheduler.runRegion(Wormholes.instance, location, () -> playEffect(PortalEffect.PUSH, location)))
		{
			Wormholes.w("Portal region rejected departure effect for " + getId());
		}
	}

	@Override
	public void rejectDeparture(Entity entity, Traversive t)
	{
		World world = getStructure() == null ? null : getStructure().getWorld();
		if(world == null)
		{
			bounceRejectedTraversal(entity, t);
			return;
		}
		armRejectedReentry(entity);
		markTeleportCooldown(entity.getUniqueId(), System.currentTimeMillis());
		Location current = entity.getLocation();
		Location target = sourceRejectionPoint(t).toLocation(world);
		target.setYaw(current.getYaw());
		target.setPitch(current.getPitch());
		playRejectedDepartureEffect(t, world);
		WormholesPlatform.teleport(Wormholes.instance, entity, target, PlayerTeleportEvent.TeleportCause.PLUGIN).whenComplete((success, error) ->
		{
			if(error != null || !Boolean.TRUE.equals(success))
			{
				if(error == null)
				{
					Wormholes.w("Failed to return " + entity.getName() + " to the source side of portal " + getId() + ": teleport was rejected");
				}
				else
				{
					Wormholes.instance.getLogger().log(Level.WARNING, "Failed to return " + entity.getName() + " to the source side of portal " + getId(), error);
				}
			}
			if(!FoliaScheduler.runEntity(Wormholes.instance, entity, () -> finishRejectedDeparture(entity, t)))
			{
				Wormholes.w("Entity scheduler rejected departure bounce for " + entity.getName() + " at portal " + getId());
			}
		});
	}

	private void finishRejectedDeparture(Entity entity, Traversive traversive)
	{
		if(!entity.isValid())
		{
			return;
		}
		entity.setVelocity(sourceRejectionVelocity(traversive));
	}

	private void playRejectedDepartureEffect(Traversive traversive, World world)
	{
		Location location = traversive.getInPoint().toLocation(world);
		if(!FoliaScheduler.runRegion(Wormholes.instance, location, () -> playEffect(PortalEffect.REJECT, location)))
		{
			Wormholes.w("Portal region rejected departure bounce effect for " + getId());
		}
	}

	private void armRejectedReentry(Entity entity)
	{
		PortalStructure portalStructure = getStructure();
		if(portalStructure != null && portalStructure.getWorld() != null && portalStructure.getWorld().equals(entity.getWorld()))
		{
			latchRejectedReentry(entity.getUniqueId(), getId());
		}
	}

	static Vector sourceRejectionPoint(Traversive traversive)
	{
		return traversive.getInPoint().clone().add(traversive.getInFrame().getNormal().toVector().normalize().multiply(1.25D));
	}

	static double sourceSideDistance(Traversive traversive, Vector point)
	{
		Vector normal = traversive.getInFrame().getNormal().toVector().normalize();
		return point.clone().subtract(traversive.getInPoint()).dot(normal);
	}

	static boolean remainsCommittedToDeparture(Traversive traversive, Vector point)
	{
		return sourceSideDistance(traversive, point) <= REENTRY_EXIT_MARGIN;
	}

	static Vector sourceRejectionVelocity(Traversive traversive)
	{
		return traversive.getInFrame().getNormal().toVector().normalize().multiply(3.0D);
	}

	@Override
	public void rejectRemoteArrival(Entity entity, Traversive t)
	{
		Location target = computeExitTarget(t);
		if(entity instanceof Player player)
		{
			WormholesPlatform.teleport(Wormholes.instance, player, target, PlayerTeleportEvent.TeleportCause.PLUGIN).thenAccept(success ->
			{
				if(success)
				{
					FoliaScheduler.runEntity(Wormholes.instance, player, () -> finishRejectedRemoteArrival(entity, t, target));
				}
			});
			return;
		}
		entity.teleport(target, PlayerTeleportEvent.TeleportCause.PLUGIN);
		finishRejectedRemoteArrival(entity, t, target);
	}

	private void finishRejectedRemoteArrival(Entity entity, Traversive t, Location target)
	{
		Vector outVelocity = t.getOutVelocity(getFrame());
		if(outVelocity.lengthSquared() < 0.01D)
		{
			outVelocity = getFrame().getNormal().toVector().normalize();
		}
		entity.setVelocity(outVelocity.multiply(2.0D));
		markTeleportCooldown(entity.getUniqueId(), System.currentTimeMillis());
		playEffect(PortalEffect.REJECT, target);
		notifyPortalDenied(entity);
	}

	public boolean beginRtpTraversal(Entity entity, long nowMillis)
	{
		Objects.requireNonNull(entity, "entity");
		if(type != PortalType.RTP || !isOpen() || !canDepart(entity) || entity.getVehicle() != null || !entity.getPassengers().isEmpty())
		{
			return false;
		}
		ReentryLatch latch = activeReentryLatch(entity.getUniqueId(), nowMillis);
		if(latch != null && getId().equals(latch.portalId()))
		{
			return false;
		}
		if(isTeleportCoolingDown(entity.getUniqueId(), nowMillis))
		{
			return false;
		}
		return TELEPORT_IN_FLIGHT.add(entity.getUniqueId());
	}

	public boolean canContinueRtpTraversal(Entity entity)
	{
		Objects.requireNonNull(entity, "entity");
		return type == PortalType.RTP
				&& entity.isValid()
				&& canDepart(entity)
				&& entity.getVehicle() == null
				&& entity.getPassengers().isEmpty()
				&& TELEPORT_IN_FLIGHT.contains(entity.getUniqueId());
	}

	public void cancelRtpTraversal(Entity entity)
	{
		if(entity != null)
		{
			TELEPORT_IN_FLIGHT.remove(entity.getUniqueId());
		}
	}

	public void completeRtpTraversal(Entity entity, Traversive traversive, PortalFrame targetFrame, Location target)
	{
		Entity requiredEntity = Objects.requireNonNull(entity, "entity");
		Traversive requiredTraversive = Objects.requireNonNull(traversive, "traversive");
		PortalFrame requiredTargetFrame = Objects.requireNonNull(targetFrame, "targetFrame");
		Location requiredTarget = Objects.requireNonNull(target, "target");
		if(!TELEPORT_IN_FLIGHT.remove(requiredEntity.getUniqueId()))
		{
			return;
		}
		requiredEntity.setVelocity(requiredTraversive.getOutVelocity(requiredTargetFrame));
		Wormholes.v("QA_EVT {\"event\":\"rtp_traversal_complete\",\"status\":\"pass\",\"details\":\"arrival_confirmed\",\"context\":{\"portal\":\""
				+ getId() + "\",\"frontSide\":" + requiredTraversive.isFrontSide() + ",\"targetNormal\":\""
				+ requiredTargetFrame.getNormal().name() + "\"}}");
		markTeleportCooldown(requiredEntity.getUniqueId(), System.currentTimeMillis());
		latchReentry(requiredEntity.getUniqueId(), getId());
		WormholesTelemetry.countTraversal();
		if(Wormholes.instance != null)
		{
			World sourceWorld = getStructure() == null ? null : getStructure().getWorld();
			if(sourceWorld != null)
			{
				playEffect(PortalEffect.PUSH, requiredTraversive.getInPoint().toLocation(sourceWorld));
			}
			if(requiredTarget.getWorld() != null)
			{
				playEffect(PortalEffect.PUSH, requiredTarget);
			}
			if(requiredEntity instanceof Player player)
			{
				World source = getStructure() == null ? null : getStructure().getWorld();
				ArrivalTransition.apply(player, source != null && requiredTarget.getWorld() != null && !source.equals(requiredTarget.getWorld()));
				if(Wormholes.projectionManager != null)
				{
					Wormholes.projectionManager.reprimeArrival(player);
				}
			}
		}
	}

	@Override
	public boolean canDepart(Entity entity)
	{
		return !mirrorMode && outgoingTraversalsEnabled && allowsPortalPermission(entity);
	}

	@Override
	public boolean canArrive(Entity entity)
	{
		return !mirrorMode && incomingTraversalsEnabled && allowsPortalPermission(entity);
	}

	static boolean isTeleportCoolingDown(UUID entityId, long now)
	{
		Long until = TELEPORT_COOLDOWNS.get(entityId);
		if(until == null)
		{
			return false;
		}
		if(until.longValue() <= now)
		{
			TELEPORT_COOLDOWNS.remove(entityId, until);
			return false;
		}
		return true;
	}

	static void markTeleportCooldown(UUID entityId, long now)
	{
		long cooldown = Settings.TELEPORT_COOLDOWN_MILLIS;
		if(cooldown <= 0L)
		{
			TELEPORT_COOLDOWNS.remove(entityId);
			return;
		}
		TELEPORT_COOLDOWNS.put(entityId, Long.valueOf(now + cooldown));
	}

	public static void latchReentry(UUID entityId, UUID portalId)
	{
		REENTRY_LATCHES.put(entityId, ReentryLatch.waiting(portalId, System.currentTimeMillis()));
	}

	private static void latchRejectedReentry(UUID entityId, UUID portalId)
	{
		REENTRY_LATCHES.put(entityId, ReentryLatch.armed(portalId, System.currentTimeMillis()));
	}

	public static boolean isReentryLatched(UUID entityId)
	{
		return REENTRY_LATCHES.containsKey(entityId);
	}

	public static void clearReentryLatch(UUID entityId)
	{
		REENTRY_LATCHES.remove(entityId);
	}

	private static ReentryLatch activeReentryLatch(UUID entityId, long now)
	{
		ReentryLatch latch = REENTRY_LATCHES.get(entityId);
		if(latch == null)
		{
			return null;
		}
		if(latch.stampMillis() + REENTRY_LATCH_TTL_MILLIS <= now)
		{
			REENTRY_LATCHES.remove(entityId, latch);
			return null;
		}
		return latch;
	}

	public static void latchReentryIfInsidePortal(Entity entity)
	{
		if(entity == null || Wormholes.portalManager == null)
		{
			return;
		}
		if(isReentryLatched(entity.getUniqueId()))
		{
			return;
		}
		for(ILocalPortal portal : Wormholes.portalManager.getLocalPortals())
		{
			if(portal instanceof LocalPortal local && local.isOccupyingPortal(entity))
			{
				latchReentry(entity.getUniqueId(), local.getId());
				return;
			}
		}
	}

	private static void pruneTeleportCooldowns(long now)
	{
		if(TELEPORT_COOLDOWNS.size() < 256 && REENTRY_LATCHES.size() < 256)
		{
			return;
		}

		Iterator<Map.Entry<UUID, Long>> iterator = TELEPORT_COOLDOWNS.entrySet().iterator();
		while(iterator.hasNext())
		{
			Map.Entry<UUID, Long> entry = iterator.next();
			if(entry.getValue().longValue() <= now)
			{
				TELEPORT_COOLDOWNS.remove(entry.getKey(), entry.getValue());
			}
		}
		Iterator<Map.Entry<UUID, ReentryLatch>> latchIterator = REENTRY_LATCHES.entrySet().iterator();
		while(latchIterator.hasNext())
		{
			Map.Entry<UUID, ReentryLatch> entry = latchIterator.next();
			if(entry.getValue().stampMillis() + REENTRY_LATCH_TTL_MILLIS <= now)
			{
				REENTRY_LATCHES.remove(entry.getKey(), entry.getValue());
			}
		}
	}

	private static final class ReentryLatch
	{
		private final UUID portalId;
		private final long stampMillis;
		private volatile boolean armed;

		private ReentryLatch(UUID portalId, long stampMillis)
		{
			this.portalId = portalId;
			this.stampMillis = stampMillis;
			this.armed = false;
		}

		private static ReentryLatch waiting(UUID portalId, long stampMillis)
		{
			return new ReentryLatch(portalId, stampMillis);
		}

		private static ReentryLatch armed(UUID portalId, long stampMillis)
		{
			ReentryLatch latch = new ReentryLatch(portalId, stampMillis);
			latch.armed = true;
			return latch;
		}

		private UUID portalId()
		{
			return portalId;
		}

		private long stampMillis()
		{
			return stampMillis;
		}
	}

	@Override
	public ITunnel getTunnel()
	{
		return tunnel;
	}

	@Override
	public void setDestination(IPortal portal)
	{
		if(type == PortalType.RTP || (portal instanceof ILocalPortal localPortal && localPortal.getType() == PortalType.RTP))
		{
			return;
		}
		if(dimensionalPortalKind.isReceiverOnly()
				|| (dimensionalPortalKind.isManagedPortal() && dimensionalCounterpartId != null))
		{
			return;
		}
		detachDimensionalPairIdentity();
		if(portal instanceof ILocalPortal)
		{
			ILocalPortal p = (ILocalPortal) portal;

			if(p.getStructure().getWorld().equals(getStructure().getWorld()))
			{
				tunnel = new LocalTunnel(p);
				save();
			}

			else
			{
				tunnel = new DimensionalTunnel(p);
				save();
			}
		}

		else if(portal instanceof IRemotePortal)
		{
			tunnel = new UniversalTunnel((IRemotePortal) portal);
			save();
		}

		else
		{
			throw new RuntimeException("Unable to determine identity of new destination!");
		}
	}

	@Override
	public void linkRemote(String serverName, UUID portalId)
	{
		if(type == PortalType.RTP || dimensionalPortalKind.isManagedPortal())
		{
			return;
		}
		detachDimensionalPairIdentity();
		tunnel = new UniversalTunnel(serverName, portalId);
		save();
	}

	@Override
	public void destroy()
	{
		if(!destructionStarted.compareAndSet(false, true))
		{
			return;
		}

		UUID destructionCounterpartId = resolveDimensionalCounterpartId();
		boolean explicitDimensionalCounterpart = dimensionalCounterpartId != null;
		ILocalPortal dimensionalCounterpart = destructionCounterpartId == null || Wormholes.portalManager == null
				? null : Wormholes.portalManager.getLocalPortal(destructionCounterpartId);
		effectSequence.incrementAndGet();
		tunnel = null;

		AxisAlignedBB deletionArea = getStructure().getArea();
		World deletionWorld = getStructure().getWorld();
		Location deletionCenter = getStructure().getCenter();
		Location anchor = deletionCenter != null ? deletionCenter : getCenter();

		if(Wormholes.projectionManager != null)
		{
			Wormholes.projectionManager.removeProjector(this);
		}
		if(Wormholes.portalManager != null)
		{
			Wormholes.portalManager.removeLocalPortal(LocalPortal.this);
			deleteData();
		}
		playDeletionEffect(deletionArea, deletionWorld, deletionCenter, anchor);

		if(dimensionalCounterpart != null && (explicitDimensionalCounterpart || isReciprocalDimensionalCounterpart(dimensionalCounterpart)))
		{
			dimensionalCounterpart.destroy();
		}
		else if(dimensionalCounterpart == null && destructionCounterpartId != null && Wormholes.portalManager != null)
		{
			Wormholes.portalManager.deletePersistedPairedPortal(destructionCounterpartId, getId());
		}
	}

	@Override
	public boolean isDestroyed()
	{
		return destructionStarted.get();
	}

	private UUID resolveDimensionalCounterpartId()
	{
		if(dimensionalCounterpartId != null)
		{
			return dimensionalCounterpartId;
		}
		if(tunnel instanceof DimensionalTunnel dimensionalTunnel)
		{
			return dimensionalTunnel.getDestinationId();
		}
		return null;
	}

	private boolean isReciprocalDimensionalCounterpart(ILocalPortal counterpart)
	{
		if(counterpart == this)
		{
			return false;
		}
		if(getId().equals(counterpart.getDimensionalCounterpartId()))
		{
			return true;
		}
		ITunnel counterpartTunnel = counterpart.getTunnel();
		return counterpartTunnel instanceof DimensionalTunnel dimensionalTunnel
				&& getId().equals(dimensionalTunnel.getDestinationId());
	}

	private void playDeletionEffect(AxisAlignedBB deletionArea, World deletionWorld, Location deletionCenter, Location anchor)
	{
		if(anchor == null || anchor.getWorld() == null)
		{
			return;
		}
		FoliaScheduler.runRegion(Wormholes.instance, anchor, () ->
		{
			if(deletionArea != null && deletionWorld != null && Wormholes.effectManager != null)
			{
				Location deletionCorner = new Location(deletionWorld, Math.min(deletionArea.getXa(), deletionArea.getXb()), Math.min(deletionArea.getYa(), deletionArea.getYb()), Math.min(deletionArea.getZa(), deletionArea.getZb()));
				double sx = Math.abs(deletionArea.getXb() - deletionArea.getXa());
				double sy = Math.abs(deletionArea.getYb() - deletionArea.getYa());
				double sz = Math.abs(deletionArea.getZb() - deletionArea.getZa());
				Wormholes.effectManager.playPortalDeletion(deletionWorld, deletionCorner, sx, sy, sz);
			}
			if(deletionCenter != null && Wormholes.effectManager != null)
			{
				Wormholes.effectManager.playNotificationFail(Wormholes.text().legacy(
						WormholesMessages.PORTAL_DELETED,
						arguments("portal", getName())), deletionCenter);
			}
		});
	}

	@Override
	public boolean hasTunnel()
	{
		return getTunnel() != null && getTunnel().getDestination() != null;
	}

	@Override
	public void unlink()
	{
		if(tunnel == null && dimensionalCounterpartId == null)
		{
			return;
		}
		detachDimensionalPairIdentity();
		tunnel = null;
		save();
	}

	private void detachDimensionalPairIdentity()
	{
		UUID previousId = resolveDimensionalCounterpartId();
		if(previousId == null)
		{
			return;
		}
		dimensionalCounterpartId = null;
		if(Wormholes.portalManager != null)
		{
			ILocalPortal previous = Wormholes.portalManager.getLocalPortal(previousId);
			if(previous instanceof LocalPortal localPrevious)
			{
				localPrevious.clearDimensionalCounterpartReference(getId());
				localPrevious.clearDimensionalTunnelReference(getId());
			}
			else if(previous == null)
			{
				PortalManager manager = Wormholes.portalManager;
				boolean scheduled = FoliaScheduler.runAsync(Wormholes.instance,
						() -> manager.clearPersistedPairedPortalReference(previousId, getId()));
				if(!scheduled)
				{
					manager.clearPersistedPairedPortalReference(previousId, getId());
				}
			}
		}
		save();
	}

	private void clearDimensionalCounterpartReference(UUID expectedId)
	{
		if(!Objects.equals(dimensionalCounterpartId, expectedId))
		{
			return;
		}
		dimensionalCounterpartId = null;
		save();
	}

	private void clearDimensionalTunnelReference(UUID expectedId)
	{
		ITunnel activeTunnel = tunnel;
		if(!(activeTunnel instanceof DimensionalTunnel dimensionalTunnel)
				|| !Objects.equals(dimensionalTunnel.getDestinationId(), expectedId))
		{
			return;
		}
		tunnel = null;
		save();
	}

	@Override
	public UUID getDimensionalCounterpartId()
	{
		return dimensionalCounterpartId;
	}

	@Override
	public void setDimensionalCounterpartId(UUID counterpartId)
	{
		if(type == PortalType.RTP && counterpartId != null)
		{
			return;
		}
		if(Objects.equals(dimensionalCounterpartId, counterpartId))
		{
			return;
		}
		dimensionalCounterpartId = counterpartId;
		save();
	}

	@Override
	public DimensionalPortalKind getDimensionalPortalKind()
	{
		return dimensionalPortalKind;
	}

	@Override
	public void setDimensionalPortalKind(DimensionalPortalKind kind)
	{
		DimensionalPortalKind normalized = kind == null ? DimensionalPortalKind.NONE : kind;
		if(type == PortalType.RTP && normalized.isManagedPortal())
		{
			return;
		}
		boolean kindChanged = dimensionalPortalKind != normalized;
		dimensionalPortalKind = normalized;
		boolean stateChanged = normalizeDimensionalState();
		if(!kindChanged && !stateChanged)
		{
			return;
		}
		if(stateChanged)
		{
			invalidateProjection();
		}
		save();
	}

	private boolean normalizeDimensionalState()
	{
		if(!dimensionalPortalKind.isManagedPortal())
		{
			return false;
		}
		boolean changed = false;
		if(type != PortalType.PORTAL)
		{
			type = PortalType.PORTAL;
			changed = true;
		}
		if(mirrorMode)
		{
			mirrorMode = false;
			changed = true;
		}
		if(dimensionalPortalKind == DimensionalPortalKind.NETHER)
		{
			if(!outgoingTraversalsEnabled)
			{
				outgoingTraversalsEnabled = true;
				changed = true;
			}
			if(!incomingTraversalsEnabled)
			{
				incomingTraversalsEnabled = true;
				changed = true;
			}
			return changed;
		}
		if(dimensionalPortalKind == DimensionalPortalKind.END_SOURCE)
		{
			if(!outgoingTraversalsEnabled)
			{
				outgoingTraversalsEnabled = true;
				changed = true;
			}
			if(incomingTraversalsEnabled)
			{
				incomingTraversalsEnabled = false;
				changed = true;
			}
			return changed;
		}
		if(dimensionalCounterpartId == null && tunnel instanceof DimensionalTunnel dimensionalTunnel)
		{
			dimensionalCounterpartId = dimensionalTunnel.getDestinationId();
			changed = dimensionalCounterpartId != null;
		}
		if(tunnel != null)
		{
			tunnel = null;
			changed = true;
		}
		if(projectionMode != ProjectionMode.OFF)
		{
			projectionMode = ProjectionMode.OFF;
			changed = true;
		}
		if(outgoingTraversalsEnabled)
		{
			outgoingTraversalsEnabled = false;
			changed = true;
		}
		if(!incomingTraversalsEnabled)
		{
			incomingTraversalsEnabled = true;
			changed = true;
		}
		return changed;
	}

	private boolean normalizeRtpState()
	{
		if(type != PortalType.RTP)
		{
			return false;
		}
		boolean changed = false;
		if(tunnel != null)
		{
			tunnel = null;
			changed = true;
		}
		if(dimensionalCounterpartId != null)
		{
			dimensionalCounterpartId = null;
			changed = true;
		}
		if(mirrorMode)
		{
			type = PortalType.PORTAL;
			changed = true;
		}
		if(rtpSettings == null)
		{
			rtpSettings = defaultRtpSettings();
			changed = rtpSettings != null || changed;
		}
		return changed;
	}

	private RtpSettings loadRtpSettings(JSONObject json)
	{
		if(!json.has("rtp"))
		{
			return type == PortalType.RTP ? defaultRtpSettings() : null;
		}
		World world = structure.getWorld();
		if(world == null)
		{
			return null;
		}
		JSONObject stored = json.optJSONObject("rtp");
		if(stored == null)
		{
			Wormholes.w("Portal " + getId() + " has malformed RTP settings; approved defaults will be persisted");
			return defaultRtpSettings();
		}
		return RtpSettings.fromJson(stored, this::resolveRtpWorld);
	}

	private boolean requiresRtpPersistenceNormalization(JSONObject json)
	{
		if(!json.has("rtp"))
		{
			return rtpSettings != null;
		}
		if(rtpSettings == null)
		{
			return true;
		}
		JSONObject stored = json.optJSONObject("rtp");
		if(stored == null)
		{
			return true;
		}
		JSONObject canonicalStored = new JSONObject(stored.toString());
		JSONObject canonicalSettings = new JSONObject(rtpSettings.toJson().toString());
		return !canonicalStored.similar(canonicalSettings);
	}

	private RtpSettings defaultRtpSettings()
	{
		World world = structure == null ? null : structure.getWorld();
		return world == null ? null : RtpSettings.defaults(world);
	}

	private World resolveRtpWorld(String worldKey)
	{
		if(worldKey == null || worldKey.isBlank())
		{
			return structure.getWorld();
		}
		return WorldIdentity.resolve(worldKey).orElse(null);
	}

	private void applyRtpSettings(RtpSettings settings)
	{
		boolean changed = !settings.equals(rtpSettings);
		rtpSettings = settings;
		if(changed)
		{
			advanceRtpConfigurationRevision();
			save();
			if(Wormholes.rtpRuntime != null)
			{
				Wormholes.rtpRuntime.synchronize(this);
			}
		}
	}

	private void advanceRtpConfigurationRevision()
	{
		rtpConfigurationRevision.updateAndGet(value -> value == Long.MAX_VALUE ? 1L : value + 1L);
	}

	@Override
	public void uiOpenPortalMenu(Player p)
	{
		if(!ensureCanManagePortal(p))
		{
			return;
		}
		Wormholes.v("QA_EVT {\"event\":\"portal_menu_open\",\"status\":\"info\",\"details\":\"home\",\"context\":{\"portal\":\""
				+ getId() + "\",\"gateway\":" + isGateway() + "}}");
		Window w = uiCreatePortalMenu(p);
		w.setVisible(true);
	}

	@Override
	public Window uiCreatePortalMenu(Player p)
	{
		UIWindow window = new UIWindow(Wormholes.instance, p);
		window.setTitle(getRouter(true));
		window.setResolution(WindowResolution.W9_H6);
		window.setViewportHeight(4);
		window.setDecorator(new UIPaneDecorator(Material.GRAY_STAINED_GLASS_PANE));
		window.onClosed((w) -> openMenus.remove(p.getUniqueId(), window));
		rebuildPortalMenuElements(window, p);
		openMenus.put(p.getUniqueId(), window);
		return window;
	}

	private void rebuildPortalMenuElements(UIWindow window, Player p)
	{
		window.batch(() ->
		{
			window.clearElements();
			window.setElement(0, 0, portalPlacardElement());

			RtpSettings currentRtpSettings = rtpSettings;
			boolean rtp = type == PortalType.RTP && currentRtpSettings != null;
			UIElement destination = new UIElement("set-destination");
			LinesKey destinationKey = rtp
					? WormholesMessages.PORTAL_MENU_RTP_DESTINATION
					: isGateway() ? WormholesMessages.PORTAL_MENU_GATEWAY_DESTINATION : WormholesMessages.PORTAL_MENU_DESTINATION;
			String destinationLabel = rtp
					? rtpRotationSummary(currentRtpSettings)
					: hasTunnel() ? getTunnel().getDestination().getName() : localized(WormholesMessages.LABEL_NONE);
			String destinationArgument = rtp ? "rotation" : "destination";
			Wormholes.text().apply(destination, destinationKey, arguments(destinationArgument, destinationLabel));
			destination.setMaterial(new MaterialBlock(rtp ? Material.COMPASS : isGateway() ? Material.END_CRYSTAL : Material.ENDER_EYE));
			destination.setCount(rtp ? 1 : Math.max(1, Wormholes.portalManager.getAccessableCount(getType()) - 1));
			destination.onLeftClick((e) ->
			{
						if(dimensionalPortalKind.isManagedPortal())
						{
							notifySetting(p, WormholesMessages.PORTAL_DIMENSIONAL_LINK_MANAGED);
							return;
						}
						if(rtp)
						{
							window.close();
							uiOpenRtpEditor(p);
						}
						else if(isGateway())
						{
							window.close();
							uiOpenGatewayPairMenu(p);
						}
						else
						{
							uiChooseDestination(p);
						}
					});
			window.setElement(-2, 1, destination);

			UIElement rename = localizedElement(
					"set-name", WormholesMessages.PORTAL_MENU_RENAME, arguments("portal", getName()), Material.NAME_TAG);
			rename.onLeftClick((e) -> uiChangeName(p));
			window.setElement(-2, 2, rename);

			window.setElement(0, 1, projectionsElement(window, p));
			window.setElement(2, 1, settingsOpenerElement(window, p));
			window.setElement(0, 2, orientationOpenerElement(window, p));
			window.setElement(2, 2, modeOpenerElement(window, p));

			UIElement destroy = localizedElement(
					"destroy", WormholesMessages.PORTAL_MENU_DELETE, MessageArgs.empty(), Material.GUNPOWDER);
			destroy.onShiftLeftClick((e) ->
			{
						if(!ensureCanManagePortal(p))
						{
							return;
						}
						window.close();
						destroy();
					});
			window.setElement(0, 3, destroy);
		});
	}

	private void uiOpenRtpEditor(Player viewer)
	{
		if(!ensureCanManagePortal(viewer))
		{
			return;
		}
		if(type != PortalType.RTP || rtpSettings == null)
		{
			notifySetting(viewer, WormholesMessages.PORTAL_NOT_RTP);
			uiOpenPortalMenu(viewer);
			return;
		}
		Wormholes.v("QA_EVT {\"event\":\"rtp_editor_open\",\"status\":\"info\",\"details\":\"configuration\",\"context\":{\"portal\":\""
				+ getId() + "\",\"allocation\":\"" + rtpSettings.getAllocationMode().name()
				+ "\",\"rotation\":\"" + rtpSettings.getRotationMode().name() + "\"}}");
		RtpEditorSession replacement = new RtpEditorSession(viewer);
		RtpEditorSession previous = rtpEditorSessions.put(viewer.getUniqueId(), replacement);
		if(previous != null)
		{
			previous.close();
		}
		replacement.open();
	}

	private boolean runRtpSourceTask(Runnable task)
	{
		Location center = structure == null ? null : structure.getCenter();
		World world = center == null ? null : center.getWorld();
		if(Wormholes.instance == null || world == null)
		{
			task.run();
			return true;
		}
		if(FoliaScheduler.isOwnedByCurrentRegion(world, center.getBlockX() >> 4, center.getBlockZ() >> 4))
		{
			task.run();
			return true;
		}
		return FoliaScheduler.runRegion(Wormholes.instance, center, task);
	}

	private final class RtpEditorSession implements RtpPortalEditor.Host
	{
		private final UUID viewerId;
		private final UIWindow window;
		private final RtpPortalEditor editor;
		private RtpSettings baseSettings;
		private RtpSettings draftSettings;
		private long baseRevision;

		private RtpEditorSession(Player viewer)
		{
			viewerId = viewer.getUniqueId();
			window = new UIWindow(Wormholes.instance, viewer);
			window.setResolution(WindowResolution.W9_H6);
			window.onClosed(closed -> rtpEditorSessions.remove(viewerId, this));
			editor = new RtpPortalEditor(this);
			baseSettings = Objects.requireNonNull(rtpSettings, "RTP settings");
			draftSettings = baseSettings;
			baseRevision = rtpConfigurationRevision.get();
		}

		private void open()
		{
			editor.populate(window, viewerId);
			window.setVisible(true);
		}

		private void close()
		{
			if(window.isVisible())
			{
				window.close();
			}
			rtpEditorSessions.remove(viewerId, this);
		}

		@Override
		public RtpPortalEditorModel.EditorSnapshot snapshot(UUID requestedViewerId)
		{
			if(!viewerId.equals(requestedViewerId) || type != PortalType.RTP || rtpSettings == null)
			{
				throw new IllegalStateException("RTP editor session is stale");
			}
			if(rtpConfigurationRevision.get() != baseRevision && !draftDirty())
			{
				baseSettings = rtpSettings;
				draftSettings = baseSettings;
				baseRevision = rtpConfigurationRevision.get();
			}
			RtpSettings settings = draftSettings;
			ArrayList<RtpPortalEditorModel.WorldOption> worlds = new ArrayList<RtpPortalEditorModel.WorldOption>();
			for(World world : Bukkit.getWorlds())
			{
				worlds.add(RtpPortalEditorModel.WorldOption.from(world));
			}
			boolean targetWorldAvailable = resolveRtpWorld(settings.getTargetWorldKey()) != null;
			RtpPortalEditorModel.StatusSnapshot status = Wormholes.rtpRuntime == null
					? idleStatus(targetWorldAvailable)
					: Wormholes.rtpRuntime.editorStatus(getId()).orElseGet(() -> idleStatus(targetWorldAvailable));
			Location center = Objects.requireNonNull(structure.getCenter(), "portal center");
			return new RtpPortalEditorModel.EditorSnapshot(
					baseRevision,
					Wormholes.text().plain(WormholesMessages.PORTAL_RTP_EDITOR_TITLE, arguments("portal", getName())),
					RtpPortalEditorModel.SettingsSnapshot.from(settings),
					status,
					worlds,
					center.getX(),
					center.getZ(),
					draftDirty());
		}

		@Override
		public void mutate(UUID requestedViewerId, long expectedRevision, RtpPortalEditorModel.Mutation mutation)
		{
			Player viewer = Bukkit.getPlayer(requestedViewerId);
			if(viewer == null || !viewerId.equals(requestedViewerId))
			{
				return;
			}
			FoliaScheduler.runEntity(Wormholes.instance, viewer, () -> mutateForViewer(viewer, expectedRevision, mutation));
		}

		@Override
		public void apply(UUID requestedViewerId, long expectedRevision)
		{
			Player viewer = Bukkit.getPlayer(requestedViewerId);
			if(viewer == null || !viewerId.equals(requestedViewerId))
			{
				return;
			}
			FoliaScheduler.runEntity(Wormholes.instance, viewer, () -> applyForViewer(viewer, expectedRevision));
		}

		@Override
		public void discard(UUID requestedViewerId)
		{
			Player viewer = Bukkit.getPlayer(requestedViewerId);
			if(viewer == null || !viewerId.equals(requestedViewerId))
			{
				return;
			}
			FoliaScheduler.runEntity(Wormholes.instance, viewer, () ->
			{
				baseSettings = rtpSettings;
				draftSettings = baseSettings;
				baseRevision = rtpConfigurationRevision.get();
				refresh(WormholesMessages.PORTAL_RTP_DRAFT_DISCARDED);
			});
		}

		@Override
		public void manual(UUID requestedViewerId, long expectedRevision, RtpPortalEditorModel.ManualAction action)
		{
			Player viewer = Bukkit.getPlayer(requestedViewerId);
			if(viewer == null || !viewerId.equals(requestedViewerId))
			{
				return;
			}
			FoliaScheduler.runEntity(Wormholes.instance, viewer, () -> manualForViewer(viewer, expectedRevision, action));
		}

		@Override
		public void back(UUID requestedViewerId)
		{
			Player viewer = Bukkit.getPlayer(requestedViewerId);
			if(viewer == null || !viewerId.equals(requestedViewerId))
			{
				return;
			}
			FoliaScheduler.runEntity(Wormholes.instance, viewer, () ->
			{
				close();
				uiOpenPortalMenu(viewer);
			});
		}

		private void mutateForViewer(Player viewer, long expectedRevision, RtpPortalEditorModel.Mutation mutation)
		{
			if(!ensureCanManagePortal(viewer))
			{
				close();
				return;
			}
			Runnable mutationTask = () ->
			{
				if(type != PortalType.RTP || rtpSettings == null)
				{
					refresh(WormholesMessages.PORTAL_NOT_RTP);
					return;
				}
				if(baseRevision != expectedRevision || rtpConfigurationRevision.get() != baseRevision)
				{
					baseSettings = rtpSettings;
					draftSettings = baseSettings;
					baseRevision = rtpConfigurationRevision.get();
					refresh(WormholesMessages.PORTAL_RTP_DRAFT_REFRESHED);
					return;
				}
				try
				{
					World sourceWorld = Objects.requireNonNull(structure.getWorld(), "portal source world");
					draftSettings = RtpPortalEditorModel.applyMutation(
							draftSettings,
							mutation,
							sourceWorld,
							LocalPortal.this::resolveRtpWorld);
					Wormholes.v("QA_EVT {\"event\":\"rtp_editor_stage\",\"status\":\"pass\",\"details\":\""
							+ mutation.getClass().getSimpleName() + "\",\"context\":{\"portal\":\"" + getId()
							+ "\",\"revision\":" + baseRevision + "}}");
					refresh(WormholesMessages.PORTAL_RTP_CHANGE_STAGED);
				}
				catch(IllegalArgumentException | IllegalStateException exception)
				{
					refresh(WormholesMessages.PORTAL_RTP_SETTING_REJECTED, arguments("reason", exception.getMessage()));
				}
			};
			if(!runRtpSourceTask(mutationTask))
			{
				refresh(WormholesMessages.PORTAL_REGION_UNAVAILABLE);
			}
		}

		private void applyForViewer(Player viewer, long expectedRevision)
		{
			if(!ensureCanManagePortal(viewer))
			{
				close();
				return;
			}
			Runnable applyTask = () ->
			{
				if(type != PortalType.RTP || rtpSettings == null)
				{
					refresh(WormholesMessages.PORTAL_NOT_RTP);
					return;
				}
				if(baseRevision != expectedRevision || rtpConfigurationRevision.get() != baseRevision)
				{
					baseSettings = rtpSettings;
					draftSettings = baseSettings;
					baseRevision = rtpConfigurationRevision.get();
					refresh(WormholesMessages.PORTAL_RTP_STALE_APPLY);
					return;
				}
				if(!draftDirty())
				{
					refresh(WormholesMessages.PORTAL_RTP_NO_CHANGES);
					return;
				}
				applyRtpSettings(draftSettings);
				baseSettings = rtpSettings;
				draftSettings = baseSettings;
				baseRevision = rtpConfigurationRevision.get();
				Wormholes.v("QA_EVT {\"event\":\"rtp_editor_apply\",\"status\":\"pass\",\"details\":\"batch\",\"context\":{\"portal\":\""
						+ getId() + "\",\"revision\":" + baseRevision + "}}");
				refresh(WormholesMessages.PORTAL_RTP_APPLIED);
			};
			if(!runRtpSourceTask(applyTask))
			{
				refresh(WormholesMessages.PORTAL_REGION_UNAVAILABLE);
			}
		}

		private boolean draftDirty()
		{
			return !draftSettings.equals(baseSettings);
		}

		private void manualForViewer(Player viewer, long expectedRevision, RtpPortalEditorModel.ManualAction action)
		{
			if(!ensureCanManagePortal(viewer))
			{
				close();
				return;
			}
			if(type != PortalType.RTP || rtpSettings == null || baseRevision != expectedRevision
					|| rtpConfigurationRevision.get() != baseRevision || draftDirty())
			{
				refresh(WormholesMessages.PORTAL_RTP_EDITOR_REFRESHED);
				return;
			}
			if(Wormholes.rtpRuntime == null)
			{
				refresh(WormholesMessages.PORTAL_RTP_RUNTIME_UNAVAILABLE);
				return;
			}
			if(action == RtpPortalEditorModel.ManualAction.REROLL)
			{
				Wormholes.rtpRuntime.requestManualReroll(getId()).whenComplete((accepted, failure) ->
						refresh(failure != null
								? WormholesMessages.PORTAL_RTP_REROLL_FAILED
								: Boolean.TRUE.equals(accepted)
										? WormholesMessages.PORTAL_RTP_REROLL_PREPARING
										: WormholesMessages.PORTAL_RTP_REROLL_UNAVAILABLE));
				return;
			}
			Wormholes.rtpRuntime.requestPoolRebuild(getId()).whenComplete((removed, failure) ->
					refresh(failure == null
							? WormholesMessages.PORTAL_RTP_POOL_REBUILDING
							: WormholesMessages.PORTAL_RTP_POOL_FAILED));
		}

		private void refresh(TextKey message)
		{
			refresh(message, MessageArgs.empty());
		}

		private void refresh(TextKey message, MessageArgs arguments)
		{
			Player viewer = Bukkit.getPlayer(viewerId);
			if(viewer == null)
			{
				rtpEditorSessions.remove(viewerId, this);
				return;
			}
			FoliaScheduler.runEntity(Wormholes.instance, viewer, () ->
			{
				if(message != null)
				{
					notifySetting(viewer, message, arguments);
				}
				if(type != PortalType.RTP || rtpSettings == null)
				{
					close();
					uiOpenPortalMenu(viewer);
					return;
				}
				if(window.isVisible())
				{
					editor.populate(window, viewerId);
					window.updateInventory();
				}
			});
		}

		private RtpPortalEditorModel.StatusSnapshot idleStatus(boolean targetWorldAvailable)
		{
			return new RtpPortalEditorModel.StatusSnapshot(
					targetWorldAvailable
							? RtpPortalEditorModel.StatusState.IDLE
							: RtpPortalEditorModel.StatusState.TARGET_WORLD_UNAVAILABLE,
					targetWorldAvailable,
					true,
					false,
					false,
					0L,
					0L,
					0,
					0,
					0,
					0);
		}
	}

	private void uiOpenSettingsMenu(Player p)
	{
		Window w = uiCreateSettingsMenu(p);
		w.setVisible(true);
	}

	private Window uiCreateSettingsMenu(Player p)
	{
		UIWindow window = new UIWindow(Wormholes.instance, p);
		window.setTitle(getRouter(true));
		window.setResolution(WindowResolution.W9_H6);
		window.setViewportHeight(3);
		window.setDecorator(new UIPaneDecorator(Material.GRAY_STAINED_GLASS_PANE));

		window.setElement(0, 0, settingsPlacardElement());

		if(isGateway())
		{
			window.setElement(-3, 1, permissionElement(window, p));
			window.setElement(-1, 1, travelDirectionElement(window, p));
			window.setElement(1, 1, networkViewQualityElement(window, p));
			window.setElement(3, 1, settingsSyncElement(window, p));
		}
		else
		{
			window.setElement(-1, 1, permissionElement(window, p));
			window.setElement(1, 1, travelDirectionElement(window, p));
		}

		window.setElement(0, 2, backToPortalMenuElement(window, p));

		return window;
	}

	private void uiOpenAdvancedSettingsMenu(Player p)
	{
		UIWindow window = new UIWindow(Wormholes.instance, p);
		window.setTitle(getRouter(true));
		window.setResolution(WindowResolution.W9_H6);
		window.setViewportHeight(4);
		window.setDecorator(new UIPaneDecorator(Material.BLACK_STAINED_GLASS_PANE));
		window.setElement(0, 0, advancedSettingsPlacardElement());
		window.setElement(-3, 1, networkViewNumberElement(window, p, "network-view-depth",
				WormholesMessages.PORTAL_NETWORK_LABEL_CAPTURE_RADIUS,
				WormholesMessages.PORTAL_NETWORK_DESCRIPTION_CAPTURE_RADIUS,
				Material.SPYGLASS, this::getNetworkViewDepth, this::setNetworkViewDepth, 4, 16));
		window.setElement(-1, 1, networkViewNumberElement(window, p, "network-view-heartbeat",
				WormholesMessages.PORTAL_NETWORK_LABEL_FULL_REFRESH,
				WormholesMessages.PORTAL_NETWORK_DESCRIPTION_FULL_REFRESH,
				Material.CLOCK, this::getNetworkViewHeartbeatTicks, this::setNetworkViewHeartbeatTicks, 10, 60));
		window.setElement(1, 1, networkViewNumberElement(window, p, "network-view-entities",
				WormholesMessages.PORTAL_NETWORK_LABEL_ENTITY_UPDATE,
				WormholesMessages.PORTAL_NETWORK_DESCRIPTION_ENTITY_UPDATE,
				Material.ENDER_EYE, this::getNetworkViewEntityIntervalTicks, this::setNetworkViewEntityIntervalTicks, 2, 20));
		window.setElement(3, 1, networkViewNumberElement(window, p, "network-view-grace",
				WormholesMessages.PORTAL_NETWORK_LABEL_VIEW_GRACE,
				WormholesMessages.PORTAL_NETWORK_DESCRIPTION_VIEW_GRACE,
				Material.REDSTONE, this::getNetworkViewUnsubscribeGraceSeconds, this::setNetworkViewUnsubscribeGraceSeconds, 5, 30));
		window.setElement(0, 2, networkViewFallbackElement(p, window));
		window.setElement(0, 3, backToSettingsMenuElement(window, p));
		window.setVisible(true);
	}

	private Element advancedSettingsPlacardElement()
	{
		return localizedElement("advanced-settings-placard", WormholesMessages.PORTAL_MENU_ADVANCED_SETTINGS,
				MessageArgs.empty(), Material.COMPARATOR);
	}

	private Element backToSettingsMenuElement(Window window, Player viewer)
	{
		UIElement element = localizedElement("back-to-settings", WormholesMessages.PORTAL_MENU_BACK_SETTINGS,
				MessageArgs.empty(), Material.ARROW);
		element.onLeftClick((e) -> FoliaScheduler.runEntity(Wormholes.instance, viewer, () ->
		{
			window.close();
			uiOpenSettingsMenu(viewer);
		}));
		return element;
	}

	private Element travelDirectionElement(Window window, Player viewer)
	{
		UIElement element = new UIElement("travel-direction");
		element.onLeftClick((e) ->
		{
			if(dimensionalPortalKind.isManagedPortal())
			{
				notifySetting(viewer, WormholesMessages.PORTAL_TRAVEL_MANAGED);
				return;
			}
			if(isMirrorMode())
			{
				notifySetting(viewer, WormholesMessages.PORTAL_TRAVEL_MIRROR_LOCKED);
				return;
			}
			PortalTravelMode mode = getTravelMode().next();
			setTravelMode(mode);
			applyTravelDirectionElement(element);
			window.updateInventory();
			notifySetting(viewer, WormholesMessages.PORTAL_TRAVEL_CHANGED,
					arguments("mode", travelModeLabel(mode)));
		});
		applyTravelDirectionElement(element);
		return element;
	}

	private PortalTravelMode getTravelMode()
	{
		return PortalTravelMode.from(isOutgoingTraversalsEnabled(), isIncomingTraversalsEnabled());
	}

	private void setTravelMode(PortalTravelMode mode)
	{
		if(dimensionalPortalKind.isManagedPortal())
		{
			return;
		}
		boolean nextOutgoing = mode.allowsOutgoing();
		boolean nextIncoming = mode.allowsIncoming();
		if(outgoingTraversalsEnabled == nextOutgoing && incomingTraversalsEnabled == nextIncoming)
		{
			return;
		}
		outgoingTraversalsEnabled = nextOutgoing;
		incomingTraversalsEnabled = nextIncoming;
		Wormholes.v("QA_EVT {\"event\":\"travel_mode\",\"status\":\"info\",\"details\":\"" + mode.name().toLowerCase(Locale.ROOT)
				+ "\",\"context\":{\"portal\":\"" + getId() + "\"}}");
		save();
		broadcastSettingsIfEnabled();
		refreshOpenMenusUnlessApplyingRemote();
	}

	private void applyTravelDirectionElement(Element element)
	{
		if(dimensionalPortalKind.isManagedPortal())
		{
			boolean receiver = dimensionalPortalKind.isReceiverOnly();
			String direction = dimensionalPortalKind == DimensionalPortalKind.NETHER
					? localized(WormholesMessages.PORTAL_LABEL_BOTH_WAYS)
					: receiver
							? localized(WormholesMessages.PORTAL_LABEL_ARRIVAL_ONLY)
							: localized(WormholesMessages.PORTAL_LABEL_DEPARTURE_ONLY);
			String detail = dimensionalPortalKind == DimensionalPortalKind.NETHER
					? localized(WormholesMessages.PORTAL_LABEL_DIMENSIONAL_BOTH_ACTIVE)
					: localized(WormholesMessages.PORTAL_LABEL_DIMENSIONAL_RETURN_DISABLED);
			Wormholes.text().apply(element, WormholesMessages.PORTAL_MENU_TRAVEL_MANAGED,
					arguments("direction", direction, "detail", detail));
			element.setEnchanted(true);
			element.setMaterial(new MaterialBlock(dimensionalPortalKind == DimensionalPortalKind.NETHER ? Material.OBSIDIAN
					: receiver ? Material.ENDER_EYE : Material.ENDER_PEARL));
			return;
		}
		if(isMirrorMode())
		{
			Wormholes.text().apply(element, WormholesMessages.PORTAL_MENU_TRAVEL_MIRROR);
			element.setEnchanted(false);
			element.setMaterial(new MaterialBlock(Material.BARRIER));
			return;
		}
		PortalTravelMode mode = getTravelMode();
		boolean locked = mode == PortalTravelMode.LOCKED;
		Wormholes.text().apply(element, WormholesMessages.PORTAL_MENU_TRAVEL,
				arguments(
						"mode", travelModeLabel(mode),
						"outgoing", localized(mode.allowsOutgoing() ? WormholesMessages.LABEL_ON : WormholesMessages.LABEL_OFF),
						"incoming", localized(mode.allowsIncoming() ? WormholesMessages.LABEL_ON : WormholesMessages.LABEL_OFF)));
		element.setEnchanted(!locked);
		element.setMaterial(new MaterialBlock(switch(mode)
		{
			case BOTH -> Material.RECOVERY_COMPASS;
			case OUTBOUND -> Material.ENDER_PEARL;
			case INBOUND -> Material.ENDER_EYE;
			case LOCKED -> Material.BARRIER;
		}));
	}

	private Element networkViewQualityElement(Window window, Player viewer)
	{
		UIElement element = new UIElement("network-view-quality");
		element.onLeftClick((e) ->
		{
			NetworkViewQuality quality = getNetworkViewQuality().next();
			setNetworkViewQuality(quality);
			applyNetworkViewQualityElement(element);
			window.updateInventory();
			notifySetting(viewer, WormholesMessages.PORTAL_STREAM_QUALITY_CHANGED,
					arguments("quality", networkViewQualityLabel(quality)));
		});
		element.onShiftLeftClick((e) -> FoliaScheduler.runEntity(Wormholes.instance, viewer, () ->
		{
			window.close();
			uiOpenAdvancedSettingsMenu(viewer);
		}));
		applyNetworkViewQualityElement(element);
		return element;
	}

	private NetworkViewQuality getNetworkViewQuality()
	{
		return NetworkViewQuality.from(networkViewDepth, networkViewHeartbeatTicks, networkViewEntityIntervalTicks, networkViewUnsubscribeGraceSeconds);
	}

	private void setNetworkViewQuality(NetworkViewQuality quality)
	{
		if(quality == NetworkViewQuality.CUSTOM)
		{
			return;
		}
		if(networkViewDepth == quality.getDepth()
				&& networkViewHeartbeatTicks == quality.getHeartbeatTicks()
				&& networkViewEntityIntervalTicks == quality.getEntityIntervalTicks()
				&& networkViewUnsubscribeGraceSeconds == quality.getUnsubscribeGraceSeconds())
		{
			return;
		}
		networkViewDepth = quality.getDepth();
		networkViewHeartbeatTicks = quality.getHeartbeatTicks();
		networkViewEntityIntervalTicks = quality.getEntityIntervalTicks();
		networkViewUnsubscribeGraceSeconds = quality.getUnsubscribeGraceSeconds();
		Wormholes.v("QA_EVT {\"event\":\"stream_quality\",\"status\":\"info\",\"details\":\"" + quality.name().toLowerCase(Locale.ROOT)
				+ "\",\"context\":{\"portal\":\"" + getId() + "\"}}");
		networkViewSettingsChanged();
	}

	private void applyNetworkViewQualityElement(Element element)
	{
		NetworkViewQuality quality = getNetworkViewQuality();
		Wormholes.text().apply(element, WormholesMessages.PORTAL_MENU_STREAM_QUALITY,
				arguments(
						"quality", networkViewQualityLabel(quality),
						"depth", networkViewDepth,
						"entities", networkViewEntityIntervalTicks,
						"refresh", networkViewHeartbeatTicks,
						"grace", networkViewUnsubscribeGraceSeconds));
		element.setEnchanted(quality == NetworkViewQuality.CINEMATIC);
		element.setMaterial(new MaterialBlock(switch(quality)
		{
			case STANDARD -> Material.CONDUIT;
			case PERFORMANCE -> Material.FEATHER;
			case BALANCED -> Material.SPYGLASS;
			case CINEMATIC -> Material.BEACON;
			case CUSTOM -> Material.COMPARATOR;
		}));
	}

	private Element orientationOpenerElement(Window window, Player viewer)
	{
		UIElement element = localizedElement("portal-orientation", WormholesMessages.PORTAL_MENU_ORIENTATION,
				arguments("facing", directionLabel(getDirection()), "up", directionLabel(getFrame().getUp())), Material.COMPASS);
		element.onLeftClick((e) -> FoliaScheduler.runEntity(Wormholes.instance, viewer, () ->
		{
			window.close();
			uiOpenOrientationMenu(viewer);
		}));
		return element;
	}

	private void uiOpenOrientationMenu(Player viewer)
	{
		UIWindow window = new UIWindow(Wormholes.instance, viewer);
		window.setTitle(getRouter(true));
		window.setResolution(WindowResolution.W9_H6);
		window.setViewportHeight(3);
		window.setDecorator(new UIPaneDecorator(Material.BLUE_STAINED_GLASS_PANE));
		window.setElement(0, 0, orientationPlacardElement());
		window.setElement(-3, 1, directionElement(window, viewer));
		window.setElement(-1, 1, flipFaceElement(window, viewer));
		window.setElement(1, 1, rotateCounterClockwiseElement(window, viewer));
		window.setElement(3, 1, rotateClockwiseElement(window, viewer));
		window.setElement(0, 2, backToPortalMenuElement(window, viewer));
		window.setVisible(true);
	}

	private Element orientationPlacardElement()
	{
		return localizedElement("orientation-placard", WormholesMessages.PORTAL_MENU_ORIENTATION_PLACARD,
				arguments("facing", directionLabel(getDirection()), "up", directionLabel(getFrame().getUp())), Material.COMPASS);
	}

	private void uiOpenGatewayPairMenu(Player viewer)
	{
		UIWindow window = new UIWindow(Wormholes.instance, viewer);
		window.setTitle(getRouter(true));
		window.setResolution(WindowResolution.W9_H6);
		window.setViewportHeight(3);
		window.setDecorator(new UIPaneDecorator(Material.BLACK_STAINED_GLASS_PANE));
		window.setElement(0, 0, gatewayPairPlacardElement());
		window.setElement(-2, 1, exportPortalElement(window, viewer));
		UIElement chooseDestination = localizedElement(
				"choose-gateway-destination", WormholesMessages.PORTAL_MENU_GATEWAY_CHOOSE,
				MessageArgs.empty(), Material.END_CRYSTAL);
		chooseDestination.onLeftClick((e) ->
		{
					window.close();
					uiChooseDestination(viewer);
				});
		window.setElement(0, 1, chooseDestination);
		window.setElement(2, 1, importPortalElement(window, viewer));
		window.setElement(0, 2, backToPortalMenuElement(window, viewer));
		window.setVisible(true);
	}

	private Element gatewayPairPlacardElement()
	{
		UIElement element = new UIElement("gateway-pair-placard");
		element.setMaterial(new MaterialBlock(Material.RESPAWN_ANCHOR));
		if(!hasTunnel())
		{
			Wormholes.text().apply(element, WormholesMessages.PORTAL_MENU_GATEWAY_UNPAIRED);
			return element;
		}
		Wormholes.text().apply(element, WormholesMessages.PORTAL_MENU_GATEWAY_PAIRED,
				arguments("destination", getTunnel().getDestination().getName()));
		if(getTunnel() instanceof UniversalTunnel universal && Wormholes.networkManager != null)
		{
			String peer = universal.getServerName();
			boolean ready = Wormholes.networkManager.isPeerReady(peer);
			String transport = localized(Wormholes.networkManager.isSidebandOnlyPeer(peer)
					? WormholesMessages.PORTAL_LABEL_SIDEBAND : WormholesMessages.PORTAL_LABEL_DIRECT);
			String state = ready ? transport : localized(WormholesMessages.PORTAL_LABEL_RECONNECTING);
			element.addLore(Wormholes.text().legacy(WormholesMessages.PORTAL_MENU_GATEWAY_SERVER, arguments("server", peer)));
			element.addLore(Wormholes.text().legacy(WormholesMessages.PORTAL_MENU_GATEWAY_LINK, arguments("state", state)));
		}
		return element;
	}

	private Element exportPortalElement(Window window, Player viewer)
	{
		UIElement element = localizedElement("export-portal", WormholesMessages.PORTAL_MENU_GATEWAY_EXPORT,
				MessageArgs.empty(), Material.PAPER);
		element.onLeftClick((e) -> FoliaScheduler.runEntity(Wormholes.instance, viewer, () ->
				{
					window.close();
					if(Wormholes.importExportService != null)
					{
						Wormholes.importExportService.exportToChat(viewer, LocalPortal.this);
					}
				}));
		return element;
	}

	private Element importPortalElement(Window window, Player viewer)
	{
		UIElement element = localizedElement("import-portal", WormholesMessages.PORTAL_MENU_GATEWAY_IMPORT,
				MessageArgs.empty(), Material.WRITABLE_BOOK);
		element.onLeftClick((e) -> FoliaScheduler.runEntity(Wormholes.instance, viewer, () ->
				{
					window.close();
					viewer.closeInventory();
					WormholesAudience.sendMessage(viewer, Wormholes.text().component(
							WormholesMessages.PORTAL_PROMPT_INVITE,
							arguments("cancel", localized(WormholesMessages.PORTAL_INPUT_CANCEL))));
					Wormholes.awaitChatInput(viewer, (text) ->
					{
						if(text == null || isCancelInput(text))
						{
							uiOpenGatewayPairMenu(viewer);
							return;
						}
						if(Wormholes.importExportService != null)
						{
							Wormholes.importExportService.importCode(viewer, LocalPortal.this, text);
						}
					});
				}));
		return element;
	}

	private Element networkViewNumberElement(Window window, Player viewer, String id, TextKey label, TextKey description, Material material, IntSupplier getter, IntConsumer setter, int step, int largeStep)
	{
		UIElement element = new UIElement(id);
		element.onLeftClick((e) -> adjustNetworkViewNumber(element, window, viewer, label, description, material, getter, setter, step));
		element.onRightClick((e) -> adjustNetworkViewNumber(element, window, viewer, label, description, material, getter, setter, -step));
		element.onShiftLeftClick((e) -> adjustNetworkViewNumber(element, window, viewer, label, description, material, getter, setter, largeStep));
		element.onShiftRightClick((e) -> adjustNetworkViewNumber(element, window, viewer, label, description, material, getter, setter, -largeStep));
		applyNetworkViewNumberElement(element, label, description, material, getter, step, largeStep);
		return element;
	}

	private void adjustNetworkViewNumber(UIElement element, Window window, Player viewer, TextKey label, TextKey description, Material material, IntSupplier getter, IntConsumer setter, int delta)
	{
		int previous = getter.getAsInt();
		setter.accept(previous + delta);
		int current = getter.getAsInt();
		applyNetworkViewNumberElement(element, label, description, material, getter, Math.abs(delta), Math.abs(delta));
		window.updateInventory();
		if(current != previous)
		{
			notifySetting(viewer, WormholesMessages.PORTAL_NETWORK_VALUE_CHANGED,
					arguments("label", localized(label), "value", current));
		}
	}

	private void applyNetworkViewNumberElement(Element element, TextKey label, TextKey description, Material material, IntSupplier getter, int step, int largeStep)
	{
		Wormholes.text().apply(element, WormholesMessages.PORTAL_MENU_NETWORK_NUMBER,
				arguments(
						"label", localized(label),
						"description", localized(description),
						"value", getter.getAsInt(),
						"step", Math.abs(step),
						"large_step", Math.abs(largeStep)));
		element.setMaterial(new MaterialBlock(material));
	}

	private Element networkViewFallbackElement(Player p, Window window)
	{
		UIElement element = new UIElement("network-view-fallback");
		element.onLeftClick((e) ->
		{
			window.close();
			p.closeInventory();
			WormholesAudience.sendMessage(p, Wormholes.text().component(
					WormholesMessages.PORTAL_PROMPT_BLOCK_STATE,
					arguments("cancel", localized(WormholesMessages.PORTAL_INPUT_CANCEL))));
			Wormholes.awaitChatInput(p, (text) ->
			{
				if(text == null || isCancelInput(text))
				{
					uiOpenSettingsMenu(p);
					return;
				}
				String previous = getNetworkViewFallbackBlock();
				String normalized = normalizeNetworkViewFallbackBlock(text);
				setNetworkViewFallbackBlock(normalized);
				if(!previous.equals(getNetworkViewFallbackBlock()))
				{
					notifySetting(p, WormholesMessages.PORTAL_FALLBACK_SET,
							arguments("block", getNetworkViewFallbackBlock()));
				}
				uiOpenSettingsMenu(p);
			});
		});
		element.onRightClick((e) ->
		{
			String previous = getNetworkViewFallbackBlock();
			setNetworkViewFallbackBlock(DEFAULT_NETWORK_VIEW_FALLBACK_BLOCK);
			applyNetworkViewFallbackElement(element);
			window.updateInventory();
			if(!previous.equals(getNetworkViewFallbackBlock()))
			{
				notifySetting(p, WormholesMessages.PORTAL_FALLBACK_RESET,
						arguments("block", getNetworkViewFallbackBlock()));
			}
		});
		applyNetworkViewFallbackElement(element);
		return element;
	}

	private void applyNetworkViewFallbackElement(Element element)
	{
		Wormholes.text().apply(element, WormholesMessages.PORTAL_MENU_FALLBACK_BLOCK,
				arguments("block", getNetworkViewFallbackBlock()));
		element.setMaterial(new MaterialBlock(Material.GLASS));
	}

	@Override
	public void uiChooseMode(Player p)
	{
		if(dimensionalPortalKind.isManagedPortal())
		{
			notifySetting(p, WormholesMessages.PORTAL_MANAGED_MODE);
			return;
		}
		UIWindow window = new UIWindow(Wormholes.instance, p);
		window.setTitle(getRouter(true));
		window.setResolution(WindowResolution.W9_H6);
		window.setViewportHeight(3);
		window.setDecorator(new UIPaneDecorator(Material.GRAY_STAINED_GLASS_PANE));
		window.onClosed((w) -> FoliaScheduler.runEntity(Wormholes.instance, p, () -> uiOpenPortalMenu(p)));

		window.setElement(0, 0, modePlacardElement());
		window.setElement(-4, 1, modeOption(PortalType.PORTAL, p, window));
		window.setElement(-2, 1, modeOption(PortalType.WORMHOLE, p, window));
		window.setElement(0, 1, modeOption(PortalType.GATEWAY, p, window));
		window.setElement(2, 1, modeOption(PortalType.RTP, p, window));
		window.setElement(4, 1, mirrorModeOption(p, window));
		window.setElement(0, 2, backToPortalMenuElement(window, p));

		window.setVisible(true);
	}

	private Element portalPlacardElement()
	{
		UIElement element = new UIElement("portal-placard");
		element.setMaterial(new MaterialBlock(Material.BOOK));
		if(type == PortalType.RTP && rtpSettings != null)
		{
			Wormholes.text().apply(element, WormholesMessages.PORTAL_MENU_PLACARD_RTP,
					arguments(
							"portal", getName(),
							"type", currentModeLabel(),
							"facing", directionLabel(getDirection()),
							"allocation", rtpAllocationLabel(rtpSettings.getAllocationMode()),
							"rotation", rtpRotationSummary(rtpSettings)));
		}
		else if(hasTunnel())
		{
			Wormholes.text().apply(element, WormholesMessages.PORTAL_MENU_PLACARD_LINKED,
					arguments(
							"portal", getName(),
							"type", currentModeLabel(),
							"facing", directionLabel(getDirection()),
							"destination", getTunnel().getDestination().getName()));
		}
		else
		{
			Wormholes.text().apply(element, WormholesMessages.PORTAL_MENU_PLACARD_UNLINKED,
					arguments(
							"portal", getName(),
							"type", currentModeLabel(),
							"facing", directionLabel(getDirection()),
							"none", localized(WormholesMessages.LABEL_NONE)));
		}
		return element;
	}

	private Element settingsPlacardElement()
	{
		return localizedElement("settings-placard",
				isGateway() ? WormholesMessages.PORTAL_MENU_SETTINGS_PLACARD_GATEWAY : WormholesMessages.PORTAL_MENU_SETTINGS_PLACARD,
				MessageArgs.empty(), Material.LEVER);
	}

	private Element modePlacardElement()
	{
		return localizedElement("mode-placard", WormholesMessages.PORTAL_MENU_MODE_PLACARD,
				arguments("mode", currentModeLabel()), Material.BEACON);
	}

	private Element modeOpenerElement(Window window, Player viewer)
	{
		String description = isMirrorMode()
				? localized(WormholesMessages.PORTAL_MODE_DESCRIPTION_MIRROR)
				: modeDescription(getType());
		UIElement element = localizedElement("set-mode", WormholesMessages.PORTAL_MENU_MODE_OPENER,
				arguments("description", description, "mode", currentModeLabel()),
				isMirrorMode() ? Material.COPPER_TORCH : modeIcon(getType()));
		element.setEnchanted(true);
		element.onLeftClick((e) -> FoliaScheduler.runEntity(Wormholes.instance, viewer, () ->
		{
			window.close();
			uiChooseMode(viewer);
		}));
		return element;
	}

	private Element directionElement(Window window, Player viewer)
	{
		UIElement element = localizedElement("set-direction", WormholesMessages.PORTAL_MENU_DIRECTION,
				arguments("direction", directionLabel(getDirection())), Material.COMPASS);
		element.onLeftClick((e) ->
		{
			window.close();
			uiChangeDirection(viewer);
		});
		return element;
	}

	private Element flipFaceElement(Window window, Player viewer)
	{
		UIElement element = localizedElement("flip-face", WormholesMessages.PORTAL_MENU_FLIP_FACE,
				arguments("up", directionLabel(getFrame().getUp())), Material.TARGET);
		element.onLeftClick((e) -> FoliaScheduler.runEntity(Wormholes.instance, viewer, () ->
		{
			setFrame(getFrame().flipNormal());
			Wormholes.effectManager.playNotificationSuccess(Wormholes.text().legacy(
					WormholesMessages.PORTAL_FACE_FLIPPED,
					arguments("portal", getName(), "direction", directionLabel(getDirection()))), getStructure().getCenter());
			window.close();
			uiOpenPortalMenu(viewer);
		}));
		return element;
	}

	private Element rotateCounterClockwiseElement(Window window, Player viewer)
	{
		UIElement element = localizedElement("rotate-counter-clockwise", WormholesMessages.PORTAL_MENU_ROTATE_COUNTERCLOCKWISE,
				arguments("up", directionLabel(getFrame().getUp())), Material.REPEATER);
		element.onLeftClick((e) -> FoliaScheduler.runEntity(Wormholes.instance, viewer, () ->
		{
			setFrame(getFrame().rotateCounterClockwise());
			Wormholes.effectManager.playNotificationSuccess(Wormholes.text().legacy(
					WormholesMessages.PORTAL_ROTATED_COUNTERCLOCKWISE,
					arguments("portal", getName())), getStructure().getCenter());
			window.close();
			uiOpenPortalMenu(viewer);
		}));
		return element;
	}

	private Element rotateClockwiseElement(Window window, Player viewer)
	{
		UIElement element = localizedElement("rotate-clockwise", WormholesMessages.PORTAL_MENU_ROTATE_CLOCKWISE,
				arguments("up", directionLabel(getFrame().getUp())), Material.LEVER);
		element.onLeftClick((e) -> FoliaScheduler.runEntity(Wormholes.instance, viewer, () ->
		{
			setFrame(getFrame().rotateClockwise());
			Wormholes.effectManager.playNotificationSuccess(Wormholes.text().legacy(
					WormholesMessages.PORTAL_ROTATED_CLOCKWISE,
					arguments("portal", getName())), getStructure().getCenter());
			window.close();
			uiOpenPortalMenu(viewer);
		}));
		return element;
	}

	private Element backToPortalMenuElement(Window window, Player viewer)
	{
		UIElement element = localizedElement("back-to-portal", WormholesMessages.PORTAL_MENU_BACK,
				MessageArgs.empty(), Material.ARROW);
		element.onLeftClick((e) -> FoliaScheduler.runEntity(Wormholes.instance, viewer, () ->
		{
			window.close();
			uiOpenPortalMenu(viewer);
		}));
		return element;
	}

	private Element projectionsElement(Window window, Player viewer)
	{
		UIElement element = new UIElement("toggle-projections");
		element.onLeftClick((e) ->
		{
			ProjectionMode previous = getProjectionMode();
			if(dimensionalPortalKind.isReceiverOnly())
			{
				notifySetting(viewer, WormholesMessages.PORTAL_PROJECTION_RECEIVER_INACTIVE);
				return;
			}
			setProjectionMode(previous.next());
			applyProjectionMode(element);
			window.updateInventory();
			if(previous != getProjectionMode())
			{
				notifySetting(viewer, WormholesMessages.PORTAL_PROJECTION_CHANGED,
						arguments("mode", projectionModeLabel(getProjectionMode())));
			}
		});
		applyProjectionMode(element);
		return element;
	}

	private void applyProjectionMode(Element element)
	{
		ProjectionMode mode = getProjectionMode();
		Wormholes.text().apply(element, mode == ProjectionMode.ON
				? WormholesMessages.PORTAL_MENU_PROJECTION_ON : WormholesMessages.PORTAL_MENU_PROJECTION_OFF);
		element.setEnchanted(mode.isEnchanted());
		element.setMaterial(new MaterialBlock(mode.getIcon()));
	}

	private Element settingsOpenerElement(Window window, Player viewer)
	{
		LinesKey key = isGateway() ? WormholesMessages.PORTAL_MENU_SETTINGS_GATEWAY : WormholesMessages.PORTAL_MENU_SETTINGS;
		MessageArgs arguments = isGateway()
				? arguments(
						"access", permissionModeLabel(getPermissionMode()),
						"send", localized(isOutgoingTraversalsEnabled() ? WormholesMessages.LABEL_ON : WormholesMessages.LABEL_OFF),
						"receive", localized(isIncomingTraversalsEnabled() ? WormholesMessages.LABEL_ON : WormholesMessages.LABEL_OFF),
						"depth", networkViewDepth,
						"entity", networkViewEntityIntervalTicks)
				: arguments(
						"access", permissionModeLabel(getPermissionMode()),
						"send", localized(isOutgoingTraversalsEnabled() ? WormholesMessages.LABEL_ON : WormholesMessages.LABEL_OFF),
						"receive", localized(isIncomingTraversalsEnabled() ? WormholesMessages.LABEL_ON : WormholesMessages.LABEL_OFF));
		UIElement element = localizedElement("portal-settings", key, arguments, Material.LEVER);
		element.onLeftClick((e) -> FoliaScheduler.runEntity(Wormholes.instance, viewer, () ->
		{
			window.close();
			uiOpenSettingsMenu(viewer);
		}));
		return element;
	}

	private Element settingsSyncElement(Window window, Player viewer)
	{
		UIElement element = new UIElement("settings-sync");
		element.onLeftClick((e) ->
		{
			boolean previous = isSettingsSyncEnabled();
			setSettingsSyncEnabled(!previous);
			applySettingsSyncElement(element);
			window.updateInventory();
			notifySetting(viewer, WormholesMessages.PORTAL_SETTINGS_SYNC_CHANGED,
					arguments("state", localized(isSettingsSyncEnabled() ? WormholesMessages.LABEL_ON : WormholesMessages.LABEL_OFF)));
		});
		applySettingsSyncElement(element);
		return element;
	}

	private void applySettingsSyncElement(Element element)
	{
		boolean enabled = isSettingsSyncEnabled();
		Wormholes.text().apply(element, WormholesMessages.PORTAL_MENU_SETTINGS_SYNC,
				arguments("state", localized(enabled ? WormholesMessages.LABEL_ON : WormholesMessages.LABEL_OFF)));
		element.setEnchanted(enabled);
		element.setMaterial(new MaterialBlock(enabled ? Material.SOUL_LANTERN : Material.LANTERN));
	}

	private Element permissionElement(Window window, Player viewer)
	{
		UIElement element = new UIElement("permission-mode");
		element.onLeftClick((e) ->
		{
			PortalPermissionMode previous = getPermissionMode();
			setPermissionMode(getPermissionMode().next());
			applyPermissionElement(element);
			window.updateInventory();
			if(previous != getPermissionMode())
			{
				notifySetting(viewer, WormholesMessages.PORTAL_ACCESS_CHANGED,
						arguments("mode", permissionModeLabel(getPermissionMode())));
			}
		});
		applyPermissionElement(element);
		return element;
	}

	private void applyPermissionElement(Element element)
	{
		PortalPermissionMode mode = getPermissionMode();
		Wormholes.text().apply(element, WormholesMessages.PORTAL_MENU_PERMISSION,
				arguments(
						"mode", permissionModeLabel(mode),
						"description", permissionModeDescription(mode),
						"node", getPermissionNode()));
		element.setEnchanted(mode == PortalPermissionMode.WHITELIST);
		element.setMaterial(new MaterialBlock(mode == PortalPermissionMode.WHITELIST ? Material.GOLDEN_HELMET : Material.IRON_HELMET));
	}

	private Element modeOption(PortalType target, Player p, Window window)
	{
		boolean current = getType() == target && !isMirrorMode();
		String label = portalTypeLabel(target);
		UIElement element = new UIElement("mode-" + target.name().toLowerCase());
		Wormholes.text().apply(element,
				current ? WormholesMessages.PORTAL_MENU_MODE_OPTION_SELECTED : WormholesMessages.PORTAL_MENU_MODE_OPTION_AVAILABLE,
				arguments("mode", label, "description", modeDescription(target)));
		element.setMaterial(new MaterialBlock(modeIcon(target)));
		element.setEnchanted(current);
		element.onLeftClick((e) -> FoliaScheduler.runEntity(Wormholes.instance, p, () ->
		{
			boolean changed = false;
			if(isMirrorMode())
			{
				setMirrorMode(false);
				changed = true;
			}
			if(getType() != target)
			{
				setType(target);
				changed = true;
			}
			if(changed)
			{
				Wormholes.effectManager.playNotificationSuccess(Wormholes.text().legacy(
						WormholesMessages.PORTAL_MODE_CHANGED,
						arguments("portal", getName(), "mode", label)), getStructure().getCenter());
			}
			window.close();
		}));
		return element;
	}

	private Element mirrorModeOption(Player p, Window window)
	{
		UIElement element = new UIElement("mode-mirror");
		element.onLeftClick((e) -> FoliaScheduler.runEntity(Wormholes.instance, p, () ->
		{
			if(!isMirrorMode())
			{
				setMirrorMode(true);
				Wormholes.effectManager.playNotificationSuccess(Wormholes.text().legacy(
						WormholesMessages.PORTAL_MODE_CHANGED,
						arguments("portal", getName(), "mode", localized(WormholesMessages.PORTAL_LABEL_MIRROR))),
						getStructure().getCenter());
			}
			window.close();
		}));
		element.onRightClick((e) -> rotateMirrorImage(element, window, p, getMirrorRotation().clockwiseFor(getFrame())));
		element.onShiftRightClick((e) -> rotateMirrorImage(element, window, p, getMirrorRotation().counterClockwiseFor(getFrame())));
		applyMirrorModeOption(element);
		return element;
	}

	private void applyMirrorModeOption(Element element)
	{
		boolean current = isMirrorMode();
		Wormholes.text().apply(element,
				current ? WormholesMessages.PORTAL_MENU_MIRROR_SELECTED : WormholesMessages.PORTAL_MENU_MIRROR_AVAILABLE);
		element.setMaterial(new MaterialBlock(Material.COPPER_TORCH));
		element.setEnchanted(current);
		if(!current)
		{
			return;
		}
		KList<String> lore = element.getLore();
		lore.add(Wormholes.text().legacy(WormholesMessages.PORTAL_MENU_MIRROR_ROTATION,
				arguments("degrees", getMirrorRotation().getDegrees())));
		if(MirrorRotation.supportsQuarterTurns(getFrame()))
		{
			lore.add(Wormholes.text().legacy(WormholesMessages.PORTAL_MENU_MIRROR_ROTATE_CLOCKWISE));
			lore.add(Wormholes.text().legacy(WormholesMessages.PORTAL_MENU_MIRROR_ROTATE_COUNTERCLOCKWISE));
			return;
		}
		lore.addAll(Wormholes.text().legacyLines(WormholesMessages.PORTAL_MENU_MIRROR_FLIP));
	}

	private void rotateMirrorImage(Element element, Window window, Player viewer, MirrorRotation rotation)
	{
		if(!isMirrorMode())
		{
			notifySetting(viewer, WormholesMessages.PORTAL_MIRROR_SELECT_FIRST);
			return;
		}
		setMirrorRotation(rotation);
		applyMirrorModeOption(element);
		window.updateInventory();
		notifySetting(viewer, WormholesMessages.PORTAL_MIRROR_ROTATION_CHANGED,
				arguments("degrees", getMirrorRotation().getDegrees()));
	}

	private void notifySetting(Player viewer, TextKey message)
	{
		notifySetting(viewer, message, MessageArgs.empty());
	}

	private void notifySetting(Player viewer, TextKey message, MessageArgs messageArguments)
	{
		if(viewer == null)
		{
			return;
		}
		Wormholes.effectManager.playNotificationSuccess(Wormholes.text().legacy(
				WormholesMessages.PORTAL_SETTING_NOTIFICATION,
				arguments(
						"portal", getName(),
						"message", Wormholes.text().plain(message, messageArguments))), viewer);
	}

	private static UIElement localizedElement(String id, LinesKey key, MessageArgs arguments, Material material)
	{
		UIElement element = new UIElement(id);
		element.setMaterial(new MaterialBlock(material));
		Wormholes.text().apply(element, key, arguments);
		return element;
	}

	private static MessageArgs arguments(Object... nameValuePairs)
	{
		if(nameValuePairs.length % 2 != 0)
		{
			throw new IllegalArgumentException("Localization arguments require name-value pairs");
		}
		MessageArgument[] arguments = new MessageArgument[nameValuePairs.length / 2];
		for(int index = 0; index < nameValuePairs.length; index += 2)
		{
			String name = Objects.requireNonNull((String) nameValuePairs[index], "Localization argument name");
			Object value = Objects.requireNonNull(nameValuePairs[index + 1], "Localization argument value");
			arguments[index / 2] = MessageArgument.untrusted(name, value);
		}
		return WormholesLocalization.args(arguments);
	}

	private static String localized(TextKey key)
	{
		return Wormholes.text().plain(key);
	}

	private static boolean isCancelInput(String text)
	{
		return text.equalsIgnoreCase(localized(WormholesMessages.PORTAL_INPUT_CANCEL));
	}

	private static String directionLabel(Direction direction)
	{
		TextKey key = switch(direction)
		{
			case U -> WormholesMessages.PORTAL_LABEL_DIRECTION_UP;
			case D -> WormholesMessages.PORTAL_LABEL_DIRECTION_DOWN;
			case N -> WormholesMessages.PORTAL_LABEL_DIRECTION_NORTH;
			case S -> WormholesMessages.PORTAL_LABEL_DIRECTION_SOUTH;
			case E -> WormholesMessages.PORTAL_LABEL_DIRECTION_EAST;
			case W -> WormholesMessages.PORTAL_LABEL_DIRECTION_WEST;
		};
		return localized(key);
	}

	private static String travelModeLabel(PortalTravelMode mode)
	{
		TextKey key = switch(mode)
		{
			case BOTH -> WormholesMessages.PORTAL_LABEL_BOTH_WAYS;
			case OUTBOUND -> WormholesMessages.PORTAL_LABEL_OUTBOUND_ONLY;
			case INBOUND -> WormholesMessages.PORTAL_LABEL_INBOUND_ONLY;
			case LOCKED -> WormholesMessages.PORTAL_LABEL_LOCKED;
		};
		return localized(key);
	}

	private static String networkViewQualityLabel(NetworkViewQuality quality)
	{
		TextKey key = switch(quality)
		{
			case STANDARD -> WormholesMessages.PORTAL_LABEL_STANDARD;
			case PERFORMANCE -> WormholesMessages.PORTAL_LABEL_PERFORMANCE;
			case BALANCED -> WormholesMessages.PORTAL_LABEL_BALANCED;
			case CINEMATIC -> WormholesMessages.PORTAL_LABEL_CINEMATIC;
			case CUSTOM -> WormholesMessages.PORTAL_LABEL_CUSTOM;
		};
		return localized(key);
	}

	private static String portalTypeLabel(PortalType type)
	{
		TextKey key = switch(type)
		{
			case PORTAL -> WormholesMessages.PORTAL_LABEL_PORTAL;
			case WORMHOLE -> WormholesMessages.PORTAL_LABEL_WORMHOLE;
			case GATEWAY -> WormholesMessages.PORTAL_LABEL_GATEWAY;
			case RTP -> WormholesMessages.PORTAL_LABEL_RTP;
		};
		return localized(key);
	}

	private static String projectionModeLabel(ProjectionMode mode)
	{
		return localized(mode == ProjectionMode.ON ? WormholesMessages.LABEL_ON : WormholesMessages.LABEL_OFF);
	}

	private static String permissionModeLabel(PortalPermissionMode mode)
	{
		return localized(mode == PortalPermissionMode.WHITELIST
				? WormholesMessages.PORTAL_LABEL_WHITELIST : WormholesMessages.PORTAL_LABEL_BLACKLIST);
	}

	private static String permissionModeDescription(PortalPermissionMode mode)
	{
		return localized(mode == PortalPermissionMode.WHITELIST
				? WormholesMessages.PORTAL_PERMISSION_DESCRIPTION_WHITELIST
				: WormholesMessages.PORTAL_PERMISSION_DESCRIPTION_BLACKLIST);
	}

	private String currentModeLabel()
	{
		return isMirrorMode() ? localized(WormholesMessages.PORTAL_LABEL_MIRROR) : portalTypeLabel(getType());
	}

	private static Material modeIcon(PortalType type)
	{
		return switch(type)
		{
			case GATEWAY -> Material.END_CRYSTAL;
			case WORMHOLE -> Material.ENDER_PEARL;
			case PORTAL -> Material.ENDER_EYE;
			case RTP -> Material.COMPASS;
		};
	}

	private String modeDescription(PortalType type)
	{
		TextKey key = switch(type)
		{
			case GATEWAY -> WormholesMessages.PORTAL_MODE_DESCRIPTION_GATEWAY;
			case WORMHOLE -> WormholesMessages.PORTAL_MODE_DESCRIPTION_WORMHOLE;
			case PORTAL -> WormholesMessages.PORTAL_MODE_DESCRIPTION_PORTAL;
			case RTP -> WormholesMessages.PORTAL_MODE_DESCRIPTION_RTP;
		};
		return localized(key);
	}

	private String rtpAllocationLabel(RtpAllocationMode mode)
	{
		return localized(mode == RtpAllocationMode.SHARED
				? WormholesMessages.PORTAL_LABEL_SHARED : WormholesMessages.PORTAL_LABEL_PER_PLAYER);
	}

	private String rtpRotationLabel(RtpRotationMode mode)
	{
		TextKey key = switch(mode)
		{
			case STATIC -> WormholesMessages.RTP_ROTATION_STATIC;
			case TIMED -> WormholesMessages.RTP_ROTATION_TIMED;
			case ON_TRAVERSAL -> WormholesMessages.RTP_ROTATION_TRIP;
		};
		return localized(key);
	}

	private String rtpRotationSummary(RtpSettings settings)
	{
		RtpSettings requiredSettings = Objects.requireNonNull(settings, "settings");
		if(requiredSettings.getAllocationMode() == RtpAllocationMode.PER_PLAYER)
		{
			return Wormholes.text().plain(WormholesMessages.PORTAL_RTP_ROTATION_PRIVATE,
					arguments("duration", formatRtpDuration(requiredSettings.getCycleDurationMillis())));
		}
		return rtpRotationLabel(requiredSettings.getRotationMode());
	}

	private String formatRtpDuration(long durationMillis)
	{
		if(durationMillis % 3_600_000L == 0L)
		{
			return Wormholes.text().plain(WormholesMessages.RTP_DURATION_HOURS,
					arguments("value", durationMillis / 3_600_000L));
		}
		if(durationMillis % 60_000L == 0L)
		{
			return Wormholes.text().plain(WormholesMessages.RTP_DURATION_MINUTES,
					arguments("value", durationMillis / 60_000L));
		}
		if(durationMillis % 1_000L == 0L)
		{
			return Wormholes.text().plain(WormholesMessages.RTP_DURATION_SECONDS,
					arguments("value", durationMillis / 1_000L));
		}
		return Wormholes.text().plain(WormholesMessages.RTP_DURATION_DECIMAL_SECONDS,
				arguments("value", String.format(Locale.ROOT, "%.1f", Double.valueOf(durationMillis / 1_000.0D))));
	}

	@Override
	public void uiChooseDestination(Player p)
	{
		if(type == PortalType.RTP)
		{
			notifySetting(p, WormholesMessages.PORTAL_RTP_CANNOT_LINK);
			return;
		}
		if(dimensionalPortalKind.isManagedPortal())
		{
			notifySetting(p, WormholesMessages.PORTAL_DIMENSIONAL_LINK_MANAGED);
			return;
		}
		//@builder
		Window window = new UIWindow(Wormholes.instance, p)
				.setTitle(getRouter(true))
				.setResolution(WindowResolution.W9_H6)
				.setDecorator(new UIPaneDecorator(Material.GRAY_STAINED_GLASS_PANE))
				.onClosed((w) -> FoliaScheduler.runEntity(Wormholes.instance, p, () -> uiOpenPortalMenu(p)));
		//@done
		int pos = 0;

		List<ILocalPortal> localTargets = new ArrayList<>();
		for(ILocalPortal i : Wormholes.portalManager.getLocalPortals())
		{
			if(i.getId().equals(getId()) || i.getType() == PortalType.RTP)
			{
				continue;
			}

			if(i.isGateway() != isGateway())
			{
				continue;
			}

			if(!i.getDimensionalPortalKind().isGenericDestination())
			{
				continue;
			}

			if(i.getStructure() == null || i.getStructure().getWorld() == null || i.getStructure().getCenter() == null)
			{
				continue;
			}

			localTargets.add(i);
		}
		localTargets.sort(Comparator
				.comparing((ILocalPortal target) -> !isLinkedToLocal(target))
				.thenComparingDouble(this::localDestinationDistanceSquared)
				.thenComparing(target -> target.getStructure().getWorld().getName(), String.CASE_INSENSITIVE_ORDER)
				.thenComparing(ILocalPortal::getName, String.CASE_INSENSITIVE_ORDER));

		for(ILocalPortal target : localTargets)
		{
			Location targetCenter = target.getStructure().getCenter();
			UIElement targetElement = localizedElement(
					"portal-" + pos,
					WormholesMessages.PORTAL_MENU_LOCAL_DESTINATION,
					arguments(
							"portal", target.getName(),
							"x", targetCenter.getBlockX(),
							"y", targetCenter.getBlockY(),
							"z", targetCenter.getBlockZ(),
							"world", target.getStructure().getWorld().getName(),
							"direction", directionLabel(target.getDirection())),
					Material.ENDER_PEARL);
			targetElement.setEnchanted(isLinkedToLocal(target));
			targetElement.onLeftClick((e) -> FoliaScheduler.runEntity(Wormholes.instance, p, () -> {
				window.close();

				if(isLinkedToLocal(target))
				{
					unlink();
					Wormholes.effectManager.playNotificationSuccess(Wormholes.text().legacy(
							WormholesMessages.PORTAL_UNLINKED,
							arguments("portal", getName(), "destination", target.getName())), getStructure().getCenter());
				}
				else
				{
					setDestination(target);
					Wormholes.effectManager.playNotificationSuccess(Wormholes.text().legacy(
							WormholesMessages.PORTAL_LINKED,
							arguments("portal", getName(), "destination", target.getName())), getStructure().getCenter());
				}
			}));
			//@builder
			window.setElement(window.getPosition(pos), window.getRow(pos), targetElement);
			//@done
			pos++;
		}

		if(isGateway() && Wormholes.remotePortalRegistry != null)
		{
			List<RemotePortal> remoteTargets = new ArrayList<>();
			for(RemotePortal i : Wormholes.remotePortalRegistry.all())
			{
				if(i.getType() != PortalType.GATEWAY)
				{
					continue;
				}

				remoteTargets.add(i);
			}
			remoteTargets.sort(Comparator
					.comparing((RemotePortal target) -> !isLinkedToRemote(target))
					.thenComparing(target -> !target.isOpen())
					.thenComparing(target -> target.getServer().getName(), String.CASE_INSENSITIVE_ORDER)
					.thenComparing(RemotePortal::getName, String.CASE_INSENSITIVE_ORDER));

			for(RemotePortal target : remoteTargets)
			{
				boolean linked = isLinkedToRemote(target);
				UIElement targetElement = localizedElement(
						"remote-portal-" + pos,
						WormholesMessages.PORTAL_MENU_REMOTE_DESTINATION,
						arguments(
								"portal", target.getName(),
								"server", target.getServer().getName(),
								"x", target.getOrigin().getBlockX(),
								"y", target.getOrigin().getBlockY(),
								"z", target.getOrigin().getBlockZ(),
								"world", target.getServer().getWorld(),
								"direction", directionLabel(target.getDirection()),
								"state", localized(target.isOpen() ? WormholesMessages.LABEL_OPEN : WormholesMessages.LABEL_CLOSED)),
						Material.END_CRYSTAL);
				targetElement.setEnchanted(linked);
				targetElement.onLeftClick((e) -> FoliaScheduler.runEntity(Wormholes.instance, p, () -> {
					window.close();

					if(isLinkedToRemote(target))
					{
						unlink();
						Wormholes.effectManager.playNotificationSuccess(Wormholes.text().legacy(
								WormholesMessages.PORTAL_UNLINKED,
								arguments("portal", getName(), "destination", target.getName())), getStructure().getCenter());
					}
					else
					{
						setDestination(target);
						Wormholes.effectManager.playNotificationSuccess(Wormholes.text().legacy(
								WormholesMessages.PORTAL_LINKED_REMOTE,
								arguments(
										"portal", getName(),
										"destination", target.getName(),
										"server", target.getServer().getName())), getStructure().getCenter());
					}
				}));
				//@builder
				window.setElement(window.getPosition(pos), window.getRow(pos), targetElement);
				//@done
				pos++;
			}
		}

		window.setVisible(true);
	}

	private boolean isLinkedToLocal(ILocalPortal target)
	{
		return hasTunnel() && getTunnel().getDestination().getId().equals(target.getId());
	}

	private double localDestinationDistanceSquared(ILocalPortal target)
	{
		Location source = getStructure().getCenter();
		Location destination = target.getStructure().getCenter();
		if(source == null || destination == null || source.getWorld() == null || !source.getWorld().equals(destination.getWorld()))
		{
			return Double.MAX_VALUE;
		}
		return source.distanceSquared(destination);
	}

	private boolean isLinkedToRemote(RemotePortal target)
	{
		if(!(tunnel instanceof UniversalTunnel universal))
		{
			return false;
		}

		return target.getServer().getName().equals(universal.getServerName())
			&& target.getId().equals(universal.getDestinationId());
	}

	@Override
	public void uiChangeName(Player p)
	{
		p.closeInventory();
		WormholesAudience.sendMessage(p, Wormholes.text().component(
				WormholesMessages.PORTAL_PROMPT_NAME,
				arguments("cancel", localized(WormholesMessages.PORTAL_INPUT_CANCEL))));
		Wormholes.awaitChatInput(p, (text) -> {
			if (text == null || isCancelInput(text)) {
				uiOpenPortalMenu(p);
				return;
			}
			setName(text);
			uiOpenPortalMenu(p);
		});
	}

	@Override
	public String getRouter(boolean dark)
	{
		return getRouter(dark, null);
	}

	@Override
	public String getRouter(boolean dark, IPortal source)
	{
		String str = "";

		if(source != null)
		{
			str += (dark ? ChatColor.GRAY : ChatColor.YELLOW) + "" + ChatColor.BOLD + source.getName();
			str += ChatColor.GRAY + " -> ";
		}

		str += (dark ? ChatColor.BLACK : ChatColor.GOLD) + "" + ChatColor.BOLD + getName();

		if(hasTunnel())
		{
			str += ChatColor.GRAY + " -> ";
			str += (dark ? ChatColor.GRAY : ChatColor.GRAY) + "" + ChatColor.BOLD + getTunnel().getDestination().getName();
		}

		return str;
	}

	@Override
	public void uiChangeDirection(Player p)
	{
		for(Component line : Wormholes.text().components(WormholesMessages.PORTAL_PROMPT_DIRECTION))
		{
			WormholesAudience.sendMessage(p, line);
		}
		chosenDirection = Direction.closest(p.getLocation().getDirection());
		chosenLook = p.getLocation().getDirection();
		directionChanger = p;

		new AR()
		{
			@Override
			public void run()
			{
				if(directionChanger == null)
				{
					cancel();
					return;
				}

				FoliaScheduler.runEntity(Wormholes.instance, p, () ->
				{
					if(directionChanger == null)
					{
						return;
					}

					chosenDirection = Direction.closest(p.getLocation().getDirection());
					chosenLook = p.getLocation().getDirection();
					sendShortTitle(p, ChatColor.GRAY + "" + ChatColor.BOLD + directionLabel(chosenDirection));
				});
			}
		};
	}

	@EventHandler
	public void on(PlayerInteractEvent e)
	{
		if(directionChanger == null)
		{
			return;
		}

		if(directionChanger.equals(e.getPlayer()))
		{
			if(e.getAction().equals(Action.LEFT_CLICK_AIR) || e.getAction().equals(Action.LEFT_CLICK_BLOCK))
			{
				e.setCancelled(true);

				boolean cancelled = e.getPlayer().isSneaking();
				if(!cancelled)
				{
					if(chosenDirection == null)
					{
						chosenDirection = Direction.closest(e.getPlayer().getLocation().getDirection());
						chosenLook = e.getPlayer().getLocation().getDirection();
					}
					setFrame(PortalFrame.fromDirectionAndLook(chosenDirection, chosenLook));
					Wormholes.effectManager.playNotificationSuccess(Wormholes.text().legacy(
							WormholesMessages.PORTAL_DIRECTION_CHANGED,
							arguments("portal", getName(), "direction", directionLabel(getDirection()))), getStructure().getCenter());
				}

				directionChanger = null;
				chosenDirection = null;
				chosenLook = null;
				WormholesAudience.sendMessage(e.getPlayer(), Wormholes.text().component(
						cancelled ? WormholesMessages.PORTAL_DIRECTION_CANCELLED : WormholesMessages.PORTAL_DIRECTION_SET));
			}
		}
	}

	@Override
	public boolean isGateway()
	{
		return getType().equals(PortalType.GATEWAY);
	}

	@Override
	public boolean supportsProjections()
	{
		return true;
	}

	@Override
	public boolean isProjecting()
	{
		return projectionMode == ProjectionMode.ON;
	}

	@Override
	public ProjectionMode getProjectionMode()
	{
		return projectionMode;
	}

	@Override
	public void setProjectionMode(ProjectionMode mode)
	{
		ProjectionMode normalized = mode == null ? ProjectionMode.ON : mode;
		if(dimensionalPortalKind.isReceiverOnly())
		{
			normalized = ProjectionMode.OFF;
		}
		if(this.projectionMode == normalized)
		{
			return;
		}
		this.projectionMode = normalized;
		invalidateProjection();
		save();
		broadcastSettingsIfEnabled();
		refreshOpenMenusUnlessApplyingRemote();
	}

	@Override
	public boolean isMirrorMode()
	{
		return mirrorMode;
	}

	@Override
	public void setMirrorMode(boolean mirrorMode)
	{
		if(mirrorMode && type == PortalType.RTP)
		{
			setType(PortalType.PORTAL);
		}
		boolean normalized = mirrorMode && !dimensionalPortalKind.isManagedPortal();
		if(this.mirrorMode == normalized)
		{
			return;
		}
		this.mirrorMode = normalized;
		invalidateProjection();
		save();
		if(Wormholes.portalSyncService != null)
		{
			Wormholes.portalSyncService.broadcastRemoteCache(this);
		}
		broadcastSettingsIfEnabled();
		refreshOpenMenusUnlessApplyingRemote();
	}

	@Override
	public MirrorRotation getMirrorRotation()
	{
		return mirrorRotation;
	}

	@Override
	public void setMirrorRotation(MirrorRotation rotation)
	{
		MirrorRotation normalized = (rotation == null ? MirrorRotation.DEGREES_0 : rotation).coherentFor(getFrame());
		if(mirrorRotation == normalized)
		{
			return;
		}
		mirrorRotation = normalized;
		invalidateProjection();
		save();
		broadcastSettingsIfEnabled();
		refreshOpenMenusUnlessApplyingRemote();
	}

	private boolean normalizeMirrorRotationForFrame()
	{
		MirrorRotation normalized = (mirrorRotation == null ? MirrorRotation.DEGREES_0 : mirrorRotation).coherentFor(getFrame());
		if(mirrorRotation == normalized)
		{
			return false;
		}
		mirrorRotation = normalized;
		return true;
	}

	static ProjectionMode resolveProjectionMode(JSONObject j)
	{
		return "OFF".equalsIgnoreCase(j.optString("projectionMode", ProjectionMode.ON.name()))
				? ProjectionMode.OFF : ProjectionMode.ON;
	}

	static boolean resolveMirrorMode(JSONObject j)
	{
		if(j.has("mirrorMode"))
		{
			return j.getBoolean("mirrorMode");
		}
		return "MIRROR".equalsIgnoreCase(j.optString("projectionMode", ""));
	}

	static MirrorRotation resolveMirrorRotation(JSONObject j)
	{
		return MirrorRotation.fromDegrees(j.optInt("mirrorRotationDegrees", 0));
	}

	private static PortalPermissionMode resolvePermissionMode(JSONObject j)
	{
		if(j.has("permissionMode"))
		{
			return PortalPermissionMode.fromName(j.getString("permissionMode"));
		}
		return PortalPermissionMode.BLACKLIST;
	}

	@Override
	public PortalPermissionMode getPermissionMode()
	{
		return permissionMode;
	}

	@Override
	public void setPermissionMode(PortalPermissionMode mode)
	{
		PortalPermissionMode normalized = mode == null ? PortalPermissionMode.BLACKLIST : mode;
		if(permissionMode == normalized)
		{
			return;
		}
		permissionMode = normalized;
		save();
		broadcastSettingsIfEnabled();
		refreshOpenMenusUnlessApplyingRemote();
	}

	@Override
	public String getPermissionNode()
	{
		return "wormholes.portal." + sanitizePermissionName(getName());
	}

	@Override
	public boolean isOutgoingTraversalsEnabled()
	{
		return outgoingTraversalsEnabled;
	}

	@Override
	public void setOutgoingTraversalsEnabled(boolean enabled)
	{
		boolean normalized = dimensionalPortalKind == DimensionalPortalKind.NETHER
				|| dimensionalPortalKind == DimensionalPortalKind.END_SOURCE
				|| (!dimensionalPortalKind.isReceiverOnly() && enabled);
		if(outgoingTraversalsEnabled == normalized)
		{
			return;
		}
		outgoingTraversalsEnabled = normalized;
		save();
		broadcastSettingsIfEnabled();
		refreshOpenMenusUnlessApplyingRemote();
	}

	@Override
	public boolean isIncomingTraversalsEnabled()
	{
		return incomingTraversalsEnabled;
	}

	@Override
	public void setIncomingTraversalsEnabled(boolean enabled)
	{
		boolean normalized = dimensionalPortalKind == DimensionalPortalKind.NETHER
				|| dimensionalPortalKind.isReceiverOnly()
				|| (dimensionalPortalKind != DimensionalPortalKind.END_SOURCE && enabled);
		if(incomingTraversalsEnabled == normalized)
		{
			return;
		}
		incomingTraversalsEnabled = normalized;
		save();
		broadcastSettingsIfEnabled();
		refreshOpenMenusUnlessApplyingRemote();
	}

		@Override
		public int getNetworkViewDepth()
		{
			return networkViewDepth;
		}

		@Override
		public void setNetworkViewDepth(int depth)
		{
			int normalized = clampNetworkViewInt(depth, 1, 128);
			if(networkViewDepth == normalized)
			{
				return;
			}
			networkViewDepth = normalized;
			networkViewSettingsChanged();
		}

		@Override
		public int getNetworkViewLateralPad()
		{
			return networkViewLateralPad;
		}

		@Override
		public void setNetworkViewLateralPad(int lateralPad)
		{
			int normalized = clampNetworkViewInt(lateralPad, 0, 64);
			if(networkViewLateralPad == normalized)
			{
				return;
			}
			networkViewLateralPad = normalized;
			networkViewSettingsChanged();
		}

		@Override
		public int getNetworkViewHeartbeatTicks()
		{
			return networkViewHeartbeatTicks;
		}

		@Override
		public void setNetworkViewHeartbeatTicks(int ticks)
		{
			int normalized = clampNetworkViewInt(ticks, 2, 600);
			if(networkViewHeartbeatTicks == normalized)
			{
				return;
			}
			networkViewHeartbeatTicks = normalized;
			networkViewSettingsChanged();
		}

		@Override
		public int getNetworkViewEntityIntervalTicks()
		{
			return networkViewEntityIntervalTicks;
		}

		@Override
		public void setNetworkViewEntityIntervalTicks(int ticks)
		{
			int normalized = clampNetworkViewInt(ticks, 2, 600);
			if(networkViewEntityIntervalTicks == normalized)
			{
				return;
			}
			networkViewEntityIntervalTicks = normalized;
			networkViewSettingsChanged();
		}

		@Override
		public int getNetworkViewUnsubscribeGraceSeconds()
		{
			return networkViewUnsubscribeGraceSeconds;
		}

		@Override
		public void setNetworkViewUnsubscribeGraceSeconds(int seconds)
		{
			int normalized = clampNetworkViewInt(seconds, 5, 600);
			if(networkViewUnsubscribeGraceSeconds == normalized)
			{
				return;
			}
			networkViewUnsubscribeGraceSeconds = normalized;
			networkViewSettingsChanged();
		}

		@Override
		public String getNetworkViewFallbackBlock()
		{
			return networkViewFallbackBlock;
		}

		@Override
		public void setNetworkViewFallbackBlock(String blockState)
		{
			String normalized = normalizeNetworkViewFallbackBlock(blockState);
			if(networkViewFallbackBlock.equals(normalized))
			{
				return;
			}
			networkViewFallbackBlock = normalized;
			networkViewSettingsChanged();
		}

		private void networkViewSettingsChanged()
		{
			save();
			if(Wormholes.viewServer != null)
			{
				Wormholes.viewServer.refreshPortal(this);
			}
			if(Wormholes.projectionManager != null)
			{
				Wormholes.projectionManager.removeProjector(this);
			}
			broadcastSettingsIfEnabled();
			refreshOpenMenusUnlessApplyingRemote();
		}

		private void refreshOpenMenusUnlessApplyingRemote()
		{
			if(PortalSyncService.isApplyingRemote())
			{
				return;
			}
			refreshOpenMenus();
		}

		public void refreshOpenMenus()
		{
			if(openMenus.isEmpty())
			{
				return;
			}
			for(Map.Entry<UUID, UIWindow> entry : openMenus.entrySet())
			{
				UUID viewerId = entry.getKey();
				UIWindow window = entry.getValue();
				Player viewer = Bukkit.getPlayer(viewerId);
				if(viewer == null || !viewer.isOnline() || !window.isVisible())
				{
					openMenus.remove(viewerId);
					continue;
				}
				FoliaScheduler.runEntity(Wormholes.instance, viewer, () ->
				{
					if(!window.isVisible())
					{
						openMenus.remove(viewerId);
						return;
					}
					rebuildPortalMenuElements(window, viewer);
				});
			}
		}

		public boolean isSettingsSyncEnabled()
		{
			return settingsSyncEnabled;
		}

		public void setSettingsSyncEnabled(boolean enabled)
		{
			if(settingsSyncEnabled == enabled)
			{
				return;
			}
			settingsSyncEnabled = enabled;
			save();
			if(isGateway() && Wormholes.portalSyncService != null && !PortalSyncService.isApplyingRemote())
			{
				Wormholes.portalSyncService.broadcastSettingsToggle(this);
			}
			refreshOpenMenusUnlessApplyingRemote();
		}

		private void broadcastSettingsIfEnabled()
		{
			if(!settingsSyncEnabled)
			{
				return;
			}
			if(PortalSyncService.isApplyingRemote())
			{
				return;
			}
			if(Wormholes.portalSyncService == null)
			{
				return;
			}
			Wormholes.portalSyncService.broadcastSettings(this);
		}

		private static int readNetworkViewInt(JSONObject j, String key, int defaultValue, int min, int max)
		{
			int value = j.has(key) ? j.optInt(key, defaultValue) : defaultValue;
			return clampNetworkViewInt(value, min, max);
		}

		private static int clampNetworkViewInt(int value, int min, int max)
		{
			return Math.max(min, Math.min(max, value));
		}

		private static String normalizeNetworkViewFallbackBlock(String blockState)
		{
			String normalized = blockState == null || blockState.isBlank() ? DEFAULT_NETWORK_VIEW_FALLBACK_BLOCK : blockState.trim();
			try
			{
				BlockData data = Bukkit.createBlockData(normalized);
				return data.getAsString();
			}
			catch(IllegalArgumentException e)
			{
				return DEFAULT_NETWORK_VIEW_FALLBACK_BLOCK;
			}
		}

		static String sanitizePermissionName(String name)
		{
		String source = name == null || name.isBlank() ? "unnamed" : name.toLowerCase(Locale.ROOT);
		StringBuilder builder = new StringBuilder(source.length());
		boolean previousSeparator = false;
		for(int i = 0; i < source.length(); i++)
		{
			char c = source.charAt(i);
			boolean allowed = (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '.' || c == '-' || c == '_';
			if(allowed)
			{
				builder.append(c);
				previousSeparator = false;
				continue;
			}
			if(!previousSeparator)
			{
				builder.append('_');
				previousSeparator = true;
			}
		}

		String sanitized = trimPermissionSeparators(builder.toString());
		return sanitized.isEmpty() ? "unnamed" : sanitized;
	}

	private static String trimPermissionSeparators(String value)
	{
		int start = 0;
		int end = value.length();
		while(start < end && value.charAt(start) == '_')
		{
			start++;
		}
		while(end > start && value.charAt(end - 1) == '_')
		{
			end--;
		}
		return value.substring(start, end);
	}

	private void syncGatewayTickets()
	{
		if(Wormholes.viewServer != null)
		{
			Wormholes.viewServer.syncGatewayTickets();
		}
	}

	private void invalidateProjection()
	{
		if(Wormholes.projectionManager != null)
		{
			Wormholes.projectionManager.removeProjector(this);
		}
	}

	@Override
	public AxisAlignedBB getView()
	{
		if(view == null || viewRange != Settings.PROJECTION_RANGE)
		{
			view = computeView();
		}
		return view;
	}

	private AxisAlignedBB computeView()
	{
		double range = Settings.PROJECTION_RANGE;
		viewRange = range;
		Vector pad = new Vector(-range, -range, -range);
		Vector padPositive = new Vector(range, range, range);
		return new AxisAlignedBB(getStructure().getArea().min().add(pad), getStructure().getArea().max().add(padPositive));
	}

	@Override
	public UUID getOwner()
	{
		return owner;
	}

	@Override
	public void setOwner(UUID owner)
	{
		this.owner = owner;
	}

	private boolean ensureCanManagePortal(Player player)
	{
		if(player == null)
		{
			return false;
		}
		boolean administrator = player.isOp() || player.hasPermission("wormholes.admin");
		UUID playerId = player.getUniqueId();
		if(PortalAccessPolicy.canManage(getId(), getOwner(), playerId, administrator))
		{
			return true;
		}
		WormholesAudience.sendActionBar(player, Wormholes.text().component(WormholesMessages.PORTAL_EDIT_DENIED));
		player.closeInventory();
		return false;
	}

	@Override
	public boolean isSelfOwned()
	{
		return getOwner().equals(getId());
	}

	@Override
	public void setSelfOwned()
	{
		setOwner(getId());
	}

	@Override
	public boolean isRemote()
	{
		return false;
	}

	@Override
	public World getWorld()
	{
		return getStructure().getWorld();
	}

	@Override
	public Location getCenter()
	{
		return getStructure().getCenter();
	}

	@Override
	public AxisAlignedBB getArea()
	{
		return getStructure().getArea();
	}

	@Override
	public void save()
	{
		if(destructionStarted.get())
		{
			return;
		}
		dirtyGeneration.incrementAndGet();
	}

	@Override
	public boolean needsSaving()
	{
		return !saveInFlight.get() && dirtyGeneration.get() != savedGeneration;
	}

	@Override
	public void saveNow() throws IOException
	{
		try
		{
			if(destructionStarted.get())
			{
				return;
			}
			doSave();
		}
		finally
		{
			saveInFlight.set(false);
		}
	}

	private void doSave() throws IOException
	{
		synchronized(persistenceLock)
		{
			if(destructionStarted.get())
			{
				return;
			}
			long generation = dirtyGeneration.get();
			File f = Wormholes.portalManager.getSaveFile(getId());
			f.getParentFile().mkdirs();
			VIO.writeAll(f, toJSON().toString(2));
			savedGeneration = generation;
			Wormholes.v("Saved Portal " + getId().toString() + " (" + getName() + ")");
		}
	}

	@Override
	public void willSave()
	{
		saveInFlight.compareAndSet(false, true);
	}

	@Override
	public void setName(String name)
	{
		super.setName(name);
		save();
	}

	@Override
	public void deleteData()
	{
		synchronized(persistenceLock)
		{
			File f = Wormholes.portalManager.getSaveFile(getId());
			try
			{
				Files.deleteIfExists(f.toPath());
			}
			catch(IOException e)
			{
				Wormholes.instance.getLogger().log(Level.WARNING, "Could not delete portal data for " + getId(), e);
				return;
			}
			deleteDirectoryIfEmpty(f.getParentFile());
			deleteDirectoryIfEmpty(f.getParentFile().getParentFile());
		}
	}

	private static void deleteDirectoryIfEmpty(File directory)
	{
		File[] contents = directory == null ? null : directory.listFiles();
		if(contents != null && contents.length == 0)
		{
			directory.delete();
		}
	}

	static UUID resolveOptionalUuid(String value)
	{
		if(value == null || value.isBlank())
		{
			return null;
		}
		try
		{
			return UUID.fromString(value);
		}
		catch(IllegalArgumentException ignored)
		{
			return null;
		}
	}

	private static void sendShortTitle(Player player, String legacy)
	{
		Component subtitle = LegacyComponentSerializer.legacySection().deserialize(legacy);
		Title.Times times = Title.Times.times(Duration.ZERO, Duration.ofMillis(100), Duration.ofMillis(150));
		Title title = Title.title(Component.empty(), subtitle, times);
		WormholesAudience.showTitle(player, title);
	}
}
