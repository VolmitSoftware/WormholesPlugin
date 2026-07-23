package art.arcane.wormholes.door;

import art.arcane.wormholes.door.DoorPortalVisualService.PortalPlaneGeometry;
import org.bukkit.block.BlockFace;

import java.util.Objects;

/** Pure per-tick math for the animated surface of an open dimensional door. */
final class DoorPortalAnimation
{
	static final int FRAME_PERIOD_TICKS = 2;
	static final int ATTENDANCE_PERIOD_TICKS = 40;
	static final double ATTENDANCE_RANGE_SQUARED = 128.0D * 128.0D;
	static final int PULSE_PERIOD_TICKS = 48;
	static final float PULSE_DEPTH = 0.06F;
	static final int SWAY_PERIOD_TICKS = 72;
	static final float SWAY_AMPLITUDE = 0.008F;
	static final int ORBIT_PERIOD_TICKS = 44;
	static final int ORBIT_ARMS = 2;
	static final double ORBIT_LATERAL_FRACTION = 0.36D;
	static final double ORBIT_VERTICAL_FRACTION = 0.4D;
	private static final double TAU = Math.PI * 2.0D;

	private DoorPortalAnimation()
	{
	}

	static PortalPlaneGeometry frame(PortalPlaneGeometry base, BlockFace facing, int tick)
	{
		Objects.requireNonNull(base, "base");
		float pulse = 1.0F - (PULSE_DEPTH * (0.5F + (0.5F * (float) Math.sin((tick * TAU) / PULSE_PERIOD_TICKS))));
		float sway = SWAY_AMPLITUDE * (float) Math.sin(((tick * TAU) / SWAY_PERIOD_TICKS) + (Math.PI / 3.0D));
		float scaleY = base.scaleY() * pulse;
		float translationY = base.translationY() + ((base.scaleY() - scaleY) / 2.0F);
		return switch(requireCardinal(facing))
		{
			case NORTH, SOUTH ->
			{
				float scaleX = base.scaleX() * pulse;
				float translationX = base.translationX() + ((base.scaleX() - scaleX) / 2.0F) + sway;
				yield new PortalPlaneGeometry(
					translationX,
					translationY,
					base.translationZ(),
					scaleX,
					scaleY,
					base.scaleZ());
			}
			default ->
			{
				float scaleZ = base.scaleZ() * pulse;
				float translationZ = base.translationZ() + ((base.scaleZ() - scaleZ) / 2.0F) + sway;
				yield new PortalPlaneGeometry(
					base.translationX(),
					translationY,
					translationZ,
					base.scaleX(),
					scaleY,
					scaleZ);
			}
		};
	}

	static double[] orbitPoint(PortalPlaneGeometry base, BlockFace facing, int tick, int arm)
	{
		Objects.requireNonNull(base, "base");
		if(arm < 0 || arm >= ORBIT_ARMS)
		{
			throw new IllegalArgumentException("Orbit arm must be within [0, " + ORBIT_ARMS + "): " + arm);
		}
		double direction = (arm & 1) == 0 ? 1.0D : -1.0D;
		double angle = (direction * ((tick * TAU) / ORBIT_PERIOD_TICKS)) + (arm * (TAU / ORBIT_ARMS));
		double verticalCenter = base.translationY() + (base.scaleY() / 2.0D);
		double verticalRadius = base.scaleY() * ORBIT_VERTICAL_FRACTION;
		double vertical = verticalCenter + (verticalRadius * Math.sin(angle));
		return switch(requireCardinal(facing))
		{
			case NORTH, SOUTH ->
			{
				double lateralCenter = base.translationX() + (base.scaleX() / 2.0D);
				double lateralRadius = base.scaleX() * ORBIT_LATERAL_FRACTION;
				yield new double[] {
					lateralCenter + (lateralRadius * Math.cos(angle)),
					vertical,
					base.translationZ() + (base.scaleZ() / 2.0D)};
			}
			default ->
			{
				double lateralCenter = base.translationZ() + (base.scaleZ() / 2.0D);
				double lateralRadius = base.scaleZ() * ORBIT_LATERAL_FRACTION;
				yield new double[] {
					base.translationX() + (base.scaleX() / 2.0D),
					vertical,
					lateralCenter + (lateralRadius * Math.cos(angle))};
			}
		};
	}

	static double[] scatterPoint(PortalPlaneGeometry base, BlockFace facing, double u, double v)
	{
		Objects.requireNonNull(base, "base");
		if(u < 0.0D || u >= 1.0D || v < 0.0D || v >= 1.0D)
		{
			throw new IllegalArgumentException("Scatter coordinates must be within [0, 1): " + u + ", " + v);
		}
		double vertical = base.translationY() + (v * base.scaleY());
		return switch(requireCardinal(facing))
		{
			case NORTH, SOUTH -> new double[] {
				base.translationX() + (u * base.scaleX()),
				vertical,
				base.translationZ() + (base.scaleZ() / 2.0D)};
			default -> new double[] {
				base.translationX() + (base.scaleX() / 2.0D),
				vertical,
				base.translationZ() + (u * base.scaleZ())};
		};
	}

	private static BlockFace requireCardinal(BlockFace facing)
	{
		Objects.requireNonNull(facing, "facing");
		return switch(facing)
		{
			case NORTH, SOUTH, EAST, WEST -> facing;
			default -> throw new IllegalArgumentException("Door portal facing must be cardinal: " + facing);
		};
	}
}
