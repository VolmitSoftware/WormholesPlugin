package com.volmit.wormholes.portal;

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

import com.volmit.wormholes.Settings;
import com.volmit.wormholes.Wormholes;
import com.volmit.wormholes.geometry.Raycast;
import com.volmit.wormholes.inventory.AnvilText;
import com.volmit.wormholes.inventory.UIElement;
import com.volmit.wormholes.inventory.UIPaneDecorator;
import com.volmit.wormholes.inventory.UIWindow;
import com.volmit.wormholes.inventory.Window;
import com.volmit.wormholes.inventory.WindowResolution;
import com.volmit.wormholes.nms.NMP;
import com.volmit.wormholes.project.ProjectionMatrix;
import com.volmit.wormholes.util.AR;
import com.volmit.wormholes.util.Axis;
import com.volmit.wormholes.util.AxisAlignedBB;
import com.volmit.wormholes.util.C;
import com.volmit.wormholes.util.Direction;
import com.volmit.wormholes.util.F;
import com.volmit.wormholes.util.FinalBoolean;
import com.volmit.wormholes.util.FinalInteger;
import com.volmit.wormholes.util.GList;
import com.volmit.wormholes.util.J;
import com.volmit.wormholes.util.M;
import com.volmit.wormholes.util.MSound;
import com.volmit.wormholes.util.MaterialBlock;
import com.volmit.wormholes.util.ParticleEffect;
import com.volmit.wormholes.util.PhantomSpinner;
import com.volmit.wormholes.util.RString;
import com.volmit.wormholes.util.VectorMath;

public class LocalPortal extends Portal implements ILocalPortal, IProgressivePortal, IFXPortal, IOwnablePortal, Listener
{
	private final PhantomSpinner spinner;
	private final PortalStructure structure;
	private final ProjectionMatrix matrix;
	private final PortalType type;
	private UUID owner;
	private ITunnel tunnel;
	private boolean open;
	private boolean progressing;
	private String progress;
	private Player directionChanger;
	private Direction chosenDirection;

	public LocalPortal(UUID id, PortalType type, PortalStructure structure)
	{
		super(id, structure.getCenter().toVector());
		this.owner = id;
		spinner = new PhantomSpinner(C.YELLOW, C.GOLD, C.RED);
		this.type = type;
		this.structure = structure;
		open = false;
		progressing = false;
		progress = "Idle";
		tunnel = null;
		directionChanger = null;
		chosenDirection = null;
		setName(F.capitalize(getType().name().toLowerCase()) + " " + id.toString().substring(0, 4));
		matrix = new ProjectionMatrix(this);
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
		GList<Vector> vxz = new GList<Vector>();

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
				vx.add(vxz.pickRandom());
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
				NMP.MESSAGE.title(p, "", spinner.toString() + C.RESET + C.GRAY + progress, 0, 2, 3);
			}

			else
			{
				NMP.MESSAGE.title(p, "", getRouter(false), 0, 2, 3);
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
			}

