package art.arcane.wormholes.render.view;

import art.arcane.wormholes.network.view.EntityVisual;
import art.arcane.wormholes.network.view.RemoteViewCache;

import com.github.retrooper.packetevents.protocol.entity.data.EntityData;
import com.github.retrooper.packetevents.protocol.player.Equipment;

import java.util.List;
import java.util.UUID;

public interface ProjectionEntityView {
    List<EntityVisual> getEntities(double centerX, double centerY, double centerZ, double range);

    RemoteViewCache.RemoteProfile getProfile(UUID entityId);

    List<EntityData<?>> getMetadata(UUID entityId);

    List<Equipment> getEquipment(UUID entityId);

    int getStateVersion(UUID entityId);
}
