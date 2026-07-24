package art.arcane.wormholes.portal;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;

import org.junit.jupiter.api.Test;

public final class LocalPortalTraversalGateTest
{
	@Test
	public void rejectedReentryLatchExpiresQuicklyWhileArrivalLatchPersists()
	{
		long stamp = 1_000_000L;
		assertFalse(LocalPortal.reentryLatchExpired(true, stamp, stamp + 2_000L));
		assertTrue(LocalPortal.reentryLatchExpired(true, stamp, stamp + 2_500L));
		assertFalse(LocalPortal.reentryLatchExpired(false, stamp, stamp + 2_500L));
		assertFalse(LocalPortal.reentryLatchExpired(false, stamp, stamp + 59_999L));
		assertTrue(LocalPortal.reentryLatchExpired(false, stamp, stamp + 60_000L));
	}

	@Test
	public void waitingLatchReleasesOutsidePortalAfterGraceAndArmedLatchReleasesImmediately()
	{
		long stamp = 5_000_000L;
		assertTrue(LocalPortal.shouldReleaseReentryLatchOutsidePortal(true, stamp, stamp));
		assertFalse(LocalPortal.shouldReleaseReentryLatchOutsidePortal(false, stamp, stamp + 1_999L));
		assertTrue(LocalPortal.shouldReleaseReentryLatchOutsidePortal(false, stamp, stamp + 2_000L));
	}

	@Test
	public void teleportInFlightTokenExpiresAfterTtlAndCanBeReacquired()
	{
		UUID entityId = UUID.nameUUIDFromBytes("in-flight-ttl".getBytes());
		long start = 10_000_000L;
		assertTrue(LocalPortal.markTeleportInFlight(entityId, start));
		assertFalse(LocalPortal.markTeleportInFlight(entityId, start + 29_999L));
		assertTrue(LocalPortal.isTeleportInFlight(entityId, start + 29_999L));
		assertTrue(LocalPortal.markTeleportInFlight(entityId, start + 30_000L));
		assertTrue(LocalPortal.clearTeleportInFlight(entityId));
		assertFalse(LocalPortal.isTeleportInFlight(entityId, start));
	}

	@Test
	public void staleInFlightTokenIsNotTreatedAsActive()
	{
		UUID entityId = UUID.nameUUIDFromBytes("in-flight-stale".getBytes());
		long start = 20_000_000L;
		assertTrue(LocalPortal.markTeleportInFlight(entityId, start));
		assertFalse(LocalPortal.isTeleportInFlight(entityId, start + 30_000L));
		assertFalse(LocalPortal.isTeleportInFlight(entityId, start + 30_001L));
		assertFalse(LocalPortal.clearTeleportInFlight(entityId));
	}

	@Test
	public void staleTokenCanStillBeClearedExplicitly()
	{
		UUID staleId = UUID.nameUUIDFromBytes("in-flight-explicit-clear".getBytes());
		long start = 30_000_000L;
		assertTrue(LocalPortal.markTeleportInFlight(staleId, start));
		assertTrue(LocalPortal.clearTeleportInFlight(staleId));
		assertFalse(LocalPortal.clearTeleportInFlight(staleId));
	}
}
