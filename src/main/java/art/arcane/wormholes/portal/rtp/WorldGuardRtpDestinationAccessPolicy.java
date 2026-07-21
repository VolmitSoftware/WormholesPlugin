package art.arcane.wormholes.portal.rtp;

import java.lang.reflect.Array;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

public final class WorldGuardRtpDestinationAccessPolicy implements RtpDestinationAccessPolicy
{
	private static final String WORLD_GUARD_CLASS = "com.sk89q.worldguard.WorldGuard";
	private static final String WORLD_GUARD_PLUGIN_CLASS = "com.sk89q.worldguard.bukkit.WorldGuardPlugin";
	private static final String BUKKIT_ADAPTER_CLASS = "com.sk89q.worldedit.bukkit.BukkitAdapter";
	private static final String FLAGS_CLASS = "com.sk89q.worldguard.protection.flags.Flags";
	private static final Environment BUKKIT_ENVIRONMENT = new BukkitEnvironment();

	private final Environment environment;

	public WorldGuardRtpDestinationAccessPolicy()
	{
		this(BUKKIT_ENVIRONMENT);
	}

	WorldGuardRtpDestinationAccessPolicy(Environment environment)
	{
		this.environment = Objects.requireNonNull(environment, "environment");
	}

	static World findWorldBySerializedKey(Iterable<? extends World> worlds, String serializedWorldKey)
	{
		for(World world : worlds)
		{
			if(world.getKey().toString().equals(serializedWorldKey))
			{
				return world;
			}
		}
		return null;
	}

	@Override
	public CompletableFuture<RtpAccessResult> canUse(Player player, RtpDestination destination)
	{
		Player requiredPlayer = Objects.requireNonNull(player, "player");
		RtpDestination requiredDestination = Objects.requireNonNull(destination, "destination");
		try
		{
			Object plugin = environment.findPlugin("WorldGuard");
			if(plugin == null)
			{
				return CompletableFuture.completedFuture(RtpAccessResult.allowedResult());
			}
			if(!environment.isPluginEnabled(plugin))
			{
				return CompletableFuture.completedFuture(RtpAccessResult.failureResult(new IllegalStateException("WorldGuard is installed but disabled")));
			}
			return CompletableFuture.completedFuture(evaluate(plugin, requiredPlayer, requiredDestination));
		}
		catch(Exception | LinkageError exception)
		{
			return CompletableFuture.completedFuture(RtpAccessResult.failureResult(unwrapFailure(exception)));
		}
	}

	private RtpAccessResult evaluate(Object plugin, Player player, RtpDestination destination) throws ReflectiveOperationException
	{
		World world = environment.resolveWorld(destination.worldKey());
		if(world == null)
		{
			throw new IllegalStateException("RTP destination world is unavailable: " + destination.worldKey());
		}
		Class<?> worldGuardClass = environment.loadClass(plugin, WORLD_GUARD_CLASS);
		Class<?> worldGuardPluginClass = environment.loadClass(plugin, WORLD_GUARD_PLUGIN_CLASS);
		Class<?> bukkitAdapterClass = environment.loadClass(plugin, BUKKIT_ADAPTER_CLASS);
		Class<?> flagsClass = environment.loadClass(plugin, FLAGS_CLASS);
		Object worldGuard = invokeStatic(worldGuardClass, "getInstance");
		Object platform = invoke(worldGuard, "getPlatform");
		Object worldGuardPlugin = invokeStatic(worldGuardPluginClass, "inst");
		Object localPlayer = invoke(worldGuardPlugin, "wrapPlayer", player);
		Object adaptedWorld = invokeStatic(bukkitAdapterClass, "adapt", world);
		Object sessionManager = invoke(platform, "getSessionManager");
		Object bypass = invoke(sessionManager, "hasBypass", localPlayer, adaptedWorld);
		if(Boolean.TRUE.equals(bypass))
		{
			return RtpAccessResult.allowedResult();
		}
		Location location = new Location(world, destination.blockX() + 0.5D, destination.feetY(), destination.blockZ() + 0.5D);
		Object adaptedLocation = invokeStatic(bukkitAdapterClass, "adapt", location);
		Object regionContainer = invoke(platform, "getRegionContainer");
		Object query = invoke(regionContainer, "createQuery");
		Object entryFlag = flagsClass.getField("ENTRY").get(null);
		return testState(query, adaptedLocation, localPlayer, entryFlag)
				? RtpAccessResult.allowedResult()
				: RtpAccessResult.deniedResult();
	}

