package art.arcane.wormholes.portal;

import art.arcane.wormholes.util.RemoteWorld;

public interface IRemotePortal extends IPortal
{
	public RemoteWorld getServer();
}
