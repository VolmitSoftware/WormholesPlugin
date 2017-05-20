package org.cyberpwn.vortex;

import org.cyberpwn.vortex.network.VortexBus;
import org.cyberpwn.vortex.provider.AutomagicalProvider;
import org.cyberpwn.vortex.provider.PortalProvider;
import org.cyberpwn.vortex.service.ApertureService;
import org.cyberpwn.vortex.service.EntityService;
import org.cyberpwn.vortex.service.IOService;
import org.cyberpwn.vortex.service.MutexService;
import org.cyberpwn.vortex.service.PortalRegistry;
import org.cyberpwn.vortex.service.ProjectionService;
import org.cyberpwn.vortex.service.TimingsService;
import wraith.ControllablePlugin;
import wraith.Direction;
import wraith.TICK;
import wraith.TickHandle;
import wraith.TickHandler;
import wraith.Ticked;

@Ticked(0)
@TickHandle(TickHandler.SYNCED)
public class VP extends ControllablePlugin
{
	public static VP instance;
	public static VortexBus bus;
	public static MutexService host;
	public static PortalProvider provider;
	public static PortalRegistry registry;
	public static ApertureService aperture;
	public static ProjectionService projector;
	public static TimingsService timings;
	public static EntityService entity;
	public static IOService io;
	
	@Override
	public void onStart()
	{
		Direction.calculatePermutations();
		instance = this;
		timings = new TimingsService();
		VP.instance.getServer().getMessenger().registerOutgoingPluginChannel(VP.instance, "BungeeCord");
		bus = new VortexBus();
		registry = new PortalRegistry();
		host = new MutexService();
		aperture = new ApertureService();
		projector = new ProjectionService();
		provider = new AutomagicalProvider();
		entity = new EntityService();
		io = new IOService();
		provider.loadAllPortals();
	}
	
	@Override
	public void onStop()
	{
		
	}
	
	@Override
	public void onTick()
	{
		try
		{
			bus.flush();
			host.flush();
			provider.flush();
			projector.flush();
			
			if(TICK.tick % 2 == 0)
			{
				aperture.flush();
				entity.flush();
			}
		}
		
		catch(Throwable e)
		{
			e.printStackTrace();
		}
	}
	
	@Override
	public void onConstruct()
	{
		
	}
}
