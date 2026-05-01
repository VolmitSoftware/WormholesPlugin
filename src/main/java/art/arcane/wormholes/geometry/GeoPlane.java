package art.arcane.wormholes.geometry;
public class GeoPlane {

	// Plane Equation: a * x + b * y + c * z + d = 0

	private double a;
	private double b;
	private double c;
	private double d;

	public double getA() { return this.a; }
	public double getB() { return this.b; }
	public double getC() { return this.c; }
	public double getD() { return this.d; }

	public GeoPlane() {}

	public GeoPlane(double a, double b, double c, double d)
	{
		this.a = a;
		this.b = b;
		this.c = c;
		this.d = d;
	}

	public GeoPlane(GeoPoint p0, GeoPoint p1, GeoPoint p2)
	{
		GeoVector v = new GeoVector(p0, p1);

		GeoVector u = new GeoVector(p0, p2);

		GeoVector n = GeoVector.Multiple(u, v);

		// normal vector
		double a = n.getX();
		double b = n.getY();
		double c = n.getZ();
		double d = -(a * p0.getX() + b * p0.getY() + c * p0.getZ());

		this.a = a;
		this.b = b;
		this.c = c;
		this.d = d;
	}

	public static GeoPlane Negative(GeoPlane pl)
	{
		return new GeoPlane(-pl.getA(), -pl.getB(), -pl.getC(), -pl.getD());
	}

	public static double Multiple(GeoPoint pt, GeoPlane pl)
	{
		return (pt.getX() * pl.getA() + pt.getY() * pl.getB() +
				pt.getZ() * pl.getC() + pl.getD());
	}
}