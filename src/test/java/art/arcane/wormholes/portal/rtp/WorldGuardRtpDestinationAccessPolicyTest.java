package art.arcane.wormholes.portal.rtp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Proxy;
import java.util.List;

import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;

public final class WorldGuardRtpDestinationAccessPolicyTest
{
	@Test
	public void absentWorldGuardAllowsPhysicalOnlyFlow()
	{
		TestEnvironment environment = TestEnvironment.absent();
		WorldGuardRtpDestinationAccessPolicy policy = new WorldGuardRtpDestinationAccessPolicy(environment);

		RtpAccessResult result = policy.canUse(player(), destination()).join();

		assertTrue(result.allowed());
		assertEquals(RtpAccessResult.Status.ALLOWED, result.status());
		assertTrue(result.failure().isEmpty());
		assertEquals(0, environment.worldResolutionCount);
		assertEquals(0, environment.classLoadCount);
	}

	@Test
	public void serializedWorldKeyLookupFindsMatchingLoadedWorld()
	{
		World expectedWorld = world("minecraft:overworld");

		World resolvedWorld = WorldGuardRtpDestinationAccessPolicy.findWorldBySerializedKey(
				List.of(world("minecraft:the_nether"), expectedWorld),
				"minecraft:overworld");

		assertSame(expectedWorld, resolvedWorld);
	}

	@Test
	public void installedWorldGuardAllowsEntryAtDestination()
	{
		FakeContext context = new FakeContext(false, true);
		TestEnvironment environment = TestEnvironment.installed(context);
		WorldGuardRtpDestinationAccessPolicy policy = new WorldGuardRtpDestinationAccessPolicy(environment);

		RtpAccessResult result = policy.canUse(player(), destination()).join();

		assertTrue(result.allowed());
		assertEquals(RtpAccessResult.Status.ALLOWED, result.status());
		assertEquals("minecraft:overworld", environment.resolvedWorldKey);
		assertEquals(1, context.queryCount);
		assertEquals(12.5D, context.queriedLocation.getX());
		assertEquals(64.0D, context.queriedLocation.getY());
		assertEquals(-7.5D, context.queriedLocation.getZ());
		assertEquals(0, context.sessionGetCount);
		assertEquals(0, context.testMoveToCount);
	}

	@Test
	public void installedWorldGuardDeniesEntryAtDestination()
	{
		FakeContext context = new FakeContext(false, false);
		WorldGuardRtpDestinationAccessPolicy policy = new WorldGuardRtpDestinationAccessPolicy(TestEnvironment.installed(context));

		RtpAccessResult result = policy.canUse(player(), destination()).join();

		assertFalse(result.allowed());
		assertEquals(RtpAccessResult.Status.DENIED, result.status());
		assertTrue(result.failure().isEmpty());
		assertEquals(1, context.queryCount);
		assertEquals(0, context.sessionGetCount);
		assertEquals(0, context.testMoveToCount);
	}

	@Test
	public void targetWorldBypassAllowsWithoutRegionQuery()
	{
		FakeContext context = new FakeContext(true, false);
		WorldGuardRtpDestinationAccessPolicy policy = new WorldGuardRtpDestinationAccessPolicy(TestEnvironment.installed(context));

		RtpAccessResult result = policy.canUse(player(), destination()).join();

		assertTrue(result.allowed());
		assertEquals(RtpAccessResult.Status.ALLOWED, result.status());
		assertEquals(0, context.queryCount);
		assertEquals(0, context.sessionGetCount);
		assertEquals(0, context.testMoveToCount);
	}

	@Test
	public void incompatibleWorldGuardApiFailsClosedWithFullException()
	{
		FakeContext context = new FakeContext(false, true);
		WorldGuardRtpDestinationAccessPolicy policy = new WorldGuardRtpDestinationAccessPolicy(TestEnvironment.incompatible(context));

		RtpAccessResult result = policy.canUse(player(), destination()).join();

		assertFalse(result.allowed());
		assertEquals(RtpAccessResult.Status.FAILURE, result.status());
		Throwable failure = result.failure().orElseThrow();
		assertEquals(NoSuchFieldException.class, failure.getClass());
		assertEquals("ENTRY", failure.getMessage());
		assertEquals(0, context.sessionGetCount);
		assertEquals(0, context.testMoveToCount);
	}

