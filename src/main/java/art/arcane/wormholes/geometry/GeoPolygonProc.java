package art.arcane.wormholes.geometry;

import java.util.ArrayList;

public class GeoPolygonProc {

	private double MaxUnitMeasureError = 0.001;

	// Polygon Boundary
	private double X0, X1, Y0, Y1, Z0, Z1;

	// Polygon faces
	private ArrayList<GeoFace> Faces;

	// Polygon face planes
	private ArrayList<GeoPlane> FacePlanes;

	// Number of faces
	private int NumberOfFaces;

	// Maximum point to face plane distance error,
	// point is considered in the face plane if its distance is less than this error
	private double MaxDisError;

	public double getX0() { return this.X0; }
	public double getX1() { return this.X1; }
	public double getY0() { return this.Y0; }
	public double getY1() { return this.Y1; }
	public double getZ0() { return this.Z0; }
	public double getZ1() { return this.Z1; }
	public ArrayList<GeoFace> getFaces() { return this.Faces; }
	public ArrayList<GeoPlane> GetFacePlanes() { return this.FacePlanes; }
	public int getNumberOfFaces() { return this.NumberOfFaces; }

	public GeoPolygonProc(){}

	public GeoPolygonProc(GeoPolygon polygonInst)
	{

		// Set boundary
		this.Set3DPolygonBoundary(polygonInst);

		// Set maximum point to face plane distance error,
		this.Set3DPolygonUnitError(polygonInst);

		// Set faces and face planes
		this.SetConvex3DFaces(polygonInst);
	}

	public boolean PointInside3DPolygon(double x, double y, double z)
	{
		GeoPoint P = new GeoPoint(x, y, z);

		for (int i = 0; i < this.NumberOfFaces; i++)
		{

			double dis = GeoPlane.Multiple(P, this.FacePlanes.get(i));

			// If the point is in the same half space with normal vector for any face of the cube,
			// then it is outside of the 3D polygon
			if (dis > 0)
			{
				return false;
			}
		}

		// If the point is in the opposite half space with normal vector for all 6 faces,
		// then it is inside of the 3D polygon
		return true;
	}

	private void Set3DPolygonUnitError(GeoPolygon polygon)
	{
		this.MaxDisError = ((Math.abs(this.X0) + Math.abs(this.X1) +
				Math.abs(this.Y0) + Math.abs(this.Y1) +
				Math.abs(this.Z0) + Math.abs(this.Z1)) / 6 * MaxUnitMeasureError);
	}

	private void Set3DPolygonBoundary(GeoPolygon polygon)
	{
		ArrayList<GeoPoint> vertices = polygon.getV();

		int n = polygon.getN();

		double xmin, xmax, ymin, ymax, zmin, zmax;

		xmin = xmax = vertices.get(0).getX();
		ymin = ymax = vertices.get(0).getY();
		zmin = zmax = vertices.get(0).getZ();

		for (int i = 1; i < n; i++)
		{
			if (vertices.get(i).getX() < xmin) xmin = vertices.get(i).getX();
			if (vertices.get(i).getY() < ymin) ymin = vertices.get(i).getY();
			if (vertices.get(i).getZ() < zmin) zmin = vertices.get(i).getZ();
			if (vertices.get(i).getX() > xmax) xmax = vertices.get(i).getX();
			if (vertices.get(i).getY() > ymax) ymax = vertices.get(i).getY();
			if (vertices.get(i).getZ() > zmax) zmax = vertices.get(i).getZ();
		}

		this.X0 = xmin;
		this.X1 = xmax;
		this.Y0 = ymin;
		this.Y1 = ymax;
		this.Z0 = zmin;
		this.Z1 = zmax;
	}

