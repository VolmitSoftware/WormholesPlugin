package com.volmit.wormholes.projection;

import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;

import com.volmit.wormholes.Settings;
import com.volmit.wormholes.geometry.Frustum;
import com.volmit.wormholes.portal.Portal;
import com.volmit.wormholes.util.Cuboid;
import com.volmit.wormholes.util.Direction;
import com.volmit.wormholes.util.VectorMath;

public class Viewport
{
	private Frustum frustum;
	private Player p;
	private Portal portal;

	public Viewport(Player p, Portal portal)
	{
		this.p = p;
		this.portal = portal;
		rebuild();
	}

	public Frustum getFrustum()
	{
		return frustum;
	}

	public void rebuild()
	{
		frustum = new Frustum(p.getEyeLocation(), portal.getPosition(), Settings.PROJECTION_SAMPLE_RADIUS);
	}

	public boolean contains(Location l)
	{
		if(portal.getPosition().getPane().contains(l))
		{
			return false;
		}

		return frustum.intersects(l);
	}

	public boolean contains(Block l)
	{
		return contains(l.getLocation());
	}

	public Location getIris()
	{
		return p.getLocation().clone().getBlock().getLocation().clone().add(0.5, 1.7, 0.5);
	}

	public Player getP()
	{
		return p;
	}

	public Portal getPortal()
	{
		return portal;
	}

	@Override
	public int hashCode()
	{
		final int prime = 31;
		int result = 1;
		result = prime * result + ((frustum == null) ? 0 : frustum.hashCode());
		result = prime * result + ((p == null) ? 0 : p.hashCode());
		result = prime * result + ((portal == null) ? 0 : portal.hashCode());
		return result;
	}

	@Override
	public boolean equals(Object obj)
	{
		if(this == obj)
		{
			return true;
		}
		if(obj == null)
		{
			return false;
		}
		if(!(obj instanceof Viewport))
		{
			return false;
		}
		Viewport other = (Viewport) obj;
		if(frustum == null)
		{
			if(other.frustum != null)
			{
				return false;
			}
		}
		else if(!frustum.equals(other.frustum))
		{
			return false;
		}
		if(p == null)
		{
			if(other.p != null)
			{
				return false;
			}
		}
		else if(!p.equals(other.p))
		{
			return false;
		}
		if(portal == null)
		{
			if(other.portal != null)
			{
				return false;
			}
		}
		else if(!portal.equals(other.portal))
		{
			return false;
		}
		return true;
	}

	public Location getLA()
	{
		return portal.getPosition().getCornerDL();
	}

	public Location getLB()
	{
		return portal.getPosition().getCornerUR();
	}

	public ProjectionSet betweenThisAnd(Viewport p, Portal por)
	{
		Location iris = p.getIris();
		Location la = p.getLA();
		Location lb = getLB();

		ProjectionSet set = new ProjectionSet();
		Vector va = VectorMath.direction(iris, la);
		Vector vb = VectorMath.direction(iris, lb);
		Integer dfd = (int) iris.clone().distance(portal.getPosition().getCenter());

		for(int i = 0; i < Settings.PROJECTION_SAMPLE_RADIUS + 6 + dfd; i++)
		{
			Location ma = iris.clone().add(va.clone().multiply(i));
			Location mb = iris.clone().add(vb.clone().multiply(i));

			set.add(new Cuboid(ma, mb));

			if(set.contains(portal.getPosition().getCenter()))
			{
				set.clear();
			}
		}

		return set;
	}

	public Direction getDirection()
	{
		return Direction.closest(VectorMath.direction(getIris(), getPortal().getPosition().getCenter()), getPortal().getIdentity().getFront(), getPortal().getIdentity().getBack());
	}
}
