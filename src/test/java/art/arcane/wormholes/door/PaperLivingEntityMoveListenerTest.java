package art.arcane.wormholes.door;

import io.papermc.paper.event.entity.EntityMoveEvent;
import org.bukkit.Location;
import org.bukkit.entity.LivingEntity;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class PaperLivingEntityMoveListenerTest
{
	@Test
	void forwardsEntityAndMovementLocations()
	{
		LivingEntity entity = entity();
		Location from = new Location(null, 1.0D, 2.0D, 3.0D);
		Location to = new Location(null, 4.0D, 5.0D, 6.0D);
		AtomicReference<LivingEntity> capturedEntity = new AtomicReference<>();
		AtomicReference<Location> capturedFrom = new AtomicReference<>();
		AtomicReference<Location> capturedTo = new AtomicReference<>();
		AtomicInteger calls = new AtomicInteger();
		PaperLivingEntityMoveListener listener = new PaperLivingEntityMoveListener((moved, origin, destination) ->
		{
			capturedEntity.set(moved);
			capturedFrom.set(origin);
			capturedTo.set(destination);
			calls.incrementAndGet();
		});

		listener.onEntityMove(new EntityMoveEvent(entity, from, to));

		assertEquals(1, calls.get());
		assertSame(entity, capturedEntity.get());
		assertSame(from, capturedFrom.get());
		assertSame(to, capturedTo.get());
	}

	@Test
	void cancelledMovementIsIgnoredByTheEventDispatcher() throws NoSuchMethodException
	{
		Method method = PaperLivingEntityMoveListener.class.getMethod("onEntityMove", EntityMoveEvent.class);
		EventHandler handler = method.getAnnotation(EventHandler.class);

		assertEquals(EventPriority.MONITOR, handler.priority());
		assertTrue(handler.ignoreCancelled());
	}

	private static LivingEntity entity()
	{
		return (LivingEntity) Proxy.newProxyInstance(
			LivingEntity.class.getClassLoader(),
			new Class<?>[]{LivingEntity.class},
			(proxy, method, arguments) -> switch(method.getName())
			{
				case "toString" -> "living-entity";
				case "hashCode" -> System.identityHashCode(proxy);
				case "equals" -> proxy == arguments[0];
				default -> defaultValue(method.getReturnType());
			});
	}

	private static Object defaultValue(Class<?> type)
	{
		if(!type.isPrimitive())
		{
			return null;
		}
		if(type == boolean.class)
		{
			return false;
		}
		if(type == char.class)
		{
			return '\0';
		}
		return 0;
	}
}
