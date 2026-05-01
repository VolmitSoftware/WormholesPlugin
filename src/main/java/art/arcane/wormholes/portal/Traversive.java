package art.arcane.wormholes.portal;

import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;

import art.arcane.wormholes.util.Direction;
import art.arcane.wormholes.util.VectorMath;

public class Traversive
{
	private final Object object;
	private final TraversableType type;
	private final Direction inDirection;
	private final Vector inVelocity;
	private final Vector inLook;
	private final Vector inPlane;

	public Traversive(Object o, TraversableType type, Direction inDirection, Vector inVelocity, Vector inLook, Vector inPlane)
	{
		this.object = o;
		this.type = type;
		this.inDirection = inDirection;
		this.inVelocity = inVelocity;
		this.inLook = inLook;
		this.inPlane = inPlane;
	}

	public Traversive(Player player, Direction inDirection, Vector inVelocity, Vector inLook, Vector inPlane)
	{
		this(player, TraversableType.PLAYER, inDirection, inVelocity, inLook, inPlane);
	}

	public Traversive(Entity entity, Direction inDirection, Vector inVelocity, Vector inLook, Vector inPlane)
	{
		this(entity, TraversableType.ENTITY, inDirection, inVelocity, inLook, inPlane);
	}

	public Vector getOutVelocity(Direction outDirection)
	{
		return getInDirection().angle(getInVelocity(), outDirection);
	}

	public Vector getOutLook(Direction outDirection)
	{
		return getInDirection().angle(getInLook(), outDirection);
	}

	public Vector getOutPlane(Direction outDirection)
	{
		return getInDirection().angle(getInPlane(), outDirection);
	}

	public Direction getInDirection()
	{
		return inDirection;
	}

	public Vector getInVelocity()
	{
		return inVelocity;
	}

	public Vector getInLook()
	{
		return inLook;
	}

	public Vector getInPlane()
	{
		return VectorMath.reverse(inPlane);
	}

	public Object getObject()
	{
		return object;
	}

	public TraversableType getType()
	{
		return type;
	}
}
