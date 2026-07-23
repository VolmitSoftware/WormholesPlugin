package art.arcane.wormholes.portal.rtp;

import java.lang.reflect.Method;
import java.util.Objects;
import java.util.function.Supplier;

import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.plugin.Plugin;

public final class IrisTerrainProbe
{
	private static final String TOOLBELT_CLASS = "art.arcane.iris.core.tools.IrisToolbelt";
	private static final IrisTerrainProbe SHARED = new IrisTerrainProbe(TOOLBELT_CLASS, IrisTerrainProbe::irisClassLoader);

	private final String toolbeltClassName;
	private final Supplier<ClassLoader> classLoaderSupplier;
	private volatile Resolution resolution;

	IrisTerrainProbe(String toolbeltClassName, Supplier<ClassLoader> classLoaderSupplier)
	{
		this.toolbeltClassName = Objects.requireNonNull(toolbeltClassName, "toolbeltClassName");
		this.classLoaderSupplier = Objects.requireNonNull(classLoaderSupplier, "classLoaderSupplier");
	}

	public static IrisTerrainProbe shared()
	{
		return SHARED;
	}

	public Boolean isUnderwater(World world, int blockX, int blockZ)
	{
		try
		{
			Resolution active = resolve();
			if(active == null)
			{
				return null;
			}
			Object generator = active.access().invoke(null, world);
			if(generator == null)
			{
				return null;
			}
			Object engine = active.getEngine().invoke(generator);
			if(engine == null)
			{
				return null;
			}
			if(Boolean.TRUE.equals(active.isClosed().invoke(engine)))
			{
				return null;
			}
			Object mantle = active.getMantle().invoke(engine);
			if(mantle == null)
			{
				return null;
			}
			Object underwater = active.isUnderwater().invoke(mantle, Integer.valueOf(blockX), Integer.valueOf(blockZ));
			return underwater instanceof Boolean result ? result : null;
		}
		catch(ReflectiveOperationException | RuntimeException | LinkageError failure)
		{
			return null;
		}
	}

	private Resolution resolve()
	{
		ClassLoader loader = classLoaderSupplier.get();
		if(loader == null)
		{
			return null;
		}
		Resolution cached = resolution;
		if(cached != null && cached.loader() == loader)
		{
			return cached;
		}
		try
		{
			Class<?> toolbelt = Class.forName(toolbeltClassName, false, loader);
			Method access = toolbelt.getMethod("access", World.class);
			Method getEngine = access.getReturnType().getMethod("getEngine");
			Class<?> engineType = getEngine.getReturnType();
			Method isClosed = engineType.getMethod("isClosed");
			Method getMantle = engineType.getMethod("getMantle");
			Method isUnderwater = getMantle.getReturnType().getMethod("isUnderwater", int.class, int.class);
			Resolution resolved = new Resolution(loader, access, getEngine, isClosed, getMantle, isUnderwater);
			resolution = resolved;
			return resolved;
		}
		catch(ReflectiveOperationException | RuntimeException | LinkageError failure)
		{
			return null;
		}
	}

	private static ClassLoader irisClassLoader()
	{
		Plugin plugin = Bukkit.getPluginManager().getPlugin("Iris");
		if(plugin == null || !plugin.isEnabled())
		{
			return null;
		}
		return plugin.getClass().getClassLoader();
	}

	private record Resolution(
			ClassLoader loader,
			Method access,
			Method getEngine,
			Method isClosed,
			Method getMantle,
			Method isUnderwater)
	{
	}
}
