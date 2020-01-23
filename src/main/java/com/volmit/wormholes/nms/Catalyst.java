package com.volmit.wormholes.nms;

public class Catalyst
{
	public static final CatalystHost host = getHost();

	private static CatalystHost getHost()
	{
		switch(NMSVersion.current())
		{
			case R1_10:
				return new Catalyst10();
			case R1_11:
				return new Catalyst11();
			case R1_12:
				return new Catalyst12();
			case R1_13:
				return new Catalyst13();
			case R1_8:
				return new Catalyst8();
			case R1_9_4:
				return new Catalyst94();
			default:
				return null;
		}
	}
}
