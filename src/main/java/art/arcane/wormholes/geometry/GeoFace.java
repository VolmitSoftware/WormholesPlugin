package art.arcane.wormholes.geometry;

import java.util.ArrayList;

public class GeoFace {

	// Vertices in one face of the 3D polygon
	private ArrayList<GeoPoint> v;

	// Vertices Index
	private ArrayList<Integer> idx;

	// Number of vertices
	private int n;

	public ArrayList<GeoPoint> getV() { return this.v; }

	public ArrayList<Integer> getI() { return this.idx; }

	public int getN() { return this.n; }

	public GeoFace(){}

	public GeoFace(ArrayList<GeoPoint> p, ArrayList<Integer> idx)
	{
		this.v = new ArrayList<GeoPoint>();

		this.idx = new ArrayList<Integer>();

		this.n = p.size();

		for(int i=0;i<n;i++)
		{
			this.v.add(p.get(i));
			this.idx.add(idx.get(i));
		}
	}
}