	@Test
	public void worldGuardQueryExceptionFailsClosedWithOriginalException()
	{
		IllegalStateException failure = new IllegalStateException("query failed");
		FakeContext context = FakeContext.failing(failure);
		WorldGuardRtpDestinationAccessPolicy policy = new WorldGuardRtpDestinationAccessPolicy(TestEnvironment.installed(context));

		RtpAccessResult result = policy.canUse(player(), destination()).join();

		assertFalse(result.allowed());
		assertEquals(RtpAccessResult.Status.FAILURE, result.status());
		assertSame(failure, result.failure().orElseThrow());
		assertEquals(1, context.queryCount);
		assertEquals(0, context.sessionGetCount);
		assertEquals(0, context.testMoveToCount);
	}

	private static Player player()
	{
		return (Player) Proxy.newProxyInstance(Player.class.getClassLoader(), new Class<?>[] { Player.class }, (proxy, method, arguments) -> switch(method.getName())
		{
			case "toString" -> "WorldGuardRtpDestinationAccessPolicyTestPlayer";
			case "hashCode" -> Integer.valueOf(System.identityHashCode(proxy));
			case "equals" -> Boolean.valueOf(proxy == arguments[0]);
			default -> throw new UnsupportedOperationException(method.getName());
		});
	}

	private static World world()
	{
		return world("minecraft:overworld");
	}

	private static World world(String serializedKey)
	{
		NamespacedKey key = NamespacedKey.fromString(serializedKey);
		if(key == null)
		{
			throw new IllegalArgumentException("Invalid world key: " + serializedKey);
		}
		return (World) Proxy.newProxyInstance(World.class.getClassLoader(), new Class<?>[] { World.class }, (proxy, method, arguments) -> switch(method.getName())
		{
			case "getKey" -> key;
			case "getName" -> "world";
			case "toString" -> "WorldGuardRtpDestinationAccessPolicyTestWorld";
			case "hashCode" -> Integer.valueOf(System.identityHashCode(proxy));
			case "equals" -> Boolean.valueOf(proxy == arguments[0]);
			default -> throw new UnsupportedOperationException(method.getName());
		});
	}

	private static RtpDestination destination()
	{
		return new RtpDestination("minecraft:overworld", 12, 64, -8, 1L, 0);
	}

	private static final class TestEnvironment implements WorldGuardRtpDestinationAccessPolicy.Environment
	{
		private final Object plugin;
		private final World world;
		private final boolean enabled;
		private int worldResolutionCount;
		private int classLoadCount;
		private String resolvedWorldKey;
		private boolean incompatible;

		private TestEnvironment(Object plugin, World world, boolean enabled)
		{
			this.plugin = plugin;
			this.world = world;
			this.enabled = enabled;
		}

		private static TestEnvironment absent()
		{
			return new TestEnvironment(null, null, false);
		}

		private static TestEnvironment installed(FakeContext context)
		{
			FakeWorldGuard.instance = new FakeWorldGuard(context);
			FakeWorldGuardPlugin.instance = new FakeWorldGuardPlugin(context);
			return new TestEnvironment(new Object(), world(), true);
		}

		private static TestEnvironment incompatible(FakeContext context)
		{
			TestEnvironment environment = installed(context);
			environment.incompatible = true;
			return environment;
		}

		@Override
		public Object findPlugin(String name)
		{
			assertEquals("WorldGuard", name);
			return plugin;
		}

		@Override
		public boolean isPluginEnabled(Object candidate)
		{
			assertEquals(plugin, candidate);
			return enabled;
		}

		@Override
		public World resolveWorld(String worldKey)
		{
			worldResolutionCount++;
			resolvedWorldKey = worldKey;
			return world;
		}

		@Override
		public Class<?> loadClass(Object candidate, String className) throws ClassNotFoundException
		{
			classLoadCount++;
			assertEquals(plugin, candidate);
			return switch(className)
			{
				case "com.sk89q.worldguard.WorldGuard" -> FakeWorldGuard.class;
				case "com.sk89q.worldguard.bukkit.WorldGuardPlugin" -> FakeWorldGuardPlugin.class;
				case "com.sk89q.worldedit.bukkit.BukkitAdapter" -> FakeBukkitAdapter.class;
				case "com.sk89q.worldguard.protection.flags.Flags" -> incompatible ? FakeMalformedFlags.class : FakeFlags.class;
				default -> throw new ClassNotFoundException(className);
			};
		}
	}

