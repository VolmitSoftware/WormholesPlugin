package art.arcane.wormholes.portal;

public interface IProgressivePortal
{
	public void showProgress(String progress);

	public String getCurrentProgress();

	public void hideProgress();

	public boolean isShowingProgress();
}
