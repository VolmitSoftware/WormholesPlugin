package art.arcane.wormholes.door;

import org.bukkit.Location;
import org.bukkit.entity.LivingEntity;

@FunctionalInterface
interface LivingEntityMoveCallback
{
	void onMove(LivingEntity entity, Location from, Location to);
}
