package art.arcane.wormholes.portal;

import java.util.UUID;

import org.bukkit.util.Vector;

import art.arcane.wormholes.util.Direction;

public interface IPortal extends IWritable {
    public Direction getDirection();

    public PortalFrame getFrame();

    public UUID getId();

    public String getName();

    public void setName(String name);

    public boolean isRemote();

    public Vector getOrigin();
}
