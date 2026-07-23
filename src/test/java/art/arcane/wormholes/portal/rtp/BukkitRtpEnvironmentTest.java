package art.arcane.wormholes.portal.rtp;

import art.arcane.wormholes.util.AxisAlignedBB;
import org.bukkit.Location;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

public final class BukkitRtpEnvironmentTest
{
	@Test
	public void previewAnchorLiftMatchesCenterHeightAboveFrameBase()
	{
		Location center = new Location(null, 10.5D, 66.5D, -3.5D);
		AxisAlignedBB area = new AxisAlignedBB(8.0D, 13.0D, 64.0D, 69.0D, -6.0D, -1.0D);

		assertEquals(2.5D, BukkitRtpEnvironment.previewAnchorLift(center, area), 0.0001D);
	}

	@Test
	public void previewAnchorLiftNeverDropsBelowOneBlock()
	{
		Location center = new Location(null, 0.5D, 64.2D, 0.5D);
		AxisAlignedBB area = new AxisAlignedBB(0.0D, 1.0D, 64.0D, 65.0D, 0.0D, 1.0D);

		assertEquals(1.0D, BukkitRtpEnvironment.previewAnchorLift(center, area), 0.0001D);
		assertEquals(1.0D, BukkitRtpEnvironment.previewAnchorLift(null, null), 0.0001D);
	}
}
