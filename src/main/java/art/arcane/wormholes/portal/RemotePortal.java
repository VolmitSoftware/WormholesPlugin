package art.arcane.wormholes.portal;

import java.util.UUID;

import org.bukkit.util.Vector;

import art.arcane.wormholes.util.RemoteWorld;

public class RemotePortal extends Portal implements IRemotePortal {
    private final RemoteWorld server;

    public RemotePortal(UUID id, RemoteWorld server, Vector origin) {
        super(id, origin);
        this.server = server;
    }

    @Override
    public boolean isRemote() {
        return true;
    }

    @Override
    public RemoteWorld getServer() {
        return server;
    }
}
