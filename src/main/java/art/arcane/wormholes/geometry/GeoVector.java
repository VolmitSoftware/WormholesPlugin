package art.arcane.wormholes.geometry;
public class GeoVector {

	private GeoPoint p0; // vector begin point
	private GeoPoint p1; // vector end point
	private double x; // vector x axis projection value
	private double y; // vector y axis projection value
	private double z; // vector z axis projection value

	public GeoPoint getP0() {return this.p0;}
	public GeoPoint getP1() {return this.p1;}
	public double getX() {return this.x;}
	public double getY() {return this.y;}
	public double getZ() {return this.z;}

	public GeoVector() {}

	public GeoVector(GeoPoint p0, GeoPoint p1)
	{
		this.p0 = p0;
		this.p1 = p1;
		this.x = p1.getX() - p0.getX();
		this.y = p1.getY() - p0.getY();
		this.z = p1.getZ() - p0.getZ();
	}

	public static GeoVector Multiple(GeoVector u, GeoVector v)
	{
		double x = u.getY() * v.getZ() - u.getZ() * v.getY();
		double y = u.getZ() * v.getX() - u.getX() * v.getZ();
		double z = u.getX() * v.getY() - u.getY() * v.getX();

		GeoPoint p0 = v.getP0();
		GeoPoint p1 = GeoPoint.Add(p0, new GeoPoint(x, y, z));

		return new GeoVector(p0, p1);
	}
}