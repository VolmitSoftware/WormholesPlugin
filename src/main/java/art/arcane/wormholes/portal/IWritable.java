package art.arcane.wormholes.portal;

import art.arcane.wormholes.util.JSONObject;

public interface IWritable
{
	public void loadJSON(JSONObject j);

	public void saveJSON(JSONObject j);

	public JSONObject toJSON();
}
