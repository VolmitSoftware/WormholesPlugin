package art.arcane.wormholes;

import java.util.Set;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.Vector;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;

import art.arcane.wormholes.portal.ILocalPortal;
import art.arcane.volmlib.util.scheduling.AR;
import art.arcane.volmlib.util.scheduling.FoliaScheduler;
import art.arcane.wormholes.util.Area;
import art.arcane.volmlib.util.collection.KList;
import art.arcane.wormholes.util.MSound;
import art.arcane.wormholes.util.ParticleEffect;

public class EffectManager implements Listener
{
	public EffectManager()
	{
		Wormholes.v("Starting Effect Manager");

		new AR()
		{
			@Override
			public void run()
			{
				for(Player player : Bukkit.getOnlinePlayers())
				{
					FoliaScheduler.runEntity(Wormholes.instance, player, () -> scanLookingPortalsFor(player));
				}
			}
		};
	}

	private void scanLookingPortalsFor(Player player)
	{
		ItemStack handItem = player.getInventory().getItemInMainHand();
		boolean holdingWand = Wormholes.blockManager.isSame(handItem, Wormholes.blockManager.getWand());

		for(ILocalPortal portal : Wormholes.portalManager.getLocalPortals())
		{
			if(portal.isLookingAt(player))
			{
				Location portalCenter = portal.getCenter();
				if(portalCenter == null)
				{
					continue;
				}

				FoliaScheduler.runRegion(Wormholes.instance, portalCenter, () -> portal.onLooking(player, holdingWand));
			}
		}
	}

	@EventHandler
	public void on(PlayerInteractEvent e)
	{
		ItemStack handItem = e.getPlayer().getInventory().getItemInMainHand();
		if(!Wormholes.blockManager.isSame(handItem, Wormholes.blockManager.getWand()))
		{
			return;
		}

		for(ILocalPortal j : Wormholes.portalManager.getLocalPortals())
		{
			if(j.isLookingAt(e.getPlayer()))
			{
				j.onWanded(e.getPlayer());
			}
		}
	}

	public void playNotificationFail(String message, Player p)
	{
		Component component = LegacyComponentSerializer.legacySection().deserialize(message).colorIfAbsent(NamedTextColor.RED);
		p.sendActionBar(component);
	}

	public void playNotificationFail(String message, Location l)
	{
		for(Player i : new Area(l, 24).getNearbyPlayers())
		{
			playNotificationFail(message, i);
		}
	}

	public void playNotificationSuccess(String message, Location l)
	{
		for(Player i : new Area(l, 24).getNearbyPlayers())
		{
			playNotificationSuccess(message, i);
		}
	}

	public void playNotificationSuccess(String message, Player p)
	{
		Component component = LegacyComponentSerializer.legacySection().deserialize(message).colorIfAbsent(NamedTextColor.GREEN);
		p.sendActionBar(component);
	}

	public void playNotification(ItemStack is, String message, Player p)
	{
		Component component = LegacyComponentSerializer.legacySection().deserialize(message);
		p.sendActionBar(component);
	}

	public void playPortalBlockPlaced(Block block)
	{
		block.getWorld().playSound(block.getLocation().clone().add(0.5, 0.5, 0.5), MSound.FRAME_FILL.bukkitSound(), 1.2f, 1.1f + ((float) (Math.random() * 0.2)));
	}

	public void playPortalBlockDestroyed(Block block)
	{
		block.getWorld().playSound(block.getLocation().clone().add(0.5, 0.5, 0.5), MSound.EYE_DEATH.bukkitSound(), 0.7f, 1.46f + ((float) (Math.random() * 0.2)));
		block.getWorld().playSound(block.getLocation().clone().add(0.5, 0.5, 0.5), MSound.GLASS.bukkitSound(), 0.7f, 1.55f + ((float) (Math.random() * 0.2)));
	}

	public void playPortalOpen(Set<Block> blocks)
	{
		Block block = new KList<Block>(blocks).getRandom();
		block.getWorld().playSound(block.getLocation().clone().add(0.5, 0.5, 0.5), MSound.FRAME_SPAWN.bukkitSound(), 2.5f, 1.0f + ((float) (Math.random() * 0.1)));
	}

	public void playPortalFailOpen(Set<Block> blocks)
	{
		Block block = new KList<Block>(blocks).getRandom();

		for(int i = 0; i < 4; i++)
		{
			block.getWorld().playSound(block.getLocation().clone().add(0.5, 0.5, 0.5), MSound.AMBIENCE_CAVE.bukkitSound(), 2.5f, 0.5f + ((float) (Math.random() * 1.45)));
		}
	}

	public void playPortalFailRefund(Block block)
	{
		ParticleEffect.EXPLOSION_LARGE.display(0f, 1, block.getLocation().clone().add(0.5, 0.5, 0.5), 32);
		block.getWorld().playSound(block.getLocation().clone().add(0.5, 0.5, 0.5), MSound.EXPLODE.bukkitSound(), 0.7f, (float) (1.6 + ((float) (Math.random() * 0.35))));
		block.getWorld().playSound(block.getLocation().clone().add(0.5, 0.5, 0.5), MSound.GLASS.bukkitSound(), 1.2f, (float) (0.25 + ((float) (Math.random() * 0.95))));
	}

	public void playPortalOpening(int size, Block cursor)
	{
		for(int i = 0; i < 32; i++)
		{
			Vector up = new Vector(0, 1, 0);
			up.add(Vector.getRandom().subtract(Vector.getRandom()).clone().multiply(0.225));
			up.multiply(4.625);
			ParticleEffect.BLOCK_DUST.display(new ParticleEffect.BlockData(Material.NETHER_PORTAL, (byte) 0), up.multiply(0.115), 1, cursor.getLocation().clone().add(0.5, 0.5, 0.5), 22);
			ParticleEffect.PORTAL.display(up.multiply(5.2), 1, cursor.getLocation().clone().add(0.5, 0.5, 0.5), 32d);
		}

		cursor.getWorld().playSound(cursor.getLocation().clone().add(0.5, 0.5, 0.5), Sound.BLOCK_CHORUS_FLOWER_GROW, 2.2f, 0.01f + ((float) size / 12F) + ((float) (Math.random() * 0.01)));
		cursor.getWorld().playSound(cursor.getLocation().clone().add(0.5, 0.5, 0.5), Sound.BLOCK_CHORUS_FLOWER_DEATH, 2.2f, 0.01f + ((float) size / 22F) + ((float) (Math.random() * 0.01)));
	}
}
