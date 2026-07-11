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
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.title.Title;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;

import art.arcane.wormholes.PortalManager;
import art.arcane.wormholes.Settings;
import art.arcane.wormholes.Wormholes;
import art.arcane.wormholes.geometry.Raycast;
import art.arcane.wormholes.network.PortalSyncService;
import art.arcane.wormholes.service.WormholesAudience;
import art.arcane.wormholes.service.WormholesTelemetry;
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
	private static final ParticleEffect.OrdinaryColor OUTLINE_PARTICLE_COLOR = new ParticleEffect.OrdinaryColor(150, 80, 255);
	private static final ConcurrentHashMap<UUID, Long> TELEPORT_COOLDOWNS = new ConcurrentHashMap<UUID, Long>();
	private static final ConcurrentHashMap<UUID, ReentryLatch> REENTRY_LATCHES = new ConcurrentHashMap<UUID, ReentryLatch>();
	private static final Set<UUID> TELEPORT_IN_FLIGHT = ConcurrentHashMap.newKeySet();

	private PhantomSpinner spinner;
	private PortalStructure structure;
	private PortalType type;
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
	private MirrorRotation mirrorRotation;
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
		mirrorRotation = MirrorRotation.DEGREES_0;
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
		j.put("mirrorRotationDegrees", mirrorRotation.getDegrees());
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
		owner = UUID.fromString(j.getString("owner"));
		projectionMode = resolveProjectionMode(j);
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
		if(storedMirrorRotation != mirrorRotation || dimensionalStateNormalized)
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

	@Override
	public void setType(PortalType type)
	{
		if(dimensionalPortalKind.isManagedPortal())
		{
			return;
		}
		if(this.type == type)
		{
			return;
		}

		boolean wasGateway = isGateway();
		this.type = type;

		if(wasGateway != isGateway())
		{
			detachDimensionalPairIdentity();
			tunnel = null;
		}

		if(!projectionMode.isAllowedFor(type))
		{
			setProjectionMode(ProjectionMode.ON);
		}

		save();
		syncGatewayTickets();
	}

	@Override
	public void update()
	{
		ITunnel activeTunnel = tunnel;
		IPortal destination = activeTunnel == null ? null : activeTunnel.getDestination();
		boolean tunnelPresent = activeTunnel != null && destination != null;

		if(isOpen())
		{
			if(isAmbientAttended())
			{
				playEffect(PortalEffect.AMBIENT_OPEN);
			}

			updateCaptures(activeTunnel, tunnelPresent);

			if(tunnelPresent && !activeTunnel.isValid())
			{
				if(activeTunnel.getTunnelType() != TunnelType.UNIVERSAL)
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

			if((tunnelPresent && activeTunnel.isValid()) || projectionMode == ProjectionMode.MIRROR)
			{
				open();
			}
		}

		if(Settings.DEBUG_RENDERING)
		{
			playEffect(PortalEffect.AMBIENT_DEBUG);
		}
	}

	private void updateCaptures(ITunnel activeTunnel, boolean tunnelPresent)
	{
		if(!isOpen() || !tunnelPresent)
		{
			return;
		}

		if(projectionMode == ProjectionMode.MIRROR || isInboundDisabledByOneWay())
		{
			return;
		}

		long now = System.currentTimeMillis();
		pruneTeleportCooldowns(now);
		for(Entity i : getStructure().getCaptureZone().getEntities(getStructure().getWorld()))
		{
			UUID entityId = i.getUniqueId();
			if(i instanceof Player viewer)
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

			if(!canUseTunnel(i, activeTunnel))
			{
				rejectTraversal(i, traversive);
				continue;
			}

			if(isTeleportCoolingDown(entityId, now))
			{
				continue;
			}

			markTeleportCooldown(entityId, now);
			Wormholes.v("[cross] " + i.getName() + " crossing portal " + getId() + " -> " + (activeTunnel instanceof UniversalTunnel ? "CROSS-SERVER handoff" : "local teleport"));
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
		Location start = i.getLocation();
		Location end = start.clone().add(velocity);
		Traversive[] f = new Traversive[1];

		new Raycast(start, end, 0.09)
		{
			@Override
			public boolean shouldContinue(Location l)
			{
				if(getStructure().contains(l))
				{
					playEffect(PortalEffect.PUSH, l);
					f[0] = buildCrossing(i, start, l.toVector(), velocity);
					return false;
				}

				return true;
			}
		};

		if(f[0] == null && getStructure().contains(start))
		{
			Vector crossVelocity = velocity.lengthSquared() > 1.0E-4D ? velocity : start.getDirection().clone().multiply(0.2D);
			playEffect(PortalEffect.PUSH, start);
			f[0] = buildCrossing(i, start, start.toVector(), crossVelocity);
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
		Vector bounce = traversive.getInVelocity().clone().multiply(-6.0D);
		if(bounce.lengthSquared() < 0.01D)
		{
			double side = traversive.isFrontSide() ? 1.0D : -1.0D;
			bounce = getFrame().getNormal().toVector().normalize().multiply(side * 3.0D);
		}
		entity.setVelocity(bounce);
		playEffect(PortalEffect.REJECT, traversive.getInPoint().toLocation(getStructure().getWorld()));
		notifyPortalDenied(entity);
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
			WormholesAudience.sendActionBar(player, Component.text("Portal access denied"));
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
		if(changed)
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

	private void playStructureOutline()
	{
		if(!M.r(0.45))
		{
			return;
		}

		Axis normalAxis = getFrame().getNormal().getAxis();
		for(Vector block : getStructure().getBlockPositions())
		{
			int x = block.getBlockX();
			int y = block.getBlockY();
			int z = block.getBlockZ();

			for(Direction direction : Direction.values())
			{
				if(direction.getAxis().equals(normalAxis))
				{
					continue;
				}

				if(!getStructure().containsBlock(x + direction.x(), y + direction.y(), z + direction.z()))
				{
					playBoundaryEdge(x, y, z, normalAxis, direction);
				}
			}
		}
	}

	private void playBoundaryEdge(int x, int y, int z, Axis normalAxis, Direction edgeDirection)
	{
		double normalX = x + 0.5D + (getFrame().getNormal().x() * 0.06D);
		double normalY = y + 0.5D + (getFrame().getNormal().y() * 0.06D);
		double normalZ = z + 0.5D + (getFrame().getNormal().z() * 0.06D);

		switch(normalAxis)
		{
			case X:
				if(edgeDirection.getAxis().equals(Axis.Y))
				{
					double edgeY = y + (edgeDirection.y() > 0 ? 1.0D : 0.0D);
					playOutlineLine(normalX, edgeY, z, normalX, edgeY, z + 1.0D);
				}
				else
				{
					double edgeZ = z + (edgeDirection.z() > 0 ? 1.0D : 0.0D);
					playOutlineLine(normalX, y, edgeZ, normalX, y + 1.0D, edgeZ);
				}
				break;
			case Y:
				if(edgeDirection.getAxis().equals(Axis.X))
				{
					double edgeX = x + (edgeDirection.x() > 0 ? 1.0D : 0.0D);
					playOutlineLine(edgeX, normalY, z, edgeX, normalY, z + 1.0D);
				}
				else
				{
					double edgeZ = z + (edgeDirection.z() > 0 ? 1.0D : 0.0D);
					playOutlineLine(x, normalY, edgeZ, x + 1.0D, normalY, edgeZ);
				}
				break;
			case Z:
				if(edgeDirection.getAxis().equals(Axis.X))
				{
					double edgeX = x + (edgeDirection.x() > 0 ? 1.0D : 0.0D);
					playOutlineLine(edgeX, y, normalZ, edgeX, y + 1.0D, normalZ);
				}
				else
				{
					double edgeY = y + (edgeDirection.y() > 0 ? 1.0D : 0.0D);
					playOutlineLine(x, edgeY, normalZ, x + 1.0D, edgeY, normalZ);
				}
				break;
			default:
				break;
		}
	}

	private void playOutlineLine(double x0, double y0, double z0, double x1, double y1, double z1)
	{
		for(int i = 0; i <= 3; i++)
		{
			double t = i / 3.0D;
			Location point = new Location(getWorld(), x0 + ((x1 - x0) * t), y0 + ((y1 - y0) * t), z0 + ((z1 - z0) * t));
			ParticleEffect.REDSTONE.display(OUTLINE_PARTICLE_COLOR, point, 32);
		}
	}


	@Override
	public void playEffect(PortalEffect effect, Location location)
	{
		switch(effect)
		{
			case PUSH:
				ParticleEffect.SMOKE.display(0.01f, 6, location, 32);
				location.getWorld().playSound(location, MSound.ENDERMAN_TELEPORT.bukkitSound(), 0.5f, 1.7f + (float) (Math.random() * 0.2));
				location.getWorld().playSound(location, MSound.ENDERMAN_TELEPORT.bukkitSound(), 0.5f, 1.5f + (float) (Math.random() * 0.2));
				location.getWorld().playSound(location, MSound.ENDERMAN_TELEPORT.bukkitSound(), 0.5f, 1.3f + (float) (Math.random() * 0.2));

				break;
			case REJECT:
				ParticleEffect.SMOKE.display(0.08f, 24, location, 32);
				ParticleEffect.REDSTONE.display(new ParticleEffect.OrdinaryColor(255, 70, 70), location, 32);
				location.getWorld().playSound(location, MSound.ANVIL_LAND.bukkitSound(), 0.7f, 1.8f);
				location.getWorld().playSound(location, MSound.GLASS.bukkitSound(), 0.6f, 0.7f);
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

				if(M.r(0.01))
				{
					getStructure().getCenter().getWorld().playSound(getStructure().getCenter(), Sound.BLOCK_LAVA_AMBIENT, 0.25f, 0.025f);
				}

				if(M.r(0.01))
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
							() -> Wormholes.effectManager.playPortalClose(closeWorld, corner, sx, sy, sz, () -> effectSequence.get() == closeSequence), 1L);
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
							() -> Wormholes.effectManager.playPortalOpen(openWorld, openCenter, sx, sy, sz, () -> effectSequence.get() == openSequence), 1L);
				}
				break;
			case AMBIENT_INSPECTING:
				playStructureOutline();
				if(M.r(0.325))
				{
					for(Location i : getStructure().getCorners())
					{
						ParticleEffect.FLAME.display(0f, 1, i, 32);
					}
				}

				ParticleEffect.ENCHANTMENT_TABLE.display(0f, 1, getStructure().randomLocation(), 32);

			case AMBIENT_DEBUG:

				break;
			default:
				break;
		}
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
			playEffect(PortalEffect.AMBIENT_INSPECTING);

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
			Vector outOffset = t.getOutOffset(getFrame());
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

			p.teleportAsync(target, PlayerTeleportEvent.TeleportCause.PLUGIN).whenComplete((success, error) ->
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

					if(Settings.DEBUG_TRAVERSABLES)
					{
						p.sendMessage("     ");
						p.sendMessage("     ");
						p.sendMessage("ANG: " + t.getInDirection().toString() + " -> " + getDirection().toString());
						p.sendMessage("FCE: " + Direction.getDirection(t.getInVelocity()).reverse().toString() + " -> " + Direction.closest(outVelocity).toString());
						p.sendMessage("MOV: " + Direction.getDirection(t.getInVelocity()).toString() + " -> " + Direction.getDirection(outVelocity).toString());
						p.sendMessage("LOK: " + Direction.getDirection(t.getInLook()).toString() + " -> " + Direction.getDirection(outLook).toString());
						p.sendMessage("POS: " + "(" + F.f(t.getInOffset().getX(), 1) + ", " + F.f(t.getInOffset().getY(), 1) + ", " + F.f(t.getInOffset().getZ(), 1) + ") -> (" + F.f(outOffset.getX(), 1) + ", " + F.f(outOffset.getY(), 1) + ", " + F.f(outOffset.getZ(), 1) + ")");
						p.sendMessage("MOT: " + "(" + F.f(t.getInVelocity().getX(), 1) + ", " + F.f(t.getInVelocity().getY(), 1) + ", " + F.f(t.getInVelocity().getZ(), 1) + ") -> (" + F.f(outVelocity.getX(), 1) + ", " + F.f(outVelocity.getY(), 1) + ", " + F.f(outVelocity.getZ(), 1) + ")");
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
	public void rejectDeparture(Entity entity, Traversive t)
	{
		rejectTraversal(entity, t);
	}

	@Override
	public void rejectRemoteArrival(Entity entity, Traversive t)
	{
		Location target = computeExitTarget(t);
		if(entity instanceof Player player)
		{
			player.teleportAsync(target, PlayerTeleportEvent.TeleportCause.PLUGIN).thenAccept(success ->
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

	@Override
	public boolean canDepart(Entity entity)
	{
		return projectionMode.allowsTraversal() && outgoingTraversalsEnabled && allowsPortalPermission(entity);
	}

	@Override
	public boolean canArrive(Entity entity)
	{
		return projectionMode.allowsTraversal() && incomingTraversalsEnabled && allowsPortalPermission(entity);
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
		REENTRY_LATCHES.put(entityId, new ReentryLatch(portalId, System.currentTimeMillis()));
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
		if(dimensionalPortalKind.isManagedPortal())
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
				Wormholes.effectManager.playNotificationFail(ChatColor.RED + getName() + " Deleted", deletionCenter);
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
			if(projectionMode == ProjectionMode.MIRROR || !projectionMode.isAllowedFor(PortalType.PORTAL))
			{
				projectionMode = ProjectionMode.ON;
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
			if(projectionMode == ProjectionMode.MIRROR || !projectionMode.isAllowedFor(PortalType.PORTAL))
			{
				projectionMode = ProjectionMode.ON;
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
		window.onClosed((w) -> openMenus.remove(p.getUniqueId()));
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

			String linkLore = hasTunnel()
					? ChatColor.GRAY + "Currently linked: " + ChatColor.GOLD + getTunnel().getDestination().getName()
					: ChatColor.GRAY + "Currently linked: " + ChatColor.RED + "None";
			window.setElement(-2, 1, new UIElement("set-destination")
					.setName(ChatColor.GOLD + "" + ChatColor.BOLD + (isGateway() ? "Pair & Destination" : "Destination"))
					.addLore(ChatColor.GRAY + (isGateway() ? "Pair another server or choose a gateway." : "Choose a portal to link to."))
					.addLore(linkLore)
					.addLore(" ")
					.addLore(ChatColor.DARK_GRAY + (isGateway() ? "Click to open the pairing hub." : "Click to open the destination picker."))
					.setMaterial(new MaterialBlock(isGateway() ? Material.END_CRYSTAL : Material.ENDER_EYE))
					.setCount(Math.max(1, Wormholes.portalManager.getAccessableCount(getType()) - 1))
					.onLeftClick((e) ->
					{
						if(dimensionalPortalKind.isManagedPortal())
						{
								notifySetting(p, "This dimensional portal keeps its generated link.");
							return;
						}
						if(isGateway())
						{
							window.close();
							uiOpenGatewayPairMenu(p);
						}
						else
						{
							uiChooseDestination(p);
						}
					}));

			window.setElement(-2, 2, new UIElement("set-name")
					.setName(ChatColor.GREEN + "" + ChatColor.BOLD + "Rename Portal")
					.addLore(ChatColor.GRAY + "Change the name shown on links and menus.")
					.addLore(ChatColor.GRAY + "Current: " + ChatColor.WHITE + getName())
					.addLore(" ")
					.addLore(ChatColor.DARK_GRAY + "Click to type a new name.")
					.setMaterial(new MaterialBlock(Material.NAME_TAG))
					.onLeftClick((e) -> uiChangeName(p)));

			window.setElement(0, 1, projectionsElement(window, p));
			window.setElement(2, 1, settingsOpenerElement(window, p));
			window.setElement(0, 2, orientationOpenerElement(window, p));
			window.setElement(2, 2, modeOpenerElement(window, p));

			window.setElement(0, 3, new UIElement("destroy")
					.setName(ChatColor.RED + "" + ChatColor.BOLD + "Delete Portal")
					.addLore(ChatColor.GRAY + "Permanently removes this portal.")
					.addLore(" ")
				.addLore(ChatColor.RED + "" + ChatColor.UNDERLINE + "Shift + Left Click to confirm")
				.setMaterial(new MaterialBlock(Material.GUNPOWDER))
					.onShiftLeftClick((e) ->
					{
						if(!ensureCanManagePortal(p))
						{
							return;
						}
						window.close();
						destroy();
				}));
		});
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
		window.setElement(-3, 1, networkViewNumberElement(window, p, "network-view-depth", "Capture Radius",
				ChatColor.GRAY + "Blocks streamed around the destination portal.",
				Material.SPYGLASS, this::getNetworkViewDepth, this::setNetworkViewDepth, 4, 16));
		window.setElement(-1, 1, networkViewNumberElement(window, p, "network-view-heartbeat", "Full Refresh",
				ChatColor.GRAY + "Ticks between full block refreshes.",
				Material.CLOCK, this::getNetworkViewHeartbeatTicks, this::setNetworkViewHeartbeatTicks, 10, 60));
		window.setElement(1, 1, networkViewNumberElement(window, p, "network-view-entities", "Entity Update",
				ChatColor.GRAY + "Ticks between entity updates.",
				Material.ENDER_EYE, this::getNetworkViewEntityIntervalTicks, this::setNetworkViewEntityIntervalTicks, 2, 20));
		window.setElement(3, 1, networkViewNumberElement(window, p, "network-view-grace", "View Grace",
				ChatColor.GRAY + "Seconds to keep a warm stream after looking away.",
				Material.REDSTONE, this::getNetworkViewUnsubscribeGraceSeconds, this::setNetworkViewUnsubscribeGraceSeconds, 5, 30));
		window.setElement(0, 2, networkViewFallbackElement(p, window));
		window.setElement(0, 3, backToSettingsMenuElement(window, p));
		window.setVisible(true);
	}

	private Element advancedSettingsPlacardElement()
	{
		UIElement element = new UIElement("advanced-settings-placard");
		element.setName(ChatColor.DARK_AQUA + "" + ChatColor.BOLD + "Advanced Stream Tuning");
		element.setMaterial(new MaterialBlock(Material.COMPARATOR));
		element.addLore(ChatColor.GRAY + "Direct controls for diagnostics and unusual links.");
		element.addLore(ChatColor.GRAY + "Changing one value marks Stream Quality as Custom.");
		return element;
	}

	private Element backToSettingsMenuElement(Window window, Player viewer)
	{
		UIElement element = new UIElement("back-to-settings");
		element.setName(ChatColor.YELLOW + "" + ChatColor.BOLD + "Back to Settings");
		element.setMaterial(new MaterialBlock(Material.ARROW));
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
				notifySetting(viewer, "Dimensional portal travel is managed automatically.");
				return;
			}
			if(getProjectionMode() == ProjectionMode.MIRROR)
			{
				notifySetting(viewer, "Mirror mode never allows travel.");
				return;
			}
			PortalTravelMode mode = getTravelMode().next();
			setTravelMode(mode);
			applyTravelDirectionElement(element);
			window.updateInventory();
			notifySetting(viewer, "Travel " + ChatColor.AQUA + mode.getDisplayName());
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
			String direction = dimensionalPortalKind == DimensionalPortalKind.NETHER ? "Both Ways" : receiver ? "Arrival Only" : "Departure Only";
			element.setName(ChatColor.GOLD + "" + ChatColor.BOLD + "Travel: " + direction);
			element.setEnchanted(true);
			element.setMaterial(new MaterialBlock(dimensionalPortalKind == DimensionalPortalKind.NETHER ? Material.OBSIDIAN
					: receiver ? Material.ENDER_EYE : Material.ENDER_PEARL));
			KList<String> endLore = element.getLore();
			endLore.clear();
			endLore.add(ChatColor.GRAY + "Managed dimensional portal direction.");
			endLore.add(ChatColor.GRAY + (dimensionalPortalKind == DimensionalPortalKind.NETHER ? "Both linked halves stay active." : "The return path stays disabled."));
			return;
		}
		if(getProjectionMode() == ProjectionMode.MIRROR)
		{
			element.setName(ChatColor.RED + "" + ChatColor.BOLD + "Travel: Mirror Locked");
			element.setEnchanted(false);
			element.setMaterial(new MaterialBlock(Material.BARRIER));
			KList<String> mirrorLore = element.getLore();
			mirrorLore.clear();
			mirrorLore.add(ChatColor.GRAY + "Mirror mode is visual only.");
			mirrorLore.add(ChatColor.GRAY + "Entities cannot enter or leave through it.");
			return;
		}
		PortalTravelMode mode = getTravelMode();
		boolean locked = mode == PortalTravelMode.LOCKED;
		element.setName((locked ? ChatColor.RED : ChatColor.GREEN) + "" + ChatColor.BOLD + "Travel: " + mode.getDisplayName());
		element.setEnchanted(!locked);
		element.setMaterial(new MaterialBlock(switch(mode)
		{
			case BOTH -> Material.RECOVERY_COMPASS;
			case OUTBOUND -> Material.ENDER_PEARL;
			case INBOUND -> Material.ENDER_EYE;
			case LOCKED -> Material.BARRIER;
		}));
		KList<String> lore = element.getLore();
		lore.clear();
		lore.add(ChatColor.GRAY + "Controls travel through this portal.");
		lore.add(" ");
		lore.add(ChatColor.GRAY + "Outgoing: " + (mode.allowsOutgoing() ? ChatColor.GREEN + "On" : ChatColor.RED + "Off"));
		lore.add(ChatColor.GRAY + "Incoming: " + (mode.allowsIncoming() ? ChatColor.GREEN + "On" : ChatColor.RED + "Off"));
		lore.add(" ");
		lore.add(ChatColor.DARK_GRAY + "Click to cycle travel direction.");
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
			notifySetting(viewer, "Stream Quality " + ChatColor.AQUA + quality.getDisplayName());
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
		element.setName(ChatColor.AQUA + "" + ChatColor.BOLD + "Stream Quality: " + quality.getDisplayName());
		element.setEnchanted(quality == NetworkViewQuality.CINEMATIC);
		element.setMaterial(new MaterialBlock(switch(quality)
		{
			case STANDARD -> Material.CONDUIT;
			case PERFORMANCE -> Material.FEATHER;
			case BALANCED -> Material.SPYGLASS;
			case CINEMATIC -> Material.BEACON;
			case CUSTOM -> Material.COMPARATOR;
		}));
		KList<String> lore = element.getLore();
		lore.clear();
		lore.add(ChatColor.GRAY + "One control for remote view range and cadence.");
		lore.add(" ");
		lore.add(ChatColor.GRAY + "Depth " + ChatColor.AQUA + networkViewDepth
				+ ChatColor.GRAY + "  Entities " + ChatColor.AQUA + networkViewEntityIntervalTicks + "t");
		lore.add(ChatColor.GRAY + "Refresh " + ChatColor.AQUA + networkViewHeartbeatTicks + "t"
				+ ChatColor.GRAY + "  Grace " + ChatColor.AQUA + networkViewUnsubscribeGraceSeconds + "s");
		lore.add(" ");
		lore.add(ChatColor.DARK_GRAY + "Click: cycle quality.");
		lore.add(ChatColor.DARK_GRAY + "Shift-click: advanced tuning.");
	}

	private Element orientationOpenerElement(Window window, Player viewer)
	{
		UIElement element = new UIElement("portal-orientation");
		element.setName(ChatColor.BLUE + "" + ChatColor.BOLD + "Orientation");
		element.setMaterial(new MaterialBlock(Material.COMPASS));
		element.addLore(ChatColor.GRAY + "Facing: " + ChatColor.BLUE + getDirection());
		element.addLore(ChatColor.GRAY + "Up: " + ChatColor.LIGHT_PURPLE + getFrame().getUp());
		element.addLore(" ");
		element.addLore(ChatColor.DARK_GRAY + "Click for facing, flip, and rotation.");
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
		UIElement element = new UIElement("orientation-placard");
		element.setName(ChatColor.BLUE + "" + ChatColor.BOLD + "Portal Orientation");
		element.setMaterial(new MaterialBlock(Material.COMPASS));
		element.addLore(ChatColor.GRAY + "Facing: " + ChatColor.BLUE + getDirection());
		element.addLore(ChatColor.GRAY + "Screen up: " + ChatColor.LIGHT_PURPLE + getFrame().getUp());
		return element;
	}

	private void uiOpenGatewayPairMenu(Player viewer)
	{
		UIWindow window = new UIWindow(Wormholes.instance, viewer);
		window.setTitle(getRouter(true));
		window.setResolution(WindowResolution.W9_H6);
		window.setViewportHeight(3);
		window.setDecorator(new UIPaneDecorator(Material.PURPLE_STAINED_GLASS_PANE));
		window.setElement(0, 0, gatewayPairPlacardElement());
		window.setElement(-2, 1, exportPortalElement(window, viewer));
		window.setElement(0, 1, new UIElement("choose-gateway-destination")
				.setName(ChatColor.LIGHT_PURPLE + "" + ChatColor.BOLD + "Choose Destination")
				.setMaterial(new MaterialBlock(Material.END_CRYSTAL))
				.addLore(ChatColor.GRAY + "Choose from discovered local and remote gateways.")
				.addLore(" ")
				.addLore(ChatColor.DARK_GRAY + "Click to open the destination list.")
				.onLeftClick((e) ->
				{
					window.close();
					uiChooseDestination(viewer);
				}));
		window.setElement(2, 1, importPortalElement(window, viewer));
		window.setElement(0, 2, backToPortalMenuElement(window, viewer));
		window.setVisible(true);
	}

	private Element gatewayPairPlacardElement()
	{
		UIElement element = new UIElement("gateway-pair-placard");
		element.setName(ChatColor.LIGHT_PURPLE + "" + ChatColor.BOLD + "Gateway Pairing");
		element.setMaterial(new MaterialBlock(Material.RESPAWN_ANCHOR));
		if(!hasTunnel())
		{
			element.addLore(ChatColor.GRAY + "Status: " + ChatColor.RED + "Not paired");
			return element;
		}
		element.addLore(ChatColor.GRAY + "Destination: " + ChatColor.GOLD + getTunnel().getDestination().getName());
		if(getTunnel() instanceof UniversalTunnel universal && Wormholes.networkManager != null)
		{
			String peer = universal.getServerName();
			boolean ready = Wormholes.networkManager.isPeerReady(peer);
			String transport = Wormholes.networkManager.isSidebandOnlyPeer(peer) ? "Sideband" : "Direct";
			element.addLore(ChatColor.GRAY + "Server: " + ChatColor.WHITE + peer);
			element.addLore(ChatColor.GRAY + "Link: " + (ready ? ChatColor.GREEN + transport : ChatColor.RED + "Reconnecting"));
		}
		return element;
	}

	private Element exportPortalElement(Window window, Player viewer)
	{
		return new UIElement("export-portal")
				.setName(ChatColor.GOLD + "" + ChatColor.BOLD + "Create Invite")
				.addLore(ChatColor.GRAY + "Create a signed gateway code for another server.")
				.addLore(" ")
				.addLore(ChatColor.DARK_GRAY + "Click to copy a fresh code.")
				.setMaterial(new MaterialBlock(Material.PAPER))
				.onLeftClick((e) -> FoliaScheduler.runEntity(Wormholes.instance, viewer, () ->
				{
					window.close();
					if(Wormholes.importExportService != null)
					{
						Wormholes.importExportService.exportToChat(viewer, LocalPortal.this);
					}
				}));
	}

	private Element importPortalElement(Window window, Player viewer)
	{
		return new UIElement("import-portal")
				.setName(ChatColor.AQUA + "" + ChatColor.BOLD + "Use Invite")
				.addLore(ChatColor.GRAY + "Paste a signed code from another gateway.")
				.addLore(" ")
				.addLore(ChatColor.DARK_GRAY + "Click, then paste the code in chat.")
				.setMaterial(new MaterialBlock(Material.WRITABLE_BOOK))
				.onLeftClick((e) -> FoliaScheduler.runEntity(Wormholes.instance, viewer, () ->
				{
					window.close();
					viewer.closeInventory();
					viewer.sendMessage(ChatColor.AQUA + "Paste the portal invite in chat (or 'cancel'):");
					Wormholes.awaitChatInput(viewer, (text) ->
					{
						if(text == null || text.equalsIgnoreCase("cancel"))
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
	}

	private Element networkViewNumberElement(Window window, Player viewer, String id, String label, String description, Material material, IntSupplier getter, IntConsumer setter, int step, int largeStep)
	{
		UIElement element = new UIElement(id);
		element.onLeftClick((e) -> adjustNetworkViewNumber(element, window, viewer, label, description, material, getter, setter, step));
		element.onRightClick((e) -> adjustNetworkViewNumber(element, window, viewer, label, description, material, getter, setter, -step));
		element.onShiftLeftClick((e) -> adjustNetworkViewNumber(element, window, viewer, label, description, material, getter, setter, largeStep));
		element.onShiftRightClick((e) -> adjustNetworkViewNumber(element, window, viewer, label, description, material, getter, setter, -largeStep));
		applyNetworkViewNumberElement(element, label, description, material, getter, step, largeStep);
		return element;
	}

	private void adjustNetworkViewNumber(UIElement element, Window window, Player viewer, String label, String description, Material material, IntSupplier getter, IntConsumer setter, int delta)
	{
		int previous = getter.getAsInt();
		setter.accept(previous + delta);
		int current = getter.getAsInt();
		applyNetworkViewNumberElement(element, label, description, material, getter, Math.abs(delta), Math.abs(delta));
		window.updateInventory();
		if(current != previous)
		{
			notifySetting(viewer, label + " " + ChatColor.AQUA + current);
		}
	}

	private void applyNetworkViewNumberElement(Element element, String label, String description, Material material, IntSupplier getter, int step, int largeStep)
	{
		element.setName(ChatColor.AQUA + "" + ChatColor.BOLD + label + " " + ChatColor.WHITE + getter.getAsInt());
		element.setMaterial(new MaterialBlock(material));
		KList<String> lore = element.getLore();
		lore.clear();
		lore.add(description);
		lore.add(" ");
		lore.add(ChatColor.GRAY + "Currently: " + ChatColor.AQUA + getter.getAsInt());
		lore.add(" ");
		lore.add(ChatColor.DARK_GRAY + "Left click: +" + Math.abs(step));
		lore.add(ChatColor.DARK_GRAY + "Right click: -" + Math.abs(step));
		lore.add(ChatColor.DARK_GRAY + "Shift-left: +" + Math.abs(largeStep));
		lore.add(ChatColor.DARK_GRAY + "Shift-right: -" + Math.abs(largeStep));
	}

	private Element networkViewFallbackElement(Player p, Window window)
	{
		UIElement element = new UIElement("network-view-fallback");
		element.onLeftClick((e) ->
		{
			window.close();
			p.closeInventory();
			p.sendMessage(ChatColor.AQUA + "Enter a block state for this portal's network view edge, or 'cancel':");
			Wormholes.awaitChatInput(p, (text) ->
			{
				if(text == null || text.equalsIgnoreCase("cancel"))
				{
					uiOpenSettingsMenu(p);
					return;
				}
				String previous = getNetworkViewFallbackBlock();
				String normalized = normalizeNetworkViewFallbackBlock(text);
				setNetworkViewFallbackBlock(normalized);
				if(!previous.equals(getNetworkViewFallbackBlock()))
				{
					notifySetting(p, "Fallback set to " + ChatColor.AQUA + getNetworkViewFallbackBlock());
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
				notifySetting(p, "Fallback reset to " + ChatColor.AQUA + getNetworkViewFallbackBlock());
			}
		});
		applyNetworkViewFallbackElement(element);
		return element;
	}

	private void applyNetworkViewFallbackElement(Element element)
	{
		element.setName(ChatColor.AQUA + "" + ChatColor.BOLD + "Fallback Block");
		element.setMaterial(new MaterialBlock(Material.GLASS));
		KList<String> lore = element.getLore();
		lore.clear();
		lore.add(ChatColor.GRAY + "Block shown beyond streamed depth.");
		lore.add(" ");
		lore.add(ChatColor.GRAY + "Currently: " + ChatColor.WHITE + getNetworkViewFallbackBlock());
		lore.add(" ");
		lore.add(ChatColor.DARK_GRAY + "Left: enter a block state.");
		lore.add(ChatColor.DARK_GRAY + "Right: reset to air.");
	}

	@Override
	public void uiChooseMode(Player p)
	{
		if(dimensionalPortalKind.isManagedPortal())
		{
			notifySetting(p, "Managed dimensional portals stay in portal mode.");
			return;
		}
		UIWindow window = new UIWindow(Wormholes.instance, p);
		window.setTitle(getRouter(true));
		window.setResolution(WindowResolution.W9_H6);
		window.setViewportHeight(3);
		window.setDecorator(new UIPaneDecorator(Material.GRAY_STAINED_GLASS_PANE));
		window.onClosed((w) -> FoliaScheduler.runEntity(Wormholes.instance, p, () -> uiOpenPortalMenu(p)));

		window.setElement(0, 0, modePlacardElement());
		window.setElement(-2, 1, modeOption(PortalType.PORTAL, p, window));
		window.setElement(0, 1, modeOption(PortalType.WORMHOLE, p, window));
		window.setElement(2, 1, modeOption(PortalType.GATEWAY, p, window));
		window.setElement(0, 2, backToPortalMenuElement(window, p));

		window.setVisible(true);
	}

	private Element portalPlacardElement()
	{
		UIElement element = new UIElement("portal-placard");
		element.setName(ChatColor.GOLD + "" + ChatColor.BOLD + getName());
		element.setMaterial(new MaterialBlock(Material.BOOK));
		element.addLore(ChatColor.GRAY + "Type: " + ChatColor.YELLOW + F.capitalize(getType().name().toLowerCase()));
		element.addLore(ChatColor.GRAY + "Facing: " + ChatColor.BLUE + getDirection().toString());
		if(hasTunnel())
		{
			element.addLore(ChatColor.GRAY + "Linked to: " + ChatColor.GOLD + getTunnel().getDestination().getName());
		}
		else
		{
			element.addLore(ChatColor.GRAY + "Linked to: " + ChatColor.RED + "None");
		}
		element.addLore(" ");
		element.addLore(ChatColor.DARK_GRAY + "Operators bypass white/blacklist.");
		return element;
	}

	private Element settingsPlacardElement()
	{
		UIElement element = new UIElement("settings-placard");
		element.setName(ChatColor.AQUA + "" + ChatColor.BOLD + "Portal Settings");
		element.setMaterial(new MaterialBlock(Material.LEVER));
		element.addLore(ChatColor.GRAY + "Access and transfer controls.");
		if(isGateway())
		{
			element.addLore(ChatColor.GRAY + "Plus cross-server view tuning.");
			element.addLore(" ");
			element.addLore(ChatColor.GRAY + "Larger depth / shorter ticks =");
			element.addLore(ChatColor.GRAY + "richer view, more bandwidth.");
		}
		return element;
	}

	private Element modePlacardElement()
	{
		UIElement element = new UIElement("mode-placard");
		element.setName(ChatColor.YELLOW + "" + ChatColor.BOLD + "Portal Mode");
		element.setMaterial(new MaterialBlock(Material.BEACON));
		element.addLore(ChatColor.GRAY + "Current: " + ChatColor.YELLOW + F.capitalize(getType().name().toLowerCase()));
		element.addLore(" ");
		element.addLore(ChatColor.GRAY + "Portal: basic linked portal.");
		element.addLore(ChatColor.GRAY + "Wormhole: portal with viewport.");
		element.addLore(ChatColor.GRAY + "Gateway: cross-server gateway.");
		return element;
	}

	private Element modeOpenerElement(Window window, Player viewer)
	{
		UIElement element = new UIElement("set-mode");
		element.setName(ChatColor.YELLOW + "" + ChatColor.BOLD + "Mode");
		element.setMaterial(new MaterialBlock(modeIcon(getType())));
		element.setEnchanted(true);
		element.addLore(ChatColor.GRAY + modeDescription(getType()));
		element.addLore(" ");
		element.addLore(ChatColor.GRAY + "Currently: " + ChatColor.YELLOW + F.capitalize(getType().name().toLowerCase()));
		element.addLore(" ");
		element.addLore(ChatColor.DARK_GRAY + "Click to change mode.");
		element.onLeftClick((e) -> FoliaScheduler.runEntity(Wormholes.instance, viewer, () ->
		{
			window.close();
			uiChooseMode(viewer);
		}));
		return element;
	}

	private Element directionElement(Window window, Player viewer)
	{
		UIElement element = new UIElement("set-direction");
		element.setName(ChatColor.BLUE + "" + ChatColor.BOLD + "Direction");
		element.setMaterial(new MaterialBlock(Material.COMPASS));
		element.addLore(ChatColor.GRAY + "Change the portal facing direction.");
		element.addLore(" ");
		element.addLore(ChatColor.GRAY + "Currently facing: " + ChatColor.BLUE + getDirection().toString());
		element.addLore(" ");
		element.addLore(ChatColor.DARK_GRAY + "Click then look, left click to apply.");
		element.onLeftClick((e) ->
		{
			window.close();
			uiChangeDirection(viewer);
		});
		return element;
	}

	private Element flipFaceElement(Window window, Player viewer)
	{
		UIElement element = new UIElement("flip-face");
		element.setName(ChatColor.AQUA + "" + ChatColor.BOLD + "Flip Face");
		element.setMaterial(new MaterialBlock(Material.TARGET));
		element.addLore(ChatColor.GRAY + "Reverse the portal face direction.");
		element.addLore(ChatColor.GRAY + "Screen rotation stays aligned.");
		element.addLore(" ");
		element.addLore(ChatColor.GRAY + "Currently rolling up: " + ChatColor.AQUA + getFrame().getUp().toString());
		element.addLore(" ");
		element.addLore(ChatColor.DARK_GRAY + "Click to flip.");
		element.onLeftClick((e) -> FoliaScheduler.runEntity(Wormholes.instance, viewer, () ->
		{
			setFrame(getFrame().flipNormal());
			Wormholes.effectManager.playNotificationSuccess(ChatColor.GREEN + getName() + " face flipped to " + getDirection().toString() + ".", getStructure().getCenter());
			window.close();
			uiOpenPortalMenu(viewer);
		}));
		return element;
	}

	private Element rotateCounterClockwiseElement(Window window, Player viewer)
	{
		UIElement element = new UIElement("rotate-counter-clockwise");
		element.setName(ChatColor.LIGHT_PURPLE + "" + ChatColor.BOLD + "Rotate Counterclockwise");
		element.setMaterial(new MaterialBlock(Material.REPEATER));
		element.addLore(ChatColor.GRAY + "Roll the portal viewport 90 degrees");
		element.addLore(ChatColor.GRAY + "without changing the face.");
		element.addLore(" ");
		element.addLore(ChatColor.GRAY + "Currently rolling up: " + ChatColor.LIGHT_PURPLE + getFrame().getUp().toString());
		element.addLore(" ");
		element.addLore(ChatColor.DARK_GRAY + "Click to rotate.");
		element.onLeftClick((e) -> FoliaScheduler.runEntity(Wormholes.instance, viewer, () ->
		{
			setFrame(getFrame().rotateCounterClockwise());
			Wormholes.effectManager.playNotificationSuccess(ChatColor.GREEN + getName() + " rolled counterclockwise.", getStructure().getCenter());
			window.close();
			uiOpenPortalMenu(viewer);
		}));
		return element;
	}

	private Element rotateClockwiseElement(Window window, Player viewer)
	{
		UIElement element = new UIElement("rotate-clockwise");
		element.setName(ChatColor.LIGHT_PURPLE + "" + ChatColor.BOLD + "Rotate Clockwise");
		element.setMaterial(new MaterialBlock(Material.LEVER));
		element.addLore(ChatColor.GRAY + "Roll the portal viewport 90 degrees");
		element.addLore(ChatColor.GRAY + "without changing the face.");
		element.addLore(" ");
		element.addLore(ChatColor.GRAY + "Currently rolling up: " + ChatColor.LIGHT_PURPLE + getFrame().getUp().toString());
		element.addLore(" ");
		element.addLore(ChatColor.DARK_GRAY + "Click to rotate.");
		element.onLeftClick((e) -> FoliaScheduler.runEntity(Wormholes.instance, viewer, () ->
		{
			setFrame(getFrame().rotateClockwise());
			Wormholes.effectManager.playNotificationSuccess(ChatColor.GREEN + getName() + " rolled clockwise.", getStructure().getCenter());
			window.close();
			uiOpenPortalMenu(viewer);
		}));
		return element;
	}

	private Element backToPortalMenuElement(Window window, Player viewer)
	{
		UIElement element = new UIElement("back-to-portal");
		element.setName(ChatColor.YELLOW + "" + ChatColor.BOLD + "Back");
		element.setMaterial(new MaterialBlock(Material.ARROW));
		element.addLore(ChatColor.GRAY + "Return to the portal menu.");
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
				notifySetting(viewer, "The End arrival stays visually inactive.");
				return;
			}
			if(dimensionalPortalKind.isManagedPortal())
			{
				setProjectionMode(previous == ProjectionMode.OFF ? ProjectionMode.ON : ProjectionMode.OFF);
			}
			else
			{
				setProjectionMode(getProjectionMode().nextFor(getType()));
			}
			applyProjectionMode(element);
			window.updateInventory();
			if(previous != getProjectionMode())
			{
				notifySetting(viewer, "Projections: " + ChatColor.AQUA + getProjectionMode().getDisplayName());
			}
		});
		element.onRightClick((e) ->
		{
			rotateMirrorImage(element, window, viewer, getMirrorRotation().clockwiseFor(getFrame()));
		});
		element.onShiftRightClick((e) ->
		{
			rotateMirrorImage(element, window, viewer, getMirrorRotation().counterClockwiseFor(getFrame()));
		});
		applyProjectionMode(element);
		return element;
	}

	private void applyProjectionMode(Element element)
	{
		ProjectionMode mode = getProjectionMode();
		element.setName(mode.getDisplayName());
		element.setEnchanted(mode.isEnchanted());
		element.setMaterial(new MaterialBlock(mode.getIcon()));
		KList<String> lore = element.getLore();
		lore.clear();
		lore.add(ChatColor.GRAY + mode.getLoreLine1());
		lore.add(ChatColor.GRAY + mode.getLoreLine2());
		lore.add(" ");
		lore.add(ChatColor.GRAY + "Currently: " + ChatColor.AQUA + mode.getDisplayName());
		lore.add(" ");
		if(isGateway())
		{
			lore.add(ChatColor.DARK_GRAY + "Click to cycle: Off > On > One-Way > Mirror");
		}
		else
		{
			lore.add(ChatColor.DARK_GRAY + "Click to cycle: Off > On > Mirror");
		}
		if(mode == ProjectionMode.MIRROR)
		{
			lore.add(ChatColor.GRAY + "Image rotation: " + ChatColor.AQUA + getMirrorRotation().getDegrees() + " degrees");
			if(MirrorRotation.supportsQuarterTurns(getFrame()))
			{
				lore.add(ChatColor.DARK_GRAY + "Right click: rotate clockwise.");
				lore.add(ChatColor.DARK_GRAY + "Shift + right click: rotate counterclockwise.");
			}
			else
			{
				lore.add(ChatColor.GRAY + "Wall mirrors flip in 180 degree steps");
				lore.add(ChatColor.GRAY + "so reflected entities stay aligned.");
				lore.add(ChatColor.DARK_GRAY + "Right click: flip the reflected image.");
			}
		}
	}

	private void rotateMirrorImage(Element element, Window window, Player viewer, MirrorRotation rotation)
	{
		if(getProjectionMode() != ProjectionMode.MIRROR)
		{
			notifySetting(viewer, "Choose Mirror before rotating the image.");
			return;
		}
		setMirrorRotation(rotation);
		applyProjectionMode(element);
		window.updateInventory();
		notifySetting(viewer, "Mirror Rotation " + ChatColor.AQUA + getMirrorRotation().getDegrees() + " degrees");
	}

	private Element settingsOpenerElement(Window window, Player viewer)
	{
		UIElement element = new UIElement("portal-settings");
		element.setName(ChatColor.AQUA + "" + ChatColor.BOLD + "Settings");
		element.setMaterial(new MaterialBlock(Material.LEVER));
		element.addLore(ChatColor.GRAY + "Permissions, transfers" + (isGateway() ? "," : "."));
		if(isGateway())
		{
			element.addLore(ChatColor.GRAY + "and cross-server view tuning.");
		}
		element.addLore(" ");
		element.addLore(ChatColor.GRAY + "Access: " + ChatColor.LIGHT_PURPLE + getPermissionMode().getDisplayName());
		element.addLore(ChatColor.GRAY + "Send " + (isOutgoingTraversalsEnabled() ? ChatColor.GREEN + "On" : ChatColor.RED + "Off")
				+ ChatColor.GRAY + "  Receive " + (isIncomingTraversalsEnabled() ? ChatColor.GREEN + "On" : ChatColor.RED + "Off"));
		if(isGateway())
		{
			element.addLore(ChatColor.GRAY + "Depth " + ChatColor.AQUA + networkViewDepth
					+ ChatColor.GRAY + "  Entity " + ChatColor.AQUA + networkViewEntityIntervalTicks + "t");
		}
		element.addLore(" ");
		element.addLore(ChatColor.DARK_GRAY + "Click to open settings.");
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
			notifySetting(viewer, "Settings Sync " + (isSettingsSyncEnabled() ? ChatColor.GREEN + "On" : ChatColor.RED + "Off"));
		});
		applySettingsSyncElement(element);
		return element;
	}

	private void applySettingsSyncElement(Element element)
	{
		boolean enabled = isSettingsSyncEnabled();
		element.setName((enabled ? ChatColor.GREEN : ChatColor.RED) + "" + ChatColor.BOLD + "Settings Sync " + (enabled ? "On" : "Off"));
		element.setEnchanted(enabled);
		element.setMaterial(new MaterialBlock(enabled ? Material.SOUL_LANTERN : Material.LANTERN));
		KList<String> lore = element.getLore();
		lore.clear();
		lore.add(ChatColor.GRAY + "When On, this portal's settings");
		lore.add(ChatColor.GRAY + "(depth, ticks, projection, permissions,");
		lore.add(ChatColor.GRAY + "transfers) sync to all linked peers.");
		lore.add(" ");
		lore.add(ChatColor.GRAY + "Currently: " + (enabled ? ChatColor.GREEN + "On" : ChatColor.RED + "Off"));
		lore.add(" ");
		lore.add(ChatColor.DARK_GRAY + "Click to toggle.");
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
				notifySetting(viewer, "Access " + ChatColor.AQUA + getPermissionMode().getDisplayName());
			}
		});
		applyPermissionElement(element);
		return element;
	}

	private void applyPermissionElement(Element element)
	{
		PortalPermissionMode mode = getPermissionMode();
		element.setName(ChatColor.LIGHT_PURPLE + "" + ChatColor.BOLD + "Access " + mode.getDisplayName());
		element.setEnchanted(mode == PortalPermissionMode.WHITELIST);
		element.setMaterial(new MaterialBlock(mode == PortalPermissionMode.WHITELIST ? Material.GOLDEN_HELMET : Material.IRON_HELMET));
		KList<String> lore = element.getLore();
		lore.clear();
		lore.add(ChatColor.GRAY + mode.getLoreLine());
		lore.add(ChatColor.GRAY + "Node: " + ChatColor.WHITE + getPermissionNode());
		lore.add(" ");
		lore.add(ChatColor.GRAY + "Currently: " + ChatColor.LIGHT_PURPLE + mode.getDisplayName());
		lore.add(" ");
		lore.add(ChatColor.DARK_GRAY + "Click to toggle whitelist / blacklist.");
		lore.add(ChatColor.DARK_GRAY + "Operators always bypass.");
	}

	private Element modeOption(PortalType target, Player p, Window window)
	{
		boolean current = getType() == target;
		String label = F.capitalize(target.name().toLowerCase());
		UIElement element = new UIElement("mode-" + target.name().toLowerCase());
		element.setName(ChatColor.YELLOW + "" + ChatColor.BOLD + label);
		element.setMaterial(new MaterialBlock(modeIcon(target)));
		element.setEnchanted(current);
		element.addLore(ChatColor.GRAY + modeDescription(target));
		element.addLore(" ");
		element.addLore(current ? ChatColor.GREEN + "Currently Selected" : ChatColor.GRAY + "Click to select");
		element.onLeftClick((e) -> FoliaScheduler.runEntity(Wormholes.instance, p, () ->
		{
			if(getType() != target)
			{
				setType(target);
				Wormholes.effectManager.playNotificationSuccess(ChatColor.GREEN + getName() + " mode set to " + label + ".", getStructure().getCenter());
			}
			window.close();
		}));
		return element;
	}

	private void notifySetting(Player viewer, String message)
	{
		if(viewer == null)
		{
			return;
		}
		Wormholes.effectManager.playNotificationSuccess(ChatColor.GREEN + getName() + ": " + ChatColor.RESET + message, viewer);
	}

	private static Material modeIcon(PortalType type)
	{
		return switch(type)
		{
			case GATEWAY -> Material.END_CRYSTAL;
			case WORMHOLE -> Material.ENDER_PEARL;
			case PORTAL -> Material.ENDER_EYE;
		};
	}

	private static String modeDescription(PortalType type)
	{
		return switch(type)
		{
			case GATEWAY -> "Reserved for cross-network linking.";
			case WORMHOLE -> "Linkable portal with viewport projection.";
			case PORTAL -> "Basic linkable portal.";
		};
	}

	@Override
	public void uiChooseDestination(Player p)
	{
		if(dimensionalPortalKind.isManagedPortal())
		{
			notifySetting(p, "This dimensional portal keeps its generated link.");
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
			if(i.getId().equals(getId()))
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
			//@builder
			window.setElement(window.getPosition(pos), window.getRow(pos), new UIElement("portal-" + pos)
					.setMaterial(new MaterialBlock(Material.ENDER_PEARL))
					.setEnchanted(isLinkedToLocal(target))
					.setName(ChatColor.GOLD + "" + target.getName())
					.addLore(ChatColor.GRAY + "at " + target.getStructure().getCenter().getBlockX() + ", " + target.getStructure().getCenter().getBlockY() + ", " + target.getStructure().getCenter().getBlockZ() + " in " + target.getStructure().getWorld().getName() + " Facing " + target.getDirection().toString())
					.onLeftClick((e) -> FoliaScheduler.runEntity(Wormholes.instance, p, () -> {
						window.close();

						if(isLinkedToLocal(target))
						{
							unlink();
							Wormholes.effectManager.playNotificationSuccess(ChatColor.YELLOW + getName() + " unlinked from " + target.getName() + ".", getStructure().getCenter());
						}
						else
						{
							setDestination(target);
							Wormholes.effectManager.playNotificationSuccess(ChatColor.GREEN + getName() + " linked to " + target.getName() + ".", getStructure().getCenter());
						}
					})));
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
				//@builder
				window.setElement(window.getPosition(pos), window.getRow(pos), new UIElement("remote-portal-" + pos)
						.setMaterial(new MaterialBlock(Material.END_CRYSTAL))
						.setEnchanted(linked)
						.setName(ChatColor.LIGHT_PURPLE + "" + target.getName())
						.addLore(ChatColor.GRAY + "on server " + ChatColor.WHITE + target.getServer().getName())
						.addLore(ChatColor.GRAY + "at " + target.getOrigin().getBlockX() + ", " + target.getOrigin().getBlockY() + ", " + target.getOrigin().getBlockZ() + " in " + target.getServer().getWorld() + " Facing " + target.getDirection().toString())
						.addLore(target.isOpen() ? ChatColor.GREEN + "Open" : ChatColor.RED + "Closed")
						.onLeftClick((e) -> FoliaScheduler.runEntity(Wormholes.instance, p, () -> {
							window.close();

							if(isLinkedToRemote(target))
							{
								unlink();
								Wormholes.effectManager.playNotificationSuccess(ChatColor.YELLOW + getName() + " unlinked from " + target.getName() + ".", getStructure().getCenter());
							}
							else
							{
								setDestination(target);
								Wormholes.effectManager.playNotificationSuccess(ChatColor.GREEN + getName() + " linked to " + target.getName() + " on " + target.getServer().getName() + ".", getStructure().getCenter());
							}
						})));
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
		p.sendMessage(ChatColor.AQUA + "Type the new portal name in chat (or 'cancel'):");
		Wormholes.awaitChatInput(p, (text) -> {
			if (text == null || text.equalsIgnoreCase("cancel")) {
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
		p.sendMessage(Wormholes.tag + "Look in a direction then left click to apply.");
		p.sendMessage(Wormholes.tag + "Shift-Left click to cancel.");
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
					sendShortTitle(p, ChatColor.GRAY + "" + ChatColor.BOLD + chosenDirection.toString());
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
					Wormholes.effectManager.playNotificationSuccess(ChatColor.GREEN + getName() + "'s direction changed to " + getDirection().toString() + ".", getStructure().getCenter());
				}

				directionChanger = null;
				chosenDirection = null;
				chosenLook = null;
				e.getPlayer().sendMessage(Wormholes.tag + (cancelled ? "Cancelled" : "Direction set"));
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
		if(projectionMode == ProjectionMode.OFF)
		{
			return false;
		}
		if(projectionMode == ProjectionMode.MIRROR)
		{
			return true;
		}
		return !isInboundDisabledByOneWay();
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
		else if(dimensionalPortalKind.isManagedPortal()
				&& (normalized == ProjectionMode.MIRROR || !normalized.isAllowedFor(PortalType.PORTAL)))
		{
			normalized = ProjectionMode.ON;
		}
		if(!normalized.isAllowedFor(getType()))
		{
			normalized = ProjectionMode.ON;
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

	private static ProjectionMode resolveProjectionMode(JSONObject j)
	{
		if(j.has("projectionMode"))
		{
			return ProjectionMode.fromName(j.getString("projectionMode"));
		}
		return ProjectionMode.ON;
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

	public boolean isInboundDisabledByOneWay()
	{
		ITunnel activeTunnel = tunnel;
		if(activeTunnel == null)
		{
			return false;
		}
		IPortal destination = activeTunnel.getDestination();
		if(!(destination instanceof ILocalPortal localDestination))
		{
			return false;
		}
		if(localDestination.getProjectionMode() != ProjectionMode.ONE_WAY)
		{
			return false;
		}
		ITunnel destinationTunnel = localDestination.getTunnel();
		if(destinationTunnel == null)
		{
			return false;
		}
		IPortal destinationOfDestination = destinationTunnel.getDestination();
		if(destinationOfDestination == null)
		{
			return false;
		}
		return getId().equals(destinationOfDestination.getId());
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
		WormholesAudience.sendActionBar(player, Component.text("Only the portal owner or an administrator can edit this portal.", NamedTextColor.RED));
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