	private static final class FakeContext
	{
		private final boolean bypass;
		private final boolean entryAllowed;
		private int queryCount;
		private int sessionGetCount;
		private int testMoveToCount;
		private Location queriedLocation;
		private RuntimeException queryFailure;

		private FakeContext(boolean bypass, boolean entryAllowed)
		{
			this.bypass = bypass;
			this.entryAllowed = entryAllowed;
		}

		private static FakeContext failing(RuntimeException failure)
		{
			FakeContext context = new FakeContext(false, false);
			context.queryFailure = failure;
			return context;
		}
	}

	public static final class FakeWorldGuard
	{
		private static FakeWorldGuard instance;

		private final FakePlatform platform;

		private FakeWorldGuard(FakeContext context)
		{
			platform = new FakePlatform(context);
		}

		public static FakeWorldGuard getInstance()
		{
			return instance;
		}

		public FakePlatform getPlatform()
		{
			return platform;
		}
	}

	public static final class FakeWorldGuardPlugin
	{
		private static FakeWorldGuardPlugin instance;

		private final FakeContext context;

		private FakeWorldGuardPlugin(FakeContext context)
		{
			this.context = context;
		}

		public static FakeWorldGuardPlugin inst()
		{
			return instance;
		}

		public FakeLocalPlayer wrapPlayer(Player player)
		{
			return new FakeLocalPlayer(context, player);
		}
	}

	public static final class FakeBukkitAdapter
	{
		public static FakeWorld adapt(World world)
		{
			return new FakeWorld(world);
		}

		public static FakeLocation adapt(Location location)
		{
			return new FakeLocation(location);
		}
	}

	public static final class FakeFlags
	{
		public static final FakeStateFlag ENTRY = new FakeStateFlag();

		private FakeFlags()
		{
		}
	}

	public static final class FakeMalformedFlags
	{
		private FakeMalformedFlags()
		{
		}
	}

	public static final class FakePlatform
	{
		private final FakeSessionManager sessionManager;
		private final FakeRegionContainer regionContainer;

		private FakePlatform(FakeContext context)
		{
			sessionManager = new FakeSessionManager(context);
			regionContainer = new FakeRegionContainer(context);
		}

		public FakeSessionManager getSessionManager()
		{
			return sessionManager;
		}

		public FakeRegionContainer getRegionContainer()
		{
			return regionContainer;
		}
	}

	public static final class FakeSessionManager
	{
		private final FakeContext context;

		private FakeSessionManager(FakeContext context)
		{
			this.context = context;
		}

		public boolean hasBypass(FakeLocalPlayer player, FakeWorld world)
		{
			return context.bypass;
		}

		public FakeSession get(FakeLocalPlayer player)
		{
			context.sessionGetCount++;
			return new FakeSession(context);
		}
	}

	public static final class FakeSession
	{
		private final FakeContext context;

		private FakeSession(FakeContext context)
		{
			this.context = context;
		}

		public Object testMoveTo(FakeLocalPlayer player, FakeLocation location, Object moveType)
		{
			context.testMoveToCount++;
			return null;
		}
	}

	public static final class FakeRegionContainer
	{
		private final FakeContext context;

		private FakeRegionContainer(FakeContext context)
		{
			this.context = context;
		}

		public FakeRegionQuery createQuery()
		{
			return new FakeRegionQuery(context);
		}
	}

	public static final class FakeRegionQuery
	{
		private final FakeContext context;

		private FakeRegionQuery(FakeContext context)
		{
			this.context = context;
		}

		public boolean testState(FakeLocation location, FakeLocalPlayer player, FakeStateFlag... flags)
		{
			assertEquals(1, flags.length);
			assertEquals(FakeFlags.ENTRY, flags[0]);
			context.queryCount++;
			context.queriedLocation = location.location;
			if(context.queryFailure != null)
			{
				throw context.queryFailure;
			}
			return context.entryAllowed;
		}
	}

	public static final class FakeLocalPlayer
	{
		private final FakeContext context;
		private final Player player;

		private FakeLocalPlayer(FakeContext context, Player player)
		{
			this.context = context;
			this.player = player;
		}
	}

	public static final class FakeWorld
	{
		private final World world;

		private FakeWorld(World world)
		{
			this.world = world;
		}
	}

	public static final class FakeLocation
	{
		private final Location location;

		private FakeLocation(Location location)
		{
			this.location = location;
		}
	}

	public static final class FakeStateFlag
	{
	}
}
