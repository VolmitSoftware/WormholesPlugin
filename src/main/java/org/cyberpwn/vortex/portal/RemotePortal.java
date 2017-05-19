package org.cyberpwn.vortex.portal;

import org.cyberpwn.vortex.VP;
import org.cyberpwn.vortex.aperture.AperturePlane;
import org.cyberpwn.vortex.projection.ProjectionPlane;
import org.cyberpwn.vortex.service.MutexService;
import org.cyberpwn.vortex.wormhole.Wormhole;
import wraith.DataCluster;
import wraith.Direction;

public class RemotePortal implements Portal
{
	private String server;
	private PortalIdentity identity;
	
	public RemotePortal(String server, PortalIdentity identity)
	{
		this.identity = identity;
		this.server = server;
	}
	
	@Override
	public void update()
	{
		
	}
	
	@Override
	public PortalIdentity getIdentity()
	{
		return identity;
	}
	
	@Override
	public PortalPosition getPosition()
	{
		return null;
	}
	
	@Override
	public PortalKey getKey()
	{
		return identity.getKey();
	}
	
	@Override
	public boolean hasWormhole()
	{
		return false;
	}
	
	@Override
	public boolean isWormholeMutex()
	{
		return false;
	}
	
	@Override
	public Wormhole getWormhole()
	{
		return null;
	}
	
	@Override
	public MutexService getService()
	{
		return VP.host;
	}
	
	@Override
	public DataCluster toData()
	{
		DataCluster cc = new DataCluster();
		
		cc.set("ku", getKey().getU().ordinal());
		cc.set("kd", getKey().getD().ordinal());
		cc.set("kl", getKey().getL().ordinal());
		cc.set("kr", getKey().getR().ordinal());
		cc.set("if", getIdentity().getFront().ordinal());
		
		return cc;
	}
	
	@Override
	public void fromData(DataCluster cc)
	{
		PortalKey k = new PortalKey(new byte[] {cc.getInt("ku").byteValue(), cc.getInt("kd").byteValue(), cc.getInt("kl").byteValue(), cc.getInt("kr").byteValue()});
		PortalIdentity i = new PortalIdentity(Direction.values()[cc.getInt("if")], k);
		identity = i;
	}
	
	@Override
	public String getServer()
	{
		return server;
	}
	
	@Override
	public ProjectionPlane getProjectionPlane()
	{
		if(!VP.projector.getRemotePlanes().containsKey(getKey()))
		{
			VP.projector.getRemotePlanes().put(getKey(), new ProjectionPlane());
		}
		
		return VP.projector.getRemotePlanes().get(getKey());
	}
	
	@Override
	public int hashCode()
	{
		final int prime = 31;
		int result = 1;
		result = prime * result + ((identity == null) ? 0 : identity.hashCode());
		result = prime * result + ((server == null) ? 0 : server.hashCode());
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
		if(getClass() != obj.getClass())
		{
			return false;
		}
		RemotePortal other = (RemotePortal) obj;
		if(identity == null)
		{
			if(other.identity != null)
			{
				return false;
			}
		}
		else if(!identity.equals(other.identity))
		{
			return false;
		}
		if(server == null)
		{
			if(other.server != null)
			{
				return false;
			}
		}
		else if(!server.equals(other.server))
		{
			return false;
		}
		return true;
	}
	
	@Override
	public boolean hasValidKey()
	{
		return true;
	}
	
	@Override
	public AperturePlane getApature()
	{
		return VP.aperture.getRemoteApaturePlanes().get(getKey());
	}
}
