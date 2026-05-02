package art.arcane.wormholes.portal;

import org.bukkit.entity.Entity;
import org.bukkit.util.Vector;

import art.arcane.wormholes.util.Direction;

public class Traversive
{
	private final Object object;
	private final TraversableType type;
	private final PortalFrame inFrame;
	private final Vector inOrigin;
	private final Vector inPoint;
	private final Vector inVelocity;
	private final Vector inLook;

	public Traversive(Object o, TraversableType type, Direction inDirection, Vector inOrigin, Vector inPoint, Vector inVelocity, Vector inLook)
	{
		this(o, type, PortalFrame.canonical(inDirection), inOrigin, inPoint, inVelocity, inLook);
	}

	public Traversive(Object o, TraversableType type, PortalFrame inFrame, Vector inOrigin, Vector inPoint, Vector inVelocity, Vector inLook)
	{
		this.object = o;
		this.type = type;
		this.inFrame = inFrame;
		this.inOrigin = inOrigin.clone();
		this.inPoint = inPoint.clone();
		this.inVelocity = inVelocity.clone();
		this.inLook = inLook.clone();
	}

	public Traversive(Entity entity, Direction inDirection, Vector inOrigin, Vector inPoint, Vector inVelocity, Vector inLook)
	{
		this(entity, TraversableType.ENTITY, inDirection, inOrigin, inPoint, inVelocity, inLook);
	}

	public Traversive(Entity entity, PortalFrame inFrame, Vector inOrigin, Vector inPoint, Vector inVelocity, Vector inLook)
	{
		this(entity, TraversableType.ENTITY, inFrame, inOrigin, inPoint, inVelocity, inLook);
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

	public Vector getOutOffset(Direction outDirection)
	{
		return getOutOffset(PortalFrame.canonical(outDirection));
	}

	public Vector getOutOffset(PortalFrame outFrame)
	{
		return inFrame.transformVector(getInOffset(), outFrame);
	}

	public Vector getOutPoint(PortalFrame outFrame, Vector outOrigin)
	{
		return inFrame.transformPoint(inPoint, inOrigin, outOrigin, outFrame);
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

	public Vector getInOffset()
	{
		return inPoint.clone().subtract(inOrigin);
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
