package com.volmit.wormholes.projection;

import org.bukkit.entity.Player;
import com.volmit.wormholes.util.Cuboid;

public interface ViewportRenderer
{
	public Player getPlayer();
	
	public Viewport getViewport();
	
	public int getStage();
	
	public int getMaxStage();
	
	public ProjectionSet getProjectionSet();
	
	public ProjectionSet getRenderedStages();
	
	public ProjectionSet getNonRenderedStages();
	
	public Cuboid getProjectionStage(int stage);
	
	public boolean isComplete();
}
