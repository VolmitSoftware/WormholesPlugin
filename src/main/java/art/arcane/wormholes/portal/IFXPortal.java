package art.arcane.wormholes.portal;

import org.bukkit.Location;

public interface IFXPortal
{
	public void playEffect(PortalEffect effect, Location location);

	public void playEffect(PortalEffect effect);
}
