package art.arcane.wormholes.portal;

import java.util.UUID;

public interface IOwnablePortal extends IPortal
{
	public UUID getOwner();

	public void setOwner(UUID owner);

	public boolean isSelfOwned();

	public void setSelfOwned();
}
