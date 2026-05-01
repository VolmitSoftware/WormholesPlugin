package art.arcane.wormholes.portal;

public class UniversalTunnel extends Tunnel
{
	public UniversalTunnel(IRemotePortal portal)
	{
		super(portal, TunnelType.UNIVERSAL);
	}

	@Override
	public void push(Traversive t)
	{
		// TODO Auto-generated method stub
	}

	@Override
	public boolean isValid()
	{
		// TODO bungee!
		return false;
	}
}
