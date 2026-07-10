package art.arcane.wormholes.portal;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PortalMenuPresetTest
{
	@Test
	void travelModesRoundTripPersistedBooleans()
	{
		for(PortalTravelMode mode : PortalTravelMode.values())
		{
			assertEquals(mode, PortalTravelMode.from(mode.allowsOutgoing(), mode.allowsIncoming()));
		}
		assertEquals(PortalTravelMode.OUTBOUND, PortalTravelMode.BOTH.next());
		assertEquals(PortalTravelMode.BOTH, PortalTravelMode.LOCKED.next());
		assertTrue(PortalTravelMode.BOTH.allowsOutgoing());
		assertTrue(PortalTravelMode.BOTH.allowsIncoming());
		assertFalse(PortalTravelMode.LOCKED.allowsOutgoing());
		assertFalse(PortalTravelMode.LOCKED.allowsIncoming());
	}

	@Test
	void streamQualityRecognizesPresetsAndPreservesCustomValues()
	{
		for(NetworkViewQuality quality : NetworkViewQuality.values())
		{
			if(quality == NetworkViewQuality.CUSTOM)
			{
				continue;
			}
			assertEquals(quality, NetworkViewQuality.from(
					quality.getDepth(),
					quality.getHeartbeatTicks(),
					quality.getEntityIntervalTicks(),
					quality.getUnsubscribeGraceSeconds()));
		}
		assertEquals(NetworkViewQuality.CUSTOM, NetworkViewQuality.from(73, 41, 7, 23));
		assertEquals(NetworkViewQuality.STANDARD, NetworkViewQuality.CUSTOM.next());
	}
}
