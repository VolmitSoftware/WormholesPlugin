package art.arcane.wormholes.door;

import io.papermc.paper.event.entity.EntityMoveEvent;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;

import java.util.Objects;

final class PaperLivingEntityMoveListener implements Listener
{
	private final LivingEntityMoveCallback callback;

	PaperLivingEntityMoveListener(LivingEntityMoveCallback callback)
	{
		this.callback = Objects.requireNonNull(callback, "callback");
	}

	@EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
	public void onEntityMove(EntityMoveEvent event)
	{
		callback.onMove(event.getEntity(), event.getFrom(), event.getTo());
	}
}