	private void SetConvex3DFaces(GeoPolygon polygon)
	{
		ArrayList<GeoFace> faces = new ArrayList<GeoFace>();

		ArrayList<GeoPlane> facePlanes = new ArrayList<GeoPlane>();

		int numberOfFaces;

		double maxError = this.MaxDisError;

		// vertices of 3D polygon
		ArrayList<GeoPoint> vertices = polygon.getV();

		int n = polygon.getN();

		// vertices indexes for all faces
		// vertices index is the original index value in the input polygon
		ArrayList<ArrayList<Integer>> faceVerticeIndex = new ArrayList<ArrayList<Integer>>();

		// face planes for all faces
		ArrayList<GeoPlane> fpOutward = new ArrayList<GeoPlane>();

		for(int i=0; i< n; i++)
		{
			// triangle point 1
			GeoPoint p0 = vertices.get(i);

			for(int j= i+1; j< n; j++)
			{
				// triangle point 2
				GeoPoint p1 = vertices.get(j);

				for(int k = j + 1; k<n; k++)
				{
					// triangle point 3
					GeoPoint p2 = vertices.get(k);

					GeoPlane trianglePlane = new GeoPlane(p0, p1, p2);

					int onLeftCount = 0;
					int onRightCount = 0;

					// indexes of points that lie in same plane with face triangle plane
					ArrayList<Integer> pointInSamePlaneIndex = new ArrayList<Integer>();

					for(int l = 0; l < n ; l ++)
					{
						// any point other than the 3 triangle points
						if(l != i && l != j && l != k)
						{
							GeoPoint p = vertices.get(l);

							double dis = GeoPlane.Multiple(p, trianglePlane);

							// next point is in the triangle plane
							if(Math.abs(dis) < maxError)
							{
								pointInSamePlaneIndex.add(l);
							}
							else
							{
								if(dis < 0)
								{
									onLeftCount ++;
								}
								else
								{
									onRightCount ++;
								}
							}
						}
					}

					// This is a face for a CONVEX 3d polygon.
					// For a CONCAVE 3d polygon, this maybe not a face.
					if(onLeftCount == 0 || onRightCount == 0)
					{
						ArrayList<Integer> verticeIndexInOneFace = new ArrayList<Integer>();

						// triangle plane
						verticeIndexInOneFace.add(i);
						verticeIndexInOneFace.add(j);
						verticeIndexInOneFace.add(k);

						int m = pointInSamePlaneIndex.size();

						if(m > 0) // there are other vertices in this triangle plane
						{
							for(int p = 0; p < m; p ++)
							{
								verticeIndexInOneFace.add(pointInSamePlaneIndex.get(p));
							}
						}

						// if verticeIndexInOneFace is a new face,
						// add it in the faceVerticeIndex list,
						// add the trianglePlane in the face plane list fpOutward
						if(!faceVerticeIndex.contains(verticeIndexInOneFace))
						{
							faceVerticeIndex.add(verticeIndexInOneFace);

							if (onRightCount == 0)
							{
								fpOutward.add(trianglePlane);
							}
							else if (onLeftCount == 0)
							{
								fpOutward.add(GeoPlane.Negative(trianglePlane));
							}
						}
					}
					else
					{
						// possible reasons:
						// 1. the plane is not a face of a convex 3d polygon,
						//    it is a plane crossing the convex 3d polygon.
						// 2. the plane is a face of a concave 3d polygon
					}

				} // k loop
			} // j loop
		} // i loop

		// return number of faces
		numberOfFaces = faceVerticeIndex.size();

		for (int i = 0; i < numberOfFaces; i++)
		{
			// return face planes
			facePlanes.add(new GeoPlane(fpOutward.get(i).getA(), fpOutward.get(i).getB(),
					fpOutward.get(i).getC(), fpOutward.get(i).getD()));

			ArrayList<GeoPoint> gp = new ArrayList<GeoPoint>();

			ArrayList<Integer> vi = new ArrayList<Integer>();

			int count = faceVerticeIndex.get(i).size();
			for (int j = 0; j < count; j++)
			{
				vi.add(faceVerticeIndex.get(i).get(j));
				gp.add( new GeoPoint(vertices.get(vi.get(j)).getX(),
						vertices.get(vi.get(j)).getY(),
						vertices.get(vi.get(j)).getZ()));
			}

			// return faces
			faces.add(new GeoFace(gp, vi));
		}

		this.Faces = faces;
		this.FacePlanes = facePlanes;
		this.NumberOfFaces = numberOfFaces;
	}
}