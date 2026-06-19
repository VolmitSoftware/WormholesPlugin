package art.arcane.wormholes.portal;

import org.bukkit.entity.Player;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import art.arcane.wormholes.Settings;

public final class ArrivalTransition
{
	private ArrivalTransition()
	{
	}

	public static void apply(Player player, boolean reloadExpected)
	{
		if(player == null || !reloadExpected || !Settings.ARRIVAL_TRANSITION_MASK)
		{
			return;
		}
		int ticks = Settings.ARRIVAL_TRANSITION_MASK_TICKS;
		if(ticks <= 0)
		{
			return;
		}
		player.addPotionEffect(new PotionEffect(PotionEffectType.DARKNESS, ticks, 0, false, false, false));
	}
}
