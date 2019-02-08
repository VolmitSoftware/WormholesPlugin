package com.volmit.wormholes.portal;

import java.util.UUID;

import org.bukkit.util.Vector;

import com.volmit.wormholes.project.ProjectionMatrix;
import com.volmit.wormholes.util.Direction;

public interface IPortal extends IWritable
{
	public Direction getDirection();

	public UUID getId();

	public String getName();

	public void setName(String name);

	public boolean isRemote();

	public Vector getOrigin();

	public ProjectionMatrix getMatrix();
}
