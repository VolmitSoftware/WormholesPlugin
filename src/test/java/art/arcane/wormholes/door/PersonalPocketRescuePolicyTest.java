package art.arcane.wormholes.door;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

public final class PersonalPocketRescuePolicyTest
{
	@Test
	public void exactLethalDamageStartsPersonalPocketEjectionAtOneHeart()
	{
		PersonalPocketRescuePolicy.Decision decision = PersonalPocketRescuePolicy.evaluate(
			PocketBindingKind.PERSONAL, 7.0D, 20.0D, 7.0D, false);

		assertEquals(PersonalPocketRescuePolicy.Action.START_EJECTION, decision.action());
		assertEquals(2.0D, decision.retainedHealth(), 0.0D);
		assertTrue(decision.preventsDamage());
		assertTrue(decision.startsEjection());
	}

	@Test
	public void nonLethalPersonalPocketDamagePassesThrough()
	{
		PersonalPocketRescuePolicy.Decision decision = PersonalPocketRescuePolicy.evaluate(
			PocketBindingKind.PERSONAL, 7.0D, 20.0D, 6.999D, false);

		assertEquals(PersonalPocketRescuePolicy.Action.PASS, decision.action());
		assertEquals(7.0D, decision.retainedHealth(), 0.0D);
		assertFalse(decision.preventsDamage());
		assertFalse(decision.startsEjection());
	}

	@Test
	public void ironPocketNeverInterceptsLethalDamage()
	{
		PersonalPocketRescuePolicy.Decision decision = PersonalPocketRescuePolicy.evaluate(
			PocketBindingKind.IRON, 7.0D, 20.0D, 100.0D, false);

		assertEquals(PersonalPocketRescuePolicy.Action.PASS, decision.action());
		assertFalse(decision.preventsDamage());
	}

	@Test
	public void inFlightEjectionHoldsPersonalPlayerAtOneHeartWithoutStartingAgain()
	{
		PersonalPocketRescuePolicy.Decision decision = PersonalPocketRescuePolicy.evaluate(
			PocketBindingKind.PERSONAL, 2.0D, 20.0D, 0.25D, true);

		assertEquals(PersonalPocketRescuePolicy.Action.HOLD_FOR_EJECTION, decision.action());
		assertEquals(2.0D, decision.retainedHealth(), 0.0D);
		assertTrue(decision.preventsDamage());
		assertFalse(decision.startsEjection());
	}

	@Test
	public void retainedHealthClampsToReducedMaximum()
	{
		PersonalPocketRescuePolicy.Decision decision = PersonalPocketRescuePolicy.evaluate(
			PocketBindingKind.PERSONAL, 1.5D, 1.5D, 2.0D, false);

		assertEquals(1.5D, decision.retainedHealth(), 0.0D);
	}

	@Test
	public void zeroOrNegativeDamageDoesNotTriggerAnInFlightHold()
	{
		PersonalPocketRescuePolicy.Decision zero = PersonalPocketRescuePolicy.evaluate(
			PocketBindingKind.PERSONAL, 2.0D, 20.0D, 0.0D, true);
		PersonalPocketRescuePolicy.Decision negative = PersonalPocketRescuePolicy.evaluate(
			PocketBindingKind.PERSONAL, 2.0D, 20.0D, -1.0D, true);

		assertEquals(PersonalPocketRescuePolicy.Action.PASS, zero.action());
		assertEquals(PersonalPocketRescuePolicy.Action.PASS, negative.action());
	}

	@Test
	public void invalidHealthAndDamageInputsAreRejected()
	{
		assertThrows(NullPointerException.class,
			() -> PersonalPocketRescuePolicy.evaluate(null, 2.0D, 20.0D, 2.0D, false));
		assertThrows(IllegalArgumentException.class,
			() -> PersonalPocketRescuePolicy.evaluate(PocketBindingKind.PERSONAL, 21.0D, 20.0D, 2.0D, false));
		assertThrows(IllegalArgumentException.class,
			() -> PersonalPocketRescuePolicy.evaluate(PocketBindingKind.PERSONAL, 2.0D, 0.0D, 2.0D, false));
		assertThrows(IllegalArgumentException.class,
			() -> PersonalPocketRescuePolicy.evaluate(PocketBindingKind.PERSONAL, 2.0D, 20.0D, Double.NaN, false));
	}
}
