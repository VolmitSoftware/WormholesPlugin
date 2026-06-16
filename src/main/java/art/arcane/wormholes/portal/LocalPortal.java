package art.arcane.wormholes.portal;

import java.io.File;
import java.io.IOException;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.IntConsumer;
import java.util.function.IntSupplier;

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

import java.time.Duration;

import art.arcane.wormholes.Settings;
import art.arcane.wormholes.Wormholes;
import art.arcane.wormholes.geometry.Raycast;
import art.arcane.wormholes.service.WormholesAudience;
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
import art.arcane.volmlib.util.math.FinalInteger;
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
	private static final long TELEPORT_COOLDOWN_MILLIS = 1000L;
	private static final long POST_TRAVERSAL_GRACE_MILLIS = 10_000L;
	private static final int DEFAULT_NETWORK_VIEW_DEPTH = 64;
	private static final int DEFAULT_NETWORK_VIEW_LATERAL_PAD = 48;
	private static final int DEFAULT_NETWORK_VIEW_HEARTBEAT_TICKS = 60;
	private static final int DEFAULT_NETWORK_VIEW_ENTITY_INTERVAL_TICKS = 10;
	private static final int DEFAULT_NETWORK_VIEW_UNSUBSCRIBE_GRACE_SECONDS = 30;
	private static final String DEFAULT_NETWORK_VIEW_FALLBACK_BLOCK = "minecraft:air";
	private static final ParticleEffect.OrdinaryColor OUTLINE_PARTICLE_COLOR = new ParticleEffect.OrdinaryColor(150, 80, 255);
	private static final ConcurrentHashMap<UUID, Long> TELEPORT_COOLDOWNS = new ConcurrentHashMap<UUID, Long>();
	private static final ConcurrentHashMap<UUID, Long> POST_TRAVERSAL_GRACE = new ConcurrentHashMap<UUID, Long>();

	private PhantomSpinner spinner;
	private PortalStructure structure;
	private PortalType type;
	private UUID owner;
	private ITunnel tunnel;
	private boolean open;
	private boolean progressing;
	private String progress;
	private Player directionChanger;
	private Direction chosenDirection;
	private Vector chosenLook;
	private boolean needsSaving;
	private ProjectionMode projectionMode;
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
	private final java.util.Map<UUID, UIWindow> openMenus = new java.util.concurrent.ConcurrentHashMap<UUID, UIWindow>();

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
		directionChanger = null;
		chosenDirection = null;
		chosenLook = null;
		setName(F.capitalize(getType().name().toLowerCase()) + " " + id.toString().substring(0, 4));
		needsSaving = false;
		projectionMode = ProjectionMode.ON;
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
		if(this.type == type)
		{
			return;
		}

		boolean wasGateway = isGateway();
		this.type = type;

		if(wasGateway != isGateway())
		{
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
		if(isOpen())
		{
			playEffect(PortalEffect.AMBIENT_OPEN);
			updateCaptures();

			if(hasTunnel() && !getTunnel().isValid())
			{
				if(getTunnel().getTunnelType() != TunnelType.UNIVERSAL)
				{
					tunnel = null;
				}

				close();
			}
		}

		else
		{
			playEffect(PortalEffect.AMBIENT_CLOSED);

			if((hasTunnel() && getTunnel().isValid()) || projectionMode == ProjectionMode.MIRROR)
			{
				open();
			}
		}

		if(Settings.DEBUG_RENDERING)
		{
			playEffect(PortalEffect.AMBIENT_DEBUG);
		}
	}

	private void updateCaptures()
	{
		if(!isOpen() || !hasTunnel())
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
			Traversive traversive = rayTeleport(i);
			if(traversive == null)
			{
				continue;
			}

			if(!canUseTunnel(i))
			{
				rejectTraversal(i, traversive);
				continue;
			}

			if(isInPostTraversalGrace(i.getUniqueId(), now))
			{
				continue;
			}

			if(isTeleportCoolingDown(i.getUniqueId(), now))
			{
				continue;
			}

			markTeleportCooldown(i.getUniqueId(), now);
			markPostTraversalGrace(i.getUniqueId());
			pushTraversive(traversive);
		}
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
					double relX = start.getX() - getOrigin().getX();
					double relY = start.getY() - getOrigin().getY();
					double relZ = start.getZ() - getOrigin().getZ();
					boolean frontSide = ((relX * getFrame().getNormal().x()) + (relY * getFrame().getNormal().y()) + (relZ * getFrame().getNormal().z())) >= 0.0D;
					f[0] = new Traversive(i, getFrame().view(frontSide), getOrigin(), l.toVector(), velocity, i.getLocation().getDirection(), frontSide);
					return false;
				}

				return true;
			}
		};
		return f[0];
	}

	private boolean canUseTunnel(Entity entity)
	{
		if(!canDepart(entity))
		{
			return false;
		}
		if(!hasTunnel())
		{
			return false;
		}
		IPortal destination = getTunnel().getDestination();
		if(destination instanceof ILocalPortal localDestination)
		{
			return localDestination.canArrive(entity);
		}
		return true;
	}

	private void pushTraversive(Traversive traversive)
	{
		if(getTunnel() instanceof UniversalTunnel universal && Wormholes.traversalService != null && traversive.getObject() instanceof Entity entity)
		{
			if(entity instanceof Player player)
			{
				Wormholes.traversalService.beginPlayerHandoff(player, universal, traversive, this);
				return;
			}
			Wormholes.traversalService.beginEntityTransfer(entity, universal, traversive, this);
			return;
		}

		getTunnel().push(traversive);
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
	public void setOpen(boolean open)
	{
		boolean changed = this.open != open;
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

		this.open = open;

		if(changed && isGateway() && Wormholes.portalSyncService != null)
		{
			Wormholes.portalSyncService.broadcastPortal(this);
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
				getStructure().getCenter().getWorld().playSound(getStructure().getCenter(), MSound.ECHEST_CLOSE.bukkitSound(), 2.25f, 0.1f);
				getStructure().getCenter().getWorld().playSound(getStructure().getCenter(), MSound.ECHEST_CLOSE.bukkitSound(), 2.25f, 1.7f);
				break;
			case OPEN:
				getStructure().getCenter().getWorld().playSound(getStructure().getCenter(), MSound.FRAME_SPAWN.bukkitSound(), 2.25f, 0.1f);
				getStructure().getCenter().getWorld().playSound(getStructure().getCenter(), MSound.FRAME_SPAWN.bukkitSound(), 2.25f, 1.6f);
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
		save();
		syncGatewayTickets();
	}

	@Override
	public void setFrame(PortalFrame frame)
	{
		applyFrame(frame);
		save();
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
			p.teleport(target, PlayerTeleportEvent.TeleportCause.PLUGIN);
			p.setVelocity(outVelocity);
			art.arcane.wormholes.service.WormholesTelemetry.countTraversal();
			markTeleportCooldown(p.getUniqueId(), System.currentTimeMillis());
			playEffect(PortalEffect.PUSH, exit);

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
		art.arcane.wormholes.service.WormholesTelemetry.countTraversal();
		playEffect(PortalEffect.PUSH, entity.getLocation());
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
		return outgoingTraversalsEnabled && allowsPortalPermission(entity);
	}

	@Override
	public boolean canArrive(Entity entity)
	{
		return incomingTraversalsEnabled && allowsPortalPermission(entity);
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
		TELEPORT_COOLDOWNS.put(entityId, Long.valueOf(now + TELEPORT_COOLDOWN_MILLIS));
	}

	public static void markPostTraversalGrace(UUID entityId)
	{
		POST_TRAVERSAL_GRACE.put(entityId, Long.valueOf(System.currentTimeMillis() + POST_TRAVERSAL_GRACE_MILLIS));
	}

	public static boolean isInPostTraversalGrace(UUID entityId, long now)
	{
		Long until = POST_TRAVERSAL_GRACE.get(entityId);
		if(until == null)
		{
			return false;
		}
		if(until.longValue() <= now)
		{
			POST_TRAVERSAL_GRACE.remove(entityId, until);
			return false;
		}
		return true;
	}

	private static void pruneTeleportCooldowns(long now)
	{
		if(TELEPORT_COOLDOWNS.size() < 256 && POST_TRAVERSAL_GRACE.size() < 256)
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
		Iterator<Map.Entry<UUID, Long>> graceIterator = POST_TRAVERSAL_GRACE.entrySet().iterator();
		while(graceIterator.hasNext())
		{
			Map.Entry<UUID, Long> entry = graceIterator.next();
			if(entry.getValue().longValue() <= now)
			{
				POST_TRAVERSAL_GRACE.remove(entry.getKey(), entry.getValue());
			}
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
		tunnel = new UniversalTunnel(serverName, portalId);
		save();
	}

	@Override
	public void destroy()
	{
		FinalInteger f = new FinalInteger(100);
		tunnel = null;

		Location anchor = getCenter();
		Runnable[] tickHolder = new Runnable[1];
		tickHolder[0] = () ->
		{
			f.sub(1);

			if(f.get() > 0)
			{
				showProgress("Destroying " + getName() + " in " + ChatColor.RED + " " + F.time(50 * f.get(), 0));
				FoliaScheduler.runRegion(Wormholes.instance, anchor, tickHolder[0], 1L);
				return;
			}

			if(f.get() == 0)
			{
				if(Wormholes.projectionManager != null)
				{
					Wormholes.projectionManager.removeProjector(LocalPortal.this);
				}
				Wormholes.portalManager.removeLocalPortal(LocalPortal.this);
				Wormholes.effectManager.playNotificationFail(ChatColor.RED + getName() + " Deleted", getStructure().getCenter());
				deleteData();
			}
		};

		FoliaScheduler.runRegion(Wormholes.instance, anchor, tickHolder[0], 1L);
	}

	@Override
	public boolean hasTunnel()
	{
		return getTunnel() != null && getTunnel().getDestination() != null;
	}

	@Override
	public void uiOpenPortalMenu(Player p)
	{
		Window w = uiCreatePortalMenu(p);
		w.setVisible(true);
	}

	@Override
	public Window uiCreatePortalMenu(Player p)
	{
		UIWindow window = new UIWindow(Wormholes.instance, p);
		window.setTitle(getRouter(true));
		window.setResolution(WindowResolution.W9_H6);
		window.setViewportHeight(6);
		window.setDecorator(new UIPaneDecorator(Material.GRAY_STAINED_GLASS_PANE));
		window.onClosed((w) -> openMenus.remove(p.getUniqueId()));
		rebuildPortalMenuElements(window, p);
		openMenus.put(p.getUniqueId(), window);
		return window;
	}

	private void rebuildPortalMenuElements(UIWindow window, Player p)
	{
		window.clearElements();
		window.setElement(0, 0, portalPlacardElement());

		String linkLore = hasTunnel()
				? ChatColor.GRAY + "Currently linked: " + ChatColor.GOLD + getTunnel().getDestination().getName()
				: ChatColor.GRAY + "Currently linked: " + ChatColor.RED + "None";
		window.setElement(-3, 1, new UIElement("set-destination")
				.setName(ChatColor.GOLD + "" + ChatColor.BOLD + "Link Destination")
				.addLore(ChatColor.GRAY + "Choose a portal to link to.")
				.addLore(linkLore)
				.addLore(" ")
				.addLore(ChatColor.DARK_GRAY + "Click to open the link picker.")
				.setMaterial(new MaterialBlock(Material.ENDER_EYE))
				.setCount(Math.max(1, Wormholes.portalManager.getAccessableCount(getType()) - 1))
				.onLeftClick((e) -> uiChooseDestination(p)));

		window.setElement(-1, 1, new UIElement("set-name")
				.setName(ChatColor.GREEN + "" + ChatColor.BOLD + "Rename Portal")
				.addLore(ChatColor.GRAY + "Change the portal name shown on")
				.addLore(ChatColor.GRAY + "links, routers, and notifications.")
				.addLore(ChatColor.GRAY + "Current: " + ChatColor.WHITE + getName())
				.addLore(" ")
				.addLore(ChatColor.DARK_GRAY + "Click to type a new name.")
				.setMaterial(new MaterialBlock(Material.NAME_TAG))
				.onLeftClick((e) -> uiChangeName(p)));

		window.setElement(1, 1, modeOpenerElement(window, p));
		window.setElement(3, 1, projectionsElement(window, p));

		window.setElement(-3, 2, permissionElement(window, p));
		window.setElement(-1, 2, outgoingTransfersElement(window, p));
		window.setElement(1, 2, incomingTransfersElement(window, p));

		window.setElement(-3, 3, directionElement(window, p));
		window.setElement(-1, 3, flipFaceElement(window, p));
		window.setElement(1, 3, rotateCounterClockwiseElement(window, p));
		window.setElement(3, 3, rotateClockwiseElement(window, p));

		window.setElement(0, 5, new UIElement("destroy")
				.setName(ChatColor.RED + "" + ChatColor.BOLD + "Delete Portal")
				.addLore(ChatColor.GRAY + "Removes the portal without")
				.addLore(ChatColor.GRAY + "dropping portal blocks.")
				.addLore(" ")
				.addLore(ChatColor.RED + "" + ChatColor.UNDERLINE + "Shift + Left Click to confirm")
				.setMaterial(new MaterialBlock(Material.GUNPOWDER))
				.onShiftLeftClick((e) ->
				{
					window.close();
					destroy();
				}));

		if(isGateway())
		{
			window.setElement(3, 2, settingsSyncElement(window, p));

			window.setElement(-3, 4, new UIElement("network-view-settings")
					.setName(ChatColor.DARK_AQUA + "" + ChatColor.BOLD + "Network View")
					.addLore(ChatColor.GRAY + "Tune this gateway's streamed")
					.addLore(ChatColor.GRAY + "cross-server projection volume.")
					.addLore(" ")
					.addLore(ChatColor.GRAY + "Depth " + ChatColor.AQUA + networkViewDepth)
					.addLore(ChatColor.GRAY + "Entity Update " + ChatColor.AQUA + networkViewEntityIntervalTicks + "t")
					.addLore(" ")
					.addLore(ChatColor.DARK_GRAY + "Click to open Network View.")
					.setMaterial(new MaterialBlock(Material.SPYGLASS))
					.onLeftClick((e) -> FoliaScheduler.runEntity(Wormholes.instance, p, () ->
					{
						window.close();
						uiOpenNetworkViewMenu(p);
					})));

			window.setElement(-1, 4, new UIElement("export-portal")
					.setName(ChatColor.GOLD + "" + ChatColor.BOLD + "Export Code")
					.addLore(ChatColor.GRAY + "Generates a portal code you can")
					.addLore(ChatColor.GRAY + "paste into another server's")
					.addLore(ChatColor.GRAY + "Import button to link gateways.")
					.addLore(" ")
					.addLore(ChatColor.DARK_GRAY + "Click to copy a fresh code.")
					.setMaterial(new MaterialBlock(Material.PAPER))
					.onLeftClick((e) -> FoliaScheduler.runEntity(Wormholes.instance, p, () ->
					{
						window.close();

						if(Wormholes.importExportService != null)
						{
							Wormholes.importExportService.exportToChat(p, LocalPortal.this);
						}
					})));

			window.setElement(1, 4, new UIElement("import-portal")
					.setName(ChatColor.AQUA + "" + ChatColor.BOLD + "Import Code")
					.addLore(ChatColor.GRAY + "Paste a portal code from another")
					.addLore(ChatColor.GRAY + "server to link this gateway to it.")
					.addLore(" ")
					.addLore(ChatColor.DARK_GRAY + "Click then type or paste the code.")
					.setMaterial(new MaterialBlock(Material.WRITABLE_BOOK))
					.onLeftClick((e) -> FoliaScheduler.runEntity(Wormholes.instance, p, () ->
					{
						window.close();
						p.closeInventory();
						p.sendMessage(ChatColor.AQUA + "Paste the portal code in chat (or 'cancel'):");
						Wormholes.awaitChatInput(p, (text) ->
						{
							if(text == null || text.equalsIgnoreCase("cancel"))
							{
								uiOpenPortalMenu(p);
								return;
							}

							if(Wormholes.importExportService != null)
							{
								Wormholes.importExportService.importCode(p, LocalPortal.this, text);
							}
						});
					})));
		}
	}

	private void uiOpenNetworkViewMenu(Player p)
	{
		Window w = uiCreateNetworkViewMenu(p);
		w.setVisible(true);
	}

	private Window uiCreateNetworkViewMenu(Player p)
	{
		UIWindow window = new UIWindow(Wormholes.instance, p);
		window.setTitle(getRouter(true));
		window.setResolution(WindowResolution.W9_H6);
		window.setViewportHeight(3);
		window.setDecorator(new UIPaneDecorator(Material.GRAY_STAINED_GLASS_PANE));

		window.setElement(0, 0, networkViewPlacardElement());

		window.setElement(-3, 1, networkViewNumberElement(window, p, "network-view-depth", "Capture Radius",
				ChatColor.GRAY + "Blocks captured in every direction around the destination portal (360°), and how far you can see through it.",
				Material.SPYGLASS, this::getNetworkViewDepth, this::setNetworkViewDepth, 4, 16));
		window.setElement(0, 1, networkViewNumberElement(window, p, "network-view-heartbeat", "Full Refresh",
				ChatColor.GRAY + "Ticks between full block refresh broadcasts.",
				Material.CLOCK, this::getNetworkViewHeartbeatTicks, this::setNetworkViewHeartbeatTicks, 10, 60));
		window.setElement(2, 1, networkViewNumberElement(window, p, "network-view-entities", "Entity Update",
				ChatColor.GRAY + "Ticks between entity update broadcasts.",
				Material.ENDER_EYE, this::getNetworkViewEntityIntervalTicks, this::setNetworkViewEntityIntervalTicks, 2, 20));
		window.setElement(4, 1, networkViewNumberElement(window, p, "network-view-grace", "Unsubscribe Grace",
				ChatColor.GRAY + "Seconds to keep streaming after a viewer looks away.",
				Material.REDSTONE, this::getNetworkViewUnsubscribeGraceSeconds, this::setNetworkViewUnsubscribeGraceSeconds, 5, 30));

		window.setElement(-2, 2, networkViewFallbackElement(p, window));
		window.setElement(0, 2, backToPortalMenuElement(window, p));

		return window;
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
					uiOpenNetworkViewMenu(p);
					return;
				}
				String previous = getNetworkViewFallbackBlock();
				String normalized = normalizeNetworkViewFallbackBlock(text);
				setNetworkViewFallbackBlock(normalized);
				if(!previous.equals(getNetworkViewFallbackBlock()))
				{
					notifySetting(p, "Fallback set to " + ChatColor.AQUA + getNetworkViewFallbackBlock());
				}
				uiOpenNetworkViewMenu(p);
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

	private Element networkViewPlacardElement()
	{
		UIElement element = new UIElement("network-view-placard");
		element.setName(ChatColor.DARK_AQUA + "" + ChatColor.BOLD + "Network View");
		element.setMaterial(new MaterialBlock(Material.SPYGLASS));
		element.addLore(ChatColor.GRAY + "Tune this gateway's streamed");
		element.addLore(ChatColor.GRAY + "cross-server projection.");
		element.addLore(" ");
		element.addLore(ChatColor.GRAY + "Larger values = richer view,");
		element.addLore(ChatColor.GRAY + "more bandwidth.");
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
		element.setMaterial(new MaterialBlock(Material.COMPARATOR));
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
			setProjectionMode(getProjectionMode().nextFor(getType()));
			applyProjectionMode(element);
			window.updateInventory();
			if(previous != getProjectionMode())
			{
				notifySetting(viewer, "Projections: " + ChatColor.AQUA + getProjectionMode().getDisplayName());
			}
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
			lore.add(ChatColor.DARK_GRAY + "Click to toggle: Off / On");
		}
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

	private Element outgoingTransfersElement(Window window, Player viewer)
	{
		UIElement element = new UIElement("outgoing-transfers");
		element.onLeftClick((e) ->
		{
			boolean previous = isOutgoingTraversalsEnabled();
			setOutgoingTraversalsEnabled(!previous);
			applyOutgoingTransfersElement(element);
			window.updateInventory();
			if(previous != isOutgoingTraversalsEnabled())
			{
				notifySetting(viewer, "Send Transfers " + (isOutgoingTraversalsEnabled() ? ChatColor.GREEN + "On" : ChatColor.RED + "Off"));
			}
		});
		applyOutgoingTransfersElement(element);
		return element;
	}

	private void applyOutgoingTransfersElement(Element element)
	{
		boolean enabled = isOutgoingTraversalsEnabled();
		element.setName((enabled ? ChatColor.GREEN : ChatColor.RED) + "" + ChatColor.BOLD + "Send Transfers " + (enabled ? "On" : "Off"));
		element.setEnchanted(enabled);
		element.setMaterial(new MaterialBlock(enabled ? Material.ENDER_PEARL : Material.BARRIER));
		KList<String> lore = element.getLore();
		lore.clear();
		lore.add(ChatColor.GRAY + "Controls players, entities, and items");
		lore.add(ChatColor.GRAY + "entering this portal from here.");
		lore.add(" ");
		lore.add(ChatColor.GRAY + "Currently: " + (enabled ? ChatColor.GREEN + "On" : ChatColor.RED + "Off"));
		lore.add(" ");
		lore.add(ChatColor.DARK_GRAY + "Click to toggle.");
	}

	private Element incomingTransfersElement(Window window, Player viewer)
	{
		UIElement element = new UIElement("incoming-transfers");
		element.onLeftClick((e) ->
		{
			boolean previous = isIncomingTraversalsEnabled();
			setIncomingTraversalsEnabled(!previous);
			applyIncomingTransfersElement(element);
			window.updateInventory();
			if(previous != isIncomingTraversalsEnabled())
			{
				notifySetting(viewer, "Receive Transfers " + (isIncomingTraversalsEnabled() ? ChatColor.GREEN + "On" : ChatColor.RED + "Off"));
			}
		});
		applyIncomingTransfersElement(element);
		return element;
	}

	private void applyIncomingTransfersElement(Element element)
	{
		boolean enabled = isIncomingTraversalsEnabled();
		element.setName((enabled ? ChatColor.GREEN : ChatColor.RED) + "" + ChatColor.BOLD + "Receive Transfers " + (enabled ? "On" : "Off"));
		element.setEnchanted(enabled);
		element.setMaterial(new MaterialBlock(enabled ? Material.CHEST : Material.BARRIER));
		KList<String> lore = element.getLore();
		lore.clear();
		lore.add(ChatColor.GRAY + "Controls players, entities, and items");
		lore.add(ChatColor.GRAY + "arriving through this portal.");
		lore.add(" ");
		lore.add(ChatColor.GRAY + "Currently: " + (enabled ? ChatColor.GREEN + "On" : ChatColor.RED + "Off"));
		lore.add(" ");
		lore.add(ChatColor.DARK_GRAY + "Click to toggle.");
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
		//@builder
		Window window = new UIWindow(Wormholes.instance, p)
				.setTitle(getRouter(true))
				.setResolution(WindowResolution.W9_H6)
				.setDecorator(new UIPaneDecorator(Material.GRAY_STAINED_GLASS_PANE))
				.onClosed((w) -> FoliaScheduler.runEntity(Wormholes.instance, p, () -> uiOpenPortalMenu(p)));
		//@done
		int pos = 0;

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

			if(i.getStructure() == null || i.getStructure().getWorld() == null || i.getStructure().getCenter() == null)
			{
				continue;
			}

			final ILocalPortal target = i;
			//@builder
			window.setElement(window.getPosition(pos), window.getRow(pos), new UIElement("portal-" + pos)
					.setMaterial(new MaterialBlock(Material.ENDER_PEARL))
					.setEnchanted(hasTunnel() && getTunnel().getDestination().getId().equals(target.getId()))
					.setName(ChatColor.GOLD + "" + target.getName())
					.addLore(ChatColor.GRAY + "at " + target.getStructure().getCenter().getBlockX() + ", " + target.getStructure().getCenter().getBlockY() + ", " + target.getStructure().getCenter().getBlockZ() + " in " + target.getStructure().getWorld().getName() + " Facing " + target.getDirection().toString())
					.onLeftClick((e) -> FoliaScheduler.runEntity(Wormholes.instance, p, () -> {
						window.close();

						if(hasTunnel() && getTunnel().getDestination().getId().equals(target.getId()))
						{
							tunnel = null;
							save();
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
			for(RemotePortal i : Wormholes.remotePortalRegistry.all())
			{
				if(i.getType() != PortalType.GATEWAY)
				{
					continue;
				}

				final RemotePortal target = i;
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
								tunnel = null;
								save();
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
		if(this.projectionMode == normalized)
		{
			return;
		}
		this.projectionMode = normalized;
		if(Wormholes.projectionManager != null)
		{
			Wormholes.projectionManager.removeProjector(this);
		}
		save();
		broadcastSettingsIfEnabled();
	}

	private static ProjectionMode resolveProjectionMode(JSONObject j)
	{
		if(j.has("projectionMode"))
		{
			return ProjectionMode.fromName(j.getString("projectionMode"));
		}
		return ProjectionMode.ON;
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
		if(outgoingTraversalsEnabled == enabled)
		{
			return;
		}
		outgoingTraversalsEnabled = enabled;
		save();
		broadcastSettingsIfEnabled();
	}

	@Override
	public boolean isIncomingTraversalsEnabled()
	{
		return incomingTraversalsEnabled;
	}

	@Override
		public void setIncomingTraversalsEnabled(boolean enabled)
		{
			if(incomingTraversalsEnabled == enabled)
			{
				return;
		}
			incomingTraversalsEnabled = enabled;
			save();
			broadcastSettingsIfEnabled();
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
			refreshOpenMenus();
		}

		public void refreshOpenMenus()
		{
			if(openMenus.isEmpty())
			{
				return;
			}
			for(java.util.Map.Entry<UUID, UIWindow> entry : openMenus.entrySet())
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
					window.updateInventory();
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
			if(isGateway() && Wormholes.portalSyncService != null && !art.arcane.wormholes.network.PortalSyncService.isApplyingRemote())
			{
				Wormholes.portalSyncService.broadcastSettingsToggle(this);
			}
			refreshOpenMenus();
		}

		private void broadcastSettingsIfEnabled()
		{
			if(!settingsSyncEnabled)
			{
				return;
			}
			if(art.arcane.wormholes.network.PortalSyncService.isApplyingRemote())
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

	public boolean isInboundDisabledByOneWay()
	{
		if(!hasTunnel())
		{
			return false;
		}
		IPortal destination = getTunnel().getDestination();
		if(!(destination instanceof ILocalPortal))
		{
			return false;
		}
		ILocalPortal localDestination = (ILocalPortal) destination;
		if(localDestination.getProjectionMode() != ProjectionMode.ONE_WAY)
		{
			return false;
		}
		if(!localDestination.hasTunnel())
		{
			return false;
		}
		IPortal destinationOfDestination = localDestination.getTunnel().getDestination();
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
		needsSaving = true;
	}

	@Override
	public boolean needsSaving()
	{
		return needsSaving;
	}

	@Override
	public void saveNow() throws IOException
	{
		doSave();
		needsSaving = false;
	}

	private void doSave() throws IOException
	{
		willSave();
		File f = Wormholes.portalManager.getSaveFile(getId());
		f.getParentFile().mkdirs();
		VIO.writeAll(f, toJSON().toString(2));
		Wormholes.v("Saved Portal " + getId().toString() + " (" + getName() + ")");
	}

	@Override
	public void willSave()
	{
		needsSaving = false;
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
		File f = Wormholes.portalManager.getSaveFile(getId());
		f.delete();

		if(f.getParentFile().listFiles().length == 0)
		{
			f.getParentFile().delete();
		}

		if(f.getParentFile().getParentFile().listFiles().length == 0)
		{
			f.getParentFile().getParentFile().delete();
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