	private static Object invokeStatic(Class<?> type, String name, Object... arguments) throws ReflectiveOperationException
	{
		Method method = findMethod(type, name, true, arguments);
		return method.invoke(null, arguments);
	}

	private static Object invoke(Object target, String name, Object... arguments) throws ReflectiveOperationException
	{
		Object requiredTarget = Objects.requireNonNull(target, name + " target");
		Method method = findMethod(requiredTarget.getClass(), name, false, arguments);
		return method.invoke(requiredTarget, arguments);
	}

	private static Method findMethod(Class<?> type, String name, boolean staticMethod, Object[] arguments) throws NoSuchMethodException
	{
		for(Method method : type.getMethods())
		{
			if(!method.getName().equals(name) || Modifier.isStatic(method.getModifiers()) != staticMethod)
			{
				continue;
			}
			Class<?>[] parameterTypes = method.getParameterTypes();
			if(parameterTypes.length != arguments.length)
			{
				continue;
			}
			boolean compatible = true;
			for(int index = 0; index < parameterTypes.length; index++)
			{
				if(!compatible(parameterTypes[index], arguments[index]))
				{
					compatible = false;
					break;
				}
			}
			if(compatible)
			{
				return method;
			}
		}
		throw new NoSuchMethodException(type.getName() + "." + name);
	}

	private static boolean compatible(Class<?> parameterType, Object argument)
	{
		if(argument == null)
		{
			return !parameterType.isPrimitive();
		}
		if(!parameterType.isPrimitive())
		{
			return parameterType.isInstance(argument);
		}
		return parameterType == boolean.class && argument instanceof Boolean
				|| parameterType == byte.class && argument instanceof Byte
				|| parameterType == short.class && argument instanceof Short
				|| parameterType == int.class && argument instanceof Integer
				|| parameterType == long.class && argument instanceof Long
				|| parameterType == float.class && argument instanceof Float
				|| parameterType == double.class && argument instanceof Double
				|| parameterType == char.class && argument instanceof Character;
	}

	private static boolean testState(Object query, Object location, Object localPlayer, Object flag) throws ReflectiveOperationException
	{
		for(Method method : query.getClass().getMethods())
		{
			Class<?>[] parameterTypes = method.getParameterTypes();
			if(!method.getName().equals("testState") || parameterTypes.length != 3 || !parameterTypes[2].isArray())
			{
				continue;
			}
			Class<?> componentType = parameterTypes[2].getComponentType();
			if(!compatible(parameterTypes[0], location) || !compatible(parameterTypes[1], localPlayer) || !compatible(componentType, flag))
			{
				continue;
			}
			Object flags = Array.newInstance(componentType, 1);
			Array.set(flags, 0, flag);
			Object result = method.invoke(query, location, localPlayer, flags);
			if(result instanceof Boolean allowed)
			{
				return allowed.booleanValue();
			}
			throw new IllegalStateException("WorldGuard testState returned a non-boolean result");
		}
		throw new NoSuchMethodException(query.getClass().getName() + ".testState");
	}

	private static Throwable unwrapFailure(Throwable failure)
	{
		Throwable unwrapped = failure;
		while(unwrapped instanceof InvocationTargetException && unwrapped.getCause() != null)
		{
			unwrapped = unwrapped.getCause();
		}
		return unwrapped;
	}

	interface Environment
	{
		Object findPlugin(String name);

		boolean isPluginEnabled(Object plugin);

		World resolveWorld(String worldKey);

		Class<?> loadClass(Object plugin, String className) throws ClassNotFoundException;
	}

	private static final class BukkitEnvironment implements Environment
	{
		@Override
		public Object findPlugin(String name)
		{
			return Bukkit.getPluginManager().getPlugin(name);
		}

		@Override
		public boolean isPluginEnabled(Object plugin)
		{
			return plugin instanceof Plugin bukkitPlugin && bukkitPlugin.isEnabled();
		}

		@Override
		public World resolveWorld(String worldKey)
		{
			return findWorldBySerializedKey(Bukkit.getWorlds(), worldKey);
		}

		@Override
		public Class<?> loadClass(Object plugin, String className) throws ClassNotFoundException
		{
			return Class.forName(className, true, plugin.getClass().getClassLoader());
		}
	}
}
