package art.arcane.wormholes.portal;

public enum MirrorRotation
{
	DEGREES_0(0),
	DEGREES_90(90),
	DEGREES_180(180),
	DEGREES_270(270);

	private static final MirrorRotation[] CYCLE = values();
	private final int degrees;

	MirrorRotation(int degrees)
	{
		this.degrees = degrees;
	}

	public int getDegrees()
	{
		return degrees;
	}

	public int getQuarterTurns()
	{
		return degrees / 90;
	}

	public MirrorRotation clockwise()
	{
		return CYCLE[(ordinal() + 1) % CYCLE.length];
	}

	public MirrorRotation counterClockwise()
	{
		return CYCLE[(ordinal() + CYCLE.length - 1) % CYCLE.length];
	}

	public MirrorRotation clockwiseFor(PortalFrame frame)
	{
		if(supportsQuarterTurns(frame))
		{
			return clockwise();
		}
		return coherentFor(frame) == DEGREES_0 ? DEGREES_180 : DEGREES_0;
	}

	public MirrorRotation counterClockwiseFor(PortalFrame frame)
	{
		if(supportsQuarterTurns(frame))
		{
			return counterClockwise();
		}
		return coherentFor(frame) == DEGREES_0 ? DEGREES_180 : DEGREES_0;
	}

	public MirrorRotation coherentFor(PortalFrame frame)
	{
		if(supportsQuarterTurns(frame) || this == DEGREES_0 || this == DEGREES_180)
		{
			return this;
		}
		return this == DEGREES_90 ? DEGREES_0 : DEGREES_180;
	}

	public static boolean supportsQuarterTurns(PortalFrame frame)
	{
		return frame != null && frame.getNormal().isVertical();
	}

	public static MirrorRotation fromDegrees(int degrees)
	{
		int normalized = Math.floorMod(degrees, 360);
		return switch(normalized)
		{
			case 90 -> DEGREES_90;
			case 180 -> DEGREES_180;
			case 270 -> DEGREES_270;
			default -> DEGREES_0;
		};
	}
}
