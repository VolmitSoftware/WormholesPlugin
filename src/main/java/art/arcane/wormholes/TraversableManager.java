package art.arcane.wormholes;

import java.util.UUID;

import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.util.Vector;

import art.arcane.volmlib.util.collection.KMap;
import art.arcane.wormholes.util.VectorMath;

public class TraversableManager implements Listener
{
	private final KMap<UUID, Vector> velocities;

	public TraversableManager()
	{
		Wormholes.v("Starting Traversable Manager");
		velocities = new KMap<>();
	}

	@EventHandler
	public void on(PlayerMoveEvent e)
	{
		impulse(e.getPlayer(), VectorMath.directionNoNormal(e.getFrom(), e.getTo()));
	}

	@EventHandler
	public void on(PlayerQuitEvent e)
	{
		velocities.remove(e.getPlayer().getUniqueId());
	}

	public Vector getVelocity(Player p)
	{
		Vector velocity = velocities.get(p.getUniqueId());
		return velocity != null ? velocity : new Vector();
	}

	public void impulse(Player p, Vector v)
	{
		velocities.put(p.getUniqueId(), v);
	}

	public Vector getVelocity(Entity i)
	{
		if(i instanceof Player)
		{
			return getVelocity((Player) i);
		}

		return i.getVelocity();
	}
}
