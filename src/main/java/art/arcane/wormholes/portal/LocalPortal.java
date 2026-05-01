package art.arcane.wormholes.portal;

import java.io.File;
import java.io.IOException;
import java.util.UUID;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.util.Vector;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.title.Title;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;

import java.time.Duration;

import art.arcane.wormholes.Settings;
import art.arcane.wormholes.Wormholes;
import art.arcane.wormholes.geometry.Raycast;
import art.arcane.volmlib.util.scheduling.AR;
import art.arcane.volmlib.util.scheduling.FoliaScheduler;
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
import art.arcane.wormholes.util.VectorMath;

import net.md_5.bungee.api.ChatColor;

public class LocalPortal extends Portal implements ILocalPortal, IProgressivePortal, IFXPortal, IOwnablePortal, Listener
{
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
	private boolean needsSaving;
	private boolean projecting;
	private AxisAlignedBB view;

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
		setName(F.capitalize(getType().name().toLowerCase()) + " " + id.toString().substring(0, 4));
		needsSaving = false;
		projecting = true;
		view = computeView();
	}

	@Override
	public void saveJSON(JSONObject j)
	{
		super.saveJSON(j);
		j.put("structure", getStructure().toJSON());
		j.put("type", type.name());
		j.put("owner", getOwner().toString());
		j.put("projecting", projecting);

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
		type = PortalType.valueOf(j.getString("type"));
		owner = UUID.fromString(j.getString("owner"));
		projecting = !j.has("projecting") || j.getBoolean("projecting");
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
	public void update()
	{
		if(isOpen())
		{
			playEffect(PortalEffect.AMBIENT_OPEN);
			updateCaptures();

			if(hasTunnel() && !getTunnel().isValid())
			{
				tunnel = null;
				close();
			}
		}

		else
		{
			playEffect(PortalEffect.AMBIENT_CLOSED);

			if(hasTunnel())
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

		for(Entity i : getStructure().getCaptureZone().getEntities(getStructure().getWorld()))
		{
			getTunnel().push(rayTeleport(i));
		}
	}

	private Traversive rayTeleport(Entity i)
	{
		Vector velocity = Wormholes.traversableManager.getVelocity(i);
		Location start = i.getLocation();
		Location end = start.clone().add(velocity);
		Direction inFace = Direction.getDirection(velocity);
		Traversive[] f = new Traversive[1];

		new Raycast(start, end, 0.09)
		{
			@Override
			public boolean shouldContinue(Location l)
			{
				if(getStructure().getArea().contains(l))
				{
					playEffect(PortalEffect.PUSH, l);
					f[0] = new Traversive(i, getDirection(), velocity, i.getLocation().getDirection(), VectorMath.directionNoNormal(getStructure().getArea().getFace(inFace).center().toLocation(getStructure().getWorld()), l));
					return false;
				}

				return true;
			}
		};
		return f[0];
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
		if(this.open != open)
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
				phase(Direction.getDirection(location.getDirection()).getAxis(), ParticleEffect.WATER_WAKE, location, 0.125f);
				location.getWorld().playSound(location, MSound.ENDERMAN_TELEPORT.bukkitSound(), 0.5f, 1.7f + (float) (Math.random() * 0.2));
				location.getWorld().playSound(location, MSound.ENDERMAN_TELEPORT.bukkitSound(), 0.5f, 1.5f + (float) (Math.random() * 0.2));
				location.getWorld().playSound(location, MSound.ENDERMAN_TELEPORT.bukkitSound(), 0.5f, 1.3f + (float) (Math.random() * 0.2));

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
						if(getStructure().getArea().contains(l))
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
		this.direction = d;
		save();
	}

	@Override
	public void receive(Traversive t)
	{
		if(t.getType().equals(TraversableType.PLAYER) || t.getType().equals(TraversableType.ENTITY))
		{
			Entity p = (Entity) t.getObject();
			Vector outVelocity = t.getOutVelocity(getDirection());
			Vector outLook = t.getOutLook(getDirection());
			Vector outPlane = t.getOutPlane(getDirection());
			Direction dx = Direction.closest(outVelocity);
			AxisAlignedBB face = getStructure().getFace(dx);
			Location exit = face.center().toLocation(getStructure().getWorld()).subtract(outPlane);
			exit.setDirection(outLook);
			p.teleport(exit.clone().add(dx.toVector().normalize().multiply(1.25)));
			p.setVelocity(outVelocity);
			playEffect(PortalEffect.PUSH, exit);

			if(Settings.DEBUG_TRAVERSABLES)
			{
				p.sendMessage("     ");
				p.sendMessage("     ");
				p.sendMessage("ANG: " + t.getInDirection().toString() + " -> " + getDirection().toString());
				p.sendMessage("FCE: " + Direction.getDirection(t.getInVelocity()).reverse().toString() + " -> " + Direction.closest(outVelocity).toString());
				p.sendMessage("MOV: " + Direction.getDirection(t.getInVelocity()).toString() + " -> " + Direction.getDirection(outVelocity).toString());
				p.sendMessage("LOK: " + Direction.getDirection(t.getInLook()).toString() + " -> " + Direction.getDirection(outLook).toString());
				p.sendMessage("PFL: " + "(" + F.f(t.getInPlane().getX(), 1) + ", " + F.f(t.getInPlane().getY(), 1) + ", " + F.f(t.getInPlane().getZ(), 1) + ") -> (" + F.f(outPlane.getX(), 1) + ", " + F.f(outPlane.getY(), 1) + ", " + F.f(outPlane.getZ(), 1) + ")");
				p.sendMessage("MOT: " + "(" + F.f(t.getInVelocity().getX(), 1) + ", " + F.f(t.getInVelocity().getY(), 1) + ", " + F.f(t.getInVelocity().getZ(), 1) + ") -> (" + F.f(outVelocity.getX(), 1) + ", " + F.f(outVelocity.getY(), 1) + ", " + F.f(outVelocity.getZ(), 1) + ")");
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
				Wormholes.portalManager.removeLocalPortal(LocalPortal.this);
				Wormholes.constructionManager.destroy(LocalPortal.this);
				deleteData();
			}
		};

		FoliaScheduler.runRegion(Wormholes.instance, anchor, tickHolder[0], 1L);
	}

	@Override
	public boolean hasTunnel()
	{
		return getTunnel() != null;
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
		//@builder
		Window window = new UIWindow(Wormholes.instance, p)
				.setTitle(getRouter(true))
				.setResolution(WindowResolution.W3_H3)
				.setViewportHeight(3);
		window.setElement(0, 1, new UIElement("set-destination")
				.setName(ChatColor.GOLD + "" + ChatColor.BOLD + "Set Focus")
				.addLore(ChatColor.GRAY + "Choose a portal destination for")
				.addLore(ChatColor.GRAY + "this portal.")
				.setMaterial(new MaterialBlock(Material.ENDER_EYE))
				.setCount(Math.max(1, Wormholes.portalManager.getAccessableCount(getType()) - 1))
				.onLeftClick((e) -> uiChooseDestination(p)))
		.setElement(0, 0, new UIElement("set-name")
				.setName(ChatColor.GREEN + "" + ChatColor.BOLD + "Set Name")
				.addLore(ChatColor.GRAY + "Change the portal name ")
				.setMaterial(new MaterialBlock(Material.NAME_TAG))
				.onLeftClick((e) -> uiChangeName(p)))
		.setElement(1, 1, new UIElement("set-direction")
				.setName(ChatColor.BLUE + "" + ChatColor.BOLD + "Change Direction")
				.addLore(ChatColor.GRAY + "Change the portal facing direction")
				.addLore(ChatColor.GRAY + "Currently Facing " + ChatColor.BLUE + "" + ChatColor.BOLD + getDirection().toString())
				.setMaterial(new MaterialBlock(Material.COMPASS))
				.onLeftClick((e) ->
				{
					uiChangeDirection(p);
					window.close();
				}))
		.setElement(1, 2, new UIElement("destroy")
				.setName(ChatColor.RED + "" + ChatColor.BOLD + "Destroy Portal")
				.addLore(ChatColor.GRAY + "Destroys the portal and ")
				.addLore(ChatColor.GRAY + "drops its portal blocks.")
				.addLore(ChatColor.GRAY + " ")
				.addLore(ChatColor.RED + "" + ChatColor.UNDERLINE + "Shift + Left Click")
				.setMaterial(new MaterialBlock(Material.GUNPOWDER))
				.onShiftLeftClick((e) ->
				{
					window.close();
					destroy();
				}))
		.setElement(1, 0, new UIElement("toggle-projections")
				.setName(isProjecting() ? ChatColor.LIGHT_PURPLE + "" + ChatColor.BOLD + "Projections Enabled" : ChatColor.DARK_PURPLE + "" + ChatColor.BOLD + "Projections Disabled")
				.setEnchanted(isProjecting())
				.setMaterial(new MaterialBlock(isProjecting() ? Material.REDSTONE_TORCH : Material.TORCH))
				.addLore(ChatColor.GRAY + "Project blocks from the destination")
				.addLore(ChatColor.GRAY + "portal through this portal.")
				.onLeftClick((e) ->
				{
					setProjecting(!isProjecting());
					e.setName(isProjecting() ? ChatColor.LIGHT_PURPLE + "" + ChatColor.BOLD + "Projections Enabled" : ChatColor.DARK_PURPLE + "" + ChatColor.BOLD + "Projections Disabled");
					e.setEnchanted(isProjecting());
					e.setMaterial(new MaterialBlock(isProjecting() ? Material.REDSTONE_TORCH : Material.TORCH));
					window.updateInventory();
				}))
		.setDecorator(new UIPaneDecorator(Material.GRAY_STAINED_GLASS_PANE));
		//@done

		return window;
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
						}
						else
						{
							setDestination(target);
						}

						window.close();
					})));
			//@done
			pos++;
		}

		window.setVisible(true);
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

					chosenDirection = Direction.getDirection(p.getLocation().getDirection());
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
					setDirection(chosenDirection);
					Wormholes.effectManager.playNotificationSuccess(ChatColor.GREEN + getName() + "'s direction changed to " + getDirection().toString() + ".", getStructure().getCenter());
				}

				directionChanger = null;
				chosenDirection = null;
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
		return projecting;
	}

	@Override
	public void setProjecting(boolean projecting)
	{
		this.projecting = projecting;
		save();
	}

	@Override
	public AxisAlignedBB getView()
	{
		if(view == null)
		{
			view = computeView();
		}
		return view;
	}

	private AxisAlignedBB computeView()
	{
		double range = Settings.PROJECTION_RANGE;
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
		player.showTitle(title);
	}
}