			else
			{
				tunnel = new DimensionalTunnel(p);
			}
		}

		else if(portal instanceof IRemotePortal)
		{
			tunnel = new UniversalTunnel((IRemotePortal) portal);
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

		J.sr(new Runnable()
		{
			@Override
			public void run()
			{
				f.sub(1);

				if(f.get() > 0)
				{
					showProgress("Destroying " + getName() + " in " + C.RED + " " + F.time(50 * f.get(), 0));
				}

				else if(f.get() == 0)
				{
					Wormholes.portalManager.removeLocalPortal(LocalPortal.this);
					Wormholes.constructionManager.destroy(LocalPortal.this);
				}
			}
		}, 0, 105);
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
		Window window = new UIWindow(p)
				.setTitle(getRouter(true))
				.setResolution(WindowResolution.W3_H3)
				.setViewportHeight(3);
		window.setElement(0, 1, new UIElement("set-destination")
				.setName(C.GOLD + "" + C.BOLD + "Set Focus")
				.addLore(C.GRAY + "Choose a portal destination for")
				.addLore(C.GRAY + "this portal.")
				.setMaterial(new MaterialBlock(Material.EYE_OF_ENDER))
				.setCount(Wormholes.portalManager.getAccessableCount(getType()) - 1)
				.onLeftClick((e) -> uiChooseDestination(p)))
		.setElement(0, 0, new UIElement("set-name")
				.setName(C.GREEN + "" + C.BOLD + "Set Name")
				.addLore(C.GRAY + "Change the portal name ")
				.setMaterial(new MaterialBlock(Material.NAME_TAG))
				.onLeftClick((e) -> uiChangeName(p)))
		.setElement(1, 1, new UIElement("set-direction")
				.setName(C.BLUE + "" + C.BOLD + "Change Direction")
				.addLore(C.GRAY + "Change the portal facing direction")
				.addLore(C.GRAY + "Currently Facing " + C.BLUE + "" + C.BOLD + getDirection().toString())
				.setMaterial(new MaterialBlock(Material.COMPASS))
				.onLeftClick((e) ->
				{
					uiChangeDirection(p);
					window.close();
				}))
		.setElement(1, 2, new UIElement("destroy")
				.setName(C.RED + "" + C.BOLD + "Destroy Portal")
				.addLore(C.GRAY + "Destroys the portal and ")
				.addLore(C.GRAY + "drops its portal blocks.")
				.addLore(C.GRAY + " ")
				.addLore(C.RED + "" + C.UNDERLINE + "Shift + Left Click")
				.setMaterial(new MaterialBlock(Material.SULPHUR))
				.onShiftLeftClick((e) ->
				{
					window.close();
					destroy();
				}))
		.setDecorator(new UIPaneDecorator(C.DARK_GRAY));
		//@done

		return window;
	}

	@Override
	public void uiChooseDestination(Player p)
	{
		//@builder
		Window window = new UIWindow(p)
				.setTitle(getRouter(true))
				.setResolution(WindowResolution.W9_H6)
				.setDecorator(new UIPaneDecorator(C.DARK_GRAY))
				.onClosed((w) -> J.s(() -> {
					uiOpenPortalMenu(p);
				}));
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

			//@builder
			window.setElement(window.getPosition(pos), window.getRow(pos), new UIElement("portal-" + pos)
					.setMaterial(new MaterialBlock(Material.ENDER_PEARL))
					.setEnchanted(hasTunnel() && getTunnel().getDestination().getId().equals(i.getId()))
					.setName(C.GOLD + "" + i.getName())
					.addLore(C.GRAY + "at " + i.getStructure().getCenter().getBlockX() + ", " + i.getStructure().getCenter().getBlockY() + ", " + i.getStructure().getCenter().getBlockZ() + " in " + i.getStructure().getWorld().getName() + " Facing " + i.getDirection().toString())
					.onLeftClick((e) -> J.s(() -> {
						window.close();

						if(hasTunnel() && getTunnel().getDestination().getId().equals(i.getId()))
						{
							tunnel = null;
						}

						else
						{
							setDestination(i);
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
		AnvilText.getText(p, C.stripColor(getName()), new RString()
		{
			@Override
			public void onComplete(String text)
			{
				setName(text);
				uiOpenPortalMenu(p);
			}
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
			str += (dark ? C.GRAY : C.YELLOW) + "" + C.BOLD + source.getName();
			str += C.GRAY + " -> ";
		}

		str += (dark ? C.BLACK : C.GOLD) + "" + C.BOLD + getName();

		if(hasTunnel())
		{
			str += C.GRAY + " -> ";
			str += (dark ? C.GRAY : C.GRAY) + "" + C.BOLD + getTunnel().getDestination().getName();
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

				chosenDirection = Direction.getDirection(p.getLocation().getDirection());
				NMP.MESSAGE.title(p, "", C.GRAY + "" + C.BOLD + chosenDirection.toString(), 0, 3, 3);
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

				if(!e.getPlayer().isSneaking())
				{
					setDirection(chosenDirection);
					Wormholes.effectManager.playNotificationSuccess(C.GREEN + getName() + "'s direction changed to " + getDirection().toString() + ".", getStructure().getCenter());
				}

				directionChanger = null;
				chosenDirection = null;
				e.getPlayer().sendMessage(Wormholes.tag + "Cancelled");
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
		return getType().equals(PortalType.GATEWAY) || getType().equals(PortalType.WORMHOLE);
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
	public ProjectionMatrix getMatrix()
	{
		return matrix;
	}
}
