package art.arcane.wormholes.door;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

public final class PocketRescuePolicyTest
{
	@Test
	public void exactLethalDamageStartsPocketEjectionAtOneHeart()
	{
		PocketRescuePolicy.Decision decision = PocketRescuePolicy.evaluate(
			7.0D, 20.0D, 7.0D, false);

		assertEquals(PocketRescuePolicy.Action.START_EJECTION, decision.action());
		assertEquals(2.0D, decision.retainedHealth(), 0.0D);
		assertTrue(decision.preventsDamage());
		assertTrue(decision.startsEjection());
	}

	@Test
	public void overkillDamageStartsPocketEjection()
	{
		PocketRescuePolicy.Decision decision = PocketRescuePolicy.evaluate(
			7.0D, 20.0D, 100.0D, false);

		assertEquals(PocketRescuePolicy.Action.START_EJECTION, decision.action());
		assertTrue(decision.preventsDamage());
	}

	@Test
	public void nonLethalPocketDamagePassesThroughAtBoundary()
	{
		PocketRescuePolicy.Decision decision = PocketRescuePolicy.evaluate(
			7.0D, 20.0D, 6.999D, false);

		assertEquals(PocketRescuePolicy.Action.PASS, decision.action());
		assertEquals(7.0D, decision.retainedHealth(), 0.0D);
		assertFalse(decision.preventsDamage());
		assertFalse(decision.startsEjection());
	}

	@Test
	public void inFlightEjectionHoldsPlayerAtOneHeartWithoutStartingAgain()
	{
		PocketRescuePolicy.Decision decision = PocketRescuePolicy.evaluate(
			2.0D, 20.0D, 0.25D, true);

		assertEquals(PocketRescuePolicy.Action.HOLD_FOR_EJECTION, decision.action());
		assertEquals(2.0D, decision.retainedHealth(), 0.0D);
		assertTrue(decision.preventsDamage());
		assertFalse(decision.startsEjection());
	}

	@Test
	public void retainedHealthClampsToReducedMaximum()
	{
		PocketRescuePolicy.Decision decision = PocketRescuePolicy.evaluate(
			1.5D, 1.5D, 2.0D, false);

		assertEquals(1.5D, decision.retainedHealth(), 0.0D);
	}

	@Test
	public void zeroOrNegativeDamageDoesNotTriggerAnInFlightHold()
	{
		PocketRescuePolicy.Decision zero = PocketRescuePolicy.evaluate(
			2.0D, 20.0D, 0.0D, true);
		PocketRescuePolicy.Decision negative = PocketRescuePolicy.evaluate(
			2.0D, 20.0D, -1.0D, true);

		assertEquals(PocketRescuePolicy.Action.PASS, zero.action());
		assertEquals(PocketRescuePolicy.Action.PASS, negative.action());
	}

	@Test
	public void invalidHealthAndDamageInputsAreRejected()
	{
		assertThrows(IllegalArgumentException.class,
			() -> PocketRescuePolicy.evaluate(21.0D, 20.0D, 2.0D, false));
		assertThrows(IllegalArgumentException.class,
			() -> PocketRescuePolicy.evaluate(2.0D, 0.0D, 2.0D, false));
		assertThrows(IllegalArgumentException.class,
			() -> PocketRescuePolicy.evaluate(2.0D, 20.0D, Double.NaN, false));
		assertThrows(IllegalArgumentException.class,
			() -> PocketRescuePolicy.evaluate(2.0D, 20.0D, Double.POSITIVE_INFINITY, false));
	}
}
