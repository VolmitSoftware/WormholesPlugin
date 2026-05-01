package art.arcane.wormholes.portal;

import java.io.IOException;

public interface IPersistant
{
	public void save();

	public boolean needsSaving();

	public void willSave();

	public void saveNow() throws IOException;

	public void deleteData();
}
