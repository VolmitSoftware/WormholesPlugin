package art.arcane.wormholes.render;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.bukkit.Location;
import org.bukkit.util.Vector;
import org.junit.jupiter.api.Test;

public final class ProjectedEntityRendererLookDirectionTest {
	@Test
	public void lookDirectionMatchesBukkitLocationDirection() {
		double[] out = new double[3];
		for(float yaw = -540.0F; yaw <= 540.0F; yaw += 22.5F) {
			for(float pitch = -90.0F; pitch <= 90.0F; pitch += 15.0F) {
				Location location = new Location(null, 0.0D, 0.0D, 0.0D, yaw, pitch);
				Vector expected = location.getDirection();
				ProjectedEntityRenderer.lookDirectionInto(yaw, pitch, out);
				assertEquals(expected.getX(), out[0], 1.0E-12D, "x yaw=" + yaw + " pitch=" + pitch);
				assertEquals(expected.getY(), out[1], 1.0E-12D, "y yaw=" + yaw + " pitch=" + pitch);
				assertEquals(expected.getZ(), out[2], 1.0E-12D, "z yaw=" + yaw + " pitch=" + pitch);
			}
		}
	}

	@Test
	public void lookDirectionCardinalAxes() {
		double[] out = new double[3];
		ProjectedEntityRenderer.lookDirectionInto(0.0F, 0.0F, out);
		assertEquals(0.0D, out[0], 1.0E-12D);
		assertEquals(0.0D, out[1], 1.0E-12D);
		assertEquals(1.0D, out[2], 1.0E-12D);
		ProjectedEntityRenderer.lookDirectionInto(90.0F, 0.0F, out);
		assertEquals(-1.0D, out[0], 1.0E-12D);
		assertEquals(0.0D, out[1], 1.0E-12D);
		ProjectedEntityRenderer.lookDirectionInto(0.0F, -90.0F, out);
		assertEquals(1.0D, out[1], 1.0E-12D);
		ProjectedEntityRenderer.lookDirectionInto(0.0F, 90.0F, out);
		assertEquals(-1.0D, out[1], 1.0E-12D);
	}
}
