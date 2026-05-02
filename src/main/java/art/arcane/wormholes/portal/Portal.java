package art.arcane.wormholes.portal;

import java.util.UUID;

import org.bukkit.util.Vector;

import art.arcane.wormholes.util.Direction;
import art.arcane.wormholes.util.JSONObject;

public abstract class Portal implements IPortal
{
	protected Direction direction;
	private PortalFrame frame;
	private boolean frameLoadedFromJson;
	private UUID id;
	private Vector origin;
	private String name;

	public Portal(UUID id, Vector origin)
	{
		this.id = id;
		this.origin = origin;
		frame = PortalFrame.canonical(Direction.N);
		direction = frame.getNormal();
		frameLoadedFromJson = false;
		name = "Portal " + id.toString().substring(0, 4);
	}

	@Override
	public void saveJSON(JSONObject j)
	{
		j.put("direction", frame.getNormal().name());
		j.put("frame", frame.toJSON());
		j.put("id", getId().toString());
		JSONObject origin = new JSONObject();
		origin.put("x", getOrigin().getX());
		origin.put("y", getOrigin().getY());
		origin.put("z", getOrigin().getZ());
		j.put("origin", origin);
		j.put("name", getName());
	}

	@Override
	public void loadJSON(JSONObject j)
	{
		direction = Direction.valueOf(j.getString("direction"));
		frameLoadedFromJson = j.has("frame");
		frame = frameLoadedFromJson ? PortalFrame.fromJSON(direction, j.getJSONObject("frame")) : PortalFrame.canonical(direction);
		direction = frame.getNormal();
		id = UUID.fromString(j.getString("id"));
		JSONObject origin = j.getJSONObject("origin");
		this.origin = new Vector(origin.getDouble("x"), origin.getDouble("y"), origin.getDouble("z"));
		name = j.getString("name");
	}

	@Override
	public JSONObject toJSON()
	{
		JSONObject o = new JSONObject();
		saveJSON(o);

		return o;
	}

	@Override
	public Vector getOrigin()
	{
		return origin;
	}

	@Override
	public UUID getId()
	{
		return id;
	}

	@Override
	public String getName()
	{
		return name;
	}

	@Override
	public void setName(String name)
	{
		this.name = name;
	}

	@Override
	public Direction getDirection()
	{
		return frame.getNormal();
	}

	@Override
	public PortalFrame getFrame()
	{
		return frame;
	}

	protected boolean hasFrameLoadedFromJson()
	{
		return frameLoadedFromJson;
	}

	protected void applyFrame(PortalFrame frame)
	{
		this.frame = frame;
		this.direction = frame.getNormal();
	}
}
