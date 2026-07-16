package art.arcane.wormholes.door;

import org.bukkit.GameMode;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.PlayerInventory;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class DimensionalDoorManagerInteractionTest
{
	@Test
	void bukkitDefaultCancelledAirInteractionCanUnpackPairKit()
	{
		assertTrue(DimensionalDoorManager.shouldUnpackPairKit(
			Action.RIGHT_CLICK_AIR,
			Event.Result.DENY,
			Event.Result.DEFAULT));
	}

	@Test
	void deniedItemUseCannotUnpackPairKit()
	{
		assertFalse(DimensionalDoorManager.shouldUnpackPairKit(
			Action.RIGHT_CLICK_AIR,
			Event.Result.DEFAULT,
			Event.Result.DENY));
	}

	@Test
	void deniedProtectedBlockUseCannotUnpackPairKit()
	{
		assertFalse(DimensionalDoorManager.shouldUnpackPairKit(
			Action.RIGHT_CLICK_BLOCK,
			Event.Result.DENY,
			Event.Result.DEFAULT));
	}

	@Test
	void defaultRightClickBlockCanUnpackPairKit()
	{
		assertTrue(DimensionalDoorManager.shouldUnpackPairKit(
			Action.RIGHT_CLICK_BLOCK,
			Event.Result.DEFAULT,
			Event.Result.DEFAULT));
	}

	@Test
	void leftClickActionsCannotUnpackPairKit()
	{
		assertFalse(DimensionalDoorManager.shouldUnpackPairKit(
			Action.LEFT_CLICK_AIR,
			Event.Result.DEFAULT,
			Event.Result.DEFAULT));
		assertFalse(DimensionalDoorManager.shouldUnpackPairKit(
			Action.LEFT_CLICK_BLOCK,
			Event.Result.DEFAULT,
			Event.Result.DEFAULT));
	}

	@Test
	void cancelledAirInteractionStillReachesPairKitHandler() throws NoSuchMethodException
	{
		Method method = DimensionalDoorManager.class.getMethod("onPairKitUse", PlayerInteractEvent.class);
		EventHandler handler = method.getAnnotation(EventHandler.class);

		assertNotNull(handler);
		assertFalse(handler.ignoreCancelled());
	}

	@Test
	void creativePlacementConsumesDoorItem()
	{
		assertTrue(DimensionalDoorManager.consumesPlacedDoorItem(GameMode.CREATIVE));
	}

	@Test
	void survivalPlacementLeavesVanillaConsumptionInPlace()
	{
		assertFalse(DimensionalDoorManager.consumesPlacedDoorItem(GameMode.SURVIVAL));
	}

	@Test
	void consumingMainHandClearsOnlyMainHand()
	{
		InventoryCalls calls = new InventoryCalls();
		DimensionalDoorManager.consumeHeldItem(player(inventory(calls)), EquipmentSlot.HAND);

		assertEquals(1, calls.mainHandWrites);
		assertEquals(0, calls.offHandWrites);
		assertNull(calls.mainHandValue);
	}

	@Test
	void consumingOffHandClearsOnlyOffHand()
	{
		InventoryCalls calls = new InventoryCalls();
		DimensionalDoorManager.consumeHeldItem(player(inventory(calls)), EquipmentSlot.OFF_HAND);

		assertEquals(0, calls.mainHandWrites);
		assertEquals(1, calls.offHandWrites);
		assertNull(calls.offHandValue);
	}

	private static Player player(PlayerInventory inventory)
	{
		return (Player) Proxy.newProxyInstance(
			Player.class.getClassLoader(),
			new Class<?>[]{Player.class},
			(proxy, method, arguments) -> switch(method.getName())
			{
				case "getInventory" -> inventory;
				case "toString" -> "player";
				case "hashCode" -> System.identityHashCode(proxy);
				case "equals" -> proxy == arguments[0];
				default -> defaultValue(method.getReturnType());
			});
	}

	private static PlayerInventory inventory(InventoryCalls calls)
	{
		return (PlayerInventory) Proxy.newProxyInstance(
			PlayerInventory.class.getClassLoader(),
			new Class<?>[]{PlayerInventory.class},
			(proxy, method, arguments) ->
			{
				switch(method.getName())
				{
					case "setItemInMainHand" ->
					{
						calls.mainHandWrites++;
						calls.mainHandValue = arguments[0];
						return null;
					}
					case "setItemInOffHand" ->
					{
						calls.offHandWrites++;
						calls.offHandValue = arguments[0];
						return null;
					}
					case "toString" ->
					{
						return "inventory";
					}
					case "hashCode" ->
					{
						return System.identityHashCode(proxy);
					}
					case "equals" ->
					{
						return proxy == arguments[0];
					}
					default ->
					{
						return defaultValue(method.getReturnType());
					}
				}
			});
	}

	private static Object defaultValue(Class<?> type)
	{
		if(!type.isPrimitive() || type == void.class)
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
		if(type == byte.class)
		{
			return (byte) 0;
		}
		if(type == short.class)
		{
			return (short) 0;
		}
		if(type == int.class)
		{
			return 0;
		}
		if(type == long.class)
		{
			return 0L;
		}
		if(type == float.class)
		{
			return 0.0F;
		}
		return 0.0D;
	}

	private static final class InventoryCalls
	{
		private int mainHandWrites;
		private int offHandWrites;
		private Object mainHandValue = new Object();
		private Object offHandValue = new Object();
	}
}
