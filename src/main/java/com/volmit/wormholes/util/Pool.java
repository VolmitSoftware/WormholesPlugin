package com.volmit.wormholes.util;

public interface Pool
{
	public void shove(Runnable op);

	public void shutDown();

	public void shutDownNow();
}
