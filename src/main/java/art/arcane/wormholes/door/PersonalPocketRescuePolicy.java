package art.arcane.wormholes.door;

import java.util.Objects;

public final class PersonalPocketRescuePolicy
{
	public static final double ONE_HEART = 2.0D;

	private PersonalPocketRescuePolicy()
	{
	}

	public static Decision evaluate(
		PocketBindingKind pocketKind,
		double currentHealth,
		double maximumHealth,
		double finalDamage,
		boolean rescueInFlight)
	{
		Objects.requireNonNull(pocketKind, "pocketKind");
		requireHealth(currentHealth, maximumHealth);
		if(!Double.isFinite(finalDamage))
		{
			throw new IllegalArgumentException("Final damage must be finite");
		}

		if(pocketKind != PocketBindingKind.PERSONAL || finalDamage <= 0.0D)
		{
			return new Decision(Action.PASS, currentHealth);
		}

		double retainedHealth = Math.min(ONE_HEART, maximumHealth);
		if(rescueInFlight)
		{
			return new Decision(Action.HOLD_FOR_EJECTION, retainedHealth);
		}
		if(finalDamage >= currentHealth)
		{
			return new Decision(Action.START_EJECTION, retainedHealth);
		}
		return new Decision(Action.PASS, currentHealth);
	}

	private static void requireHealth(double currentHealth, double maximumHealth)
	{
		if(!Double.isFinite(currentHealth) || !Double.isFinite(maximumHealth)
			|| currentHealth < 0.0D || maximumHealth <= 0.0D || currentHealth > maximumHealth)
		{
			throw new IllegalArgumentException("Health values must be finite and within the maximum");
		}
	}

	public record Decision(Action action, double retainedHealth)
	{
		public Decision
		{
			Objects.requireNonNull(action, "action");
			if(!Double.isFinite(retainedHealth) || retainedHealth < 0.0D)
			{
				throw new IllegalArgumentException("Retained health must be finite and non-negative");
			}
		}

		public boolean preventsDamage()
		{
			return action != Action.PASS;
		}

		public boolean startsEjection()
		{
			return action == Action.START_EJECTION;
		}
	}

	public enum Action
	{
		PASS,
		START_EJECTION,
		HOLD_FOR_EJECTION
	}
}
