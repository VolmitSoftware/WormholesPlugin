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
	private final PortalFrame inFrame;
	private final Vector inVelocity;
	private final Vector inLook;
	private final Vector inPlane;

	public Traversive(Object o, TraversableType type, Direction inDirection, Vector inVelocity, Vector inLook, Vector inPlane)
	{
		this(o, type, PortalFrame.canonical(inDirection), inVelocity, inLook, inPlane);
	}

	public Traversive(Object o, TraversableType type, PortalFrame inFrame, Vector inVelocity, Vector inLook, Vector inPlane)
	{
		this.object = o;
		this.type = type;
		this.inFrame = inFrame;
		this.inVelocity = inVelocity;
		this.inLook = inLook;
		this.inPlane = inPlane;
	}

	public Traversive(Player player, Direction inDirection, Vector inVelocity, Vector inLook, Vector inPlane)
	{
		this(player, TraversableType.PLAYER, inDirection, inVelocity, inLook, inPlane);
	}

	public Traversive(Player player, PortalFrame inFrame, Vector inVelocity, Vector inLook, Vector inPlane)
	{
		this(player, TraversableType.PLAYER, inFrame, inVelocity, inLook, inPlane);
	}

	public Traversive(Entity entity, Direction inDirection, Vector inVelocity, Vector inLook, Vector inPlane)
	{
		this(entity, TraversableType.ENTITY, inDirection, inVelocity, inLook, inPlane);
	}

	public Traversive(Entity entity, PortalFrame inFrame, Vector inVelocity, Vector inLook, Vector inPlane)
	{
		this(entity, TraversableType.ENTITY, inFrame, inVelocity, inLook, inPlane);
	}

	public Vector getOutVelocity(Direction outDirection)
	{
		return getOutVelocity(PortalFrame.canonical(outDirection));
	}

	public Vector getOutVelocity(PortalFrame outFrame)
	{
		return inFrame.transformVector(getInVelocity(), outFrame);
	}

	public Vector getOutLook(Direction outDirection)
	{
		return getOutLook(PortalFrame.canonical(outDirection));
	}

	public Vector getOutLook(PortalFrame outFrame)
	{
		return inFrame.transformVector(getInLook(), outFrame);
	}

	public Vector getOutPlane(Direction outDirection)
	{
		return getOutPlane(PortalFrame.canonical(outDirection));
	}

	public Vector getOutPlane(PortalFrame outFrame)
	{
		return inFrame.transformVector(getInPlane(), outFrame);
	}

	public Direction getInDirection()
	{
		return inFrame.getNormal();
	}

	public PortalFrame getInFrame()
	{
		return inFrame;
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
