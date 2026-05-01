package art.arcane.wormholes;

import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.util.Vector;

import art.arcane.volmlib.util.collection.KMap;
import art.arcane.wormholes.util.VectorMath;

public class TraversableManager implements Listener
{
	private KMap<Player, Vector> velocities;

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

	public Vector getVelocity(Player p)
	{
		return velocities.containsKey(p) ? velocities.get(p) : new Vector();
	}

	public void impulse(Player p, Vector v)
	{
		velocities.put(p, v);
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
