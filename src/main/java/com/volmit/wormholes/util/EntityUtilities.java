package com.volmit.wormholes.util;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.*;

import com.comphenix.protocol.injector.BukkitUnwrapper;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;

import com.comphenix.protocol.reflect.FieldAccessException;
import com.comphenix.protocol.reflect.FieldUtils;
import com.comphenix.protocol.reflect.FuzzyReflection;
import com.comphenix.protocol.utility.MinecraftReflection;
import com.google.common.collect.Lists;

/**
 * Used to perform certain operations on entities.
 *
 * @author Kristian
 */
public class EntityUtilities {

	private static Field entityTrackerField;
	private static Field trackedEntitiesField;
	private static Field trackedPlayersField;
	private static Field trackerField;

	private static Method hashGetMethod;
	private static Method scanPlayersMethod;

	/*
	 * While this function may look pretty bad, it's essentially just a reflection-warped
	 * version of the following:
	 *
	 *  	@SuppressWarnings("unchecked")
	 *	 	public static void updateEntity2(Entity entity, List<Player> observers) {
	 *
	 *			World world = entity.getWorld();
	 *			WorldServer worldServer = ((CraftWorld) world).getHandle();
	 *
	 *			EntityTracker tracker = worldServer.tracker;
	 *			EntityTrackerEntry entry = (EntityTrackerEntry) tracker.trackedEntities.get(entity.getEntityId());
	 *
	 *			List<EntityPlayer> nmsPlayers = getNmsPlayers(observers);
	 *
	 *			entry.trackedPlayers.removeAll(nmsPlayers);
	 *			entry.scanPlayers(nmsPlayers);
	 *		}
	 *
	 *		private static List<EntityPlayer> getNmsPlayers(List<Player> players) {
	 *			List<EntityPlayer> nsmPlayers = new ArrayList<EntityPlayer>();
	 *
	 *			for (Player bukkitPlayer : players) {
	 *				CraftPlayer craftPlayer = (CraftPlayer) bukkitPlayer;
	 *				nsmPlayers.add(craftPlayer.getHandle());
	 *			}
	 *
	 *			return nsmPlayers;
	 *		}
	 *
	 */
	public static void updateEntity(Entity entity, List<Player> observers) throws FieldAccessException {
		try {
			//EntityTrackerEntry trackEntity = (EntityTrackerEntry) tracker.trackedEntities.get(entity.getEntityId());
			Object trackerEntry = getEntityTrackerEntry(entity.getWorld(), entity.getEntityId());

			if (trackedPlayersField == null) {
				// This one is fairly easy
				trackedPlayersField = FuzzyReflection.fromObject(trackerEntry).getFieldByType("java\\.util\\..*");
			}

			// Phew, finally there.
			Object trackedPlayers = FieldUtils.readField(trackedPlayersField, trackerEntry, false);
			List<Object> nmsPlayers = unwrapBukkit(observers);

			// trackEntity.trackedPlayers.clear();
			if (trackedPlayers instanceof Collection)
				((Collection<?>) trackedPlayers).removeAll(nmsPlayers);
			else ((Map<?, ?>) trackedPlayers).keySet().removeAll(nmsPlayers);


			// We have to rely on a NAME once again. Damn it.
			if (scanPlayersMethod == null) {
				scanPlayersMethod = trackerEntry.getClass().getMethod("scanPlayers", List.class);
			}

			//trackEntity.scanPlayers(server.players);
			scanPlayersMethod.invoke(trackerEntry, nmsPlayers);

		} catch (IllegalArgumentException e) {
			throw e;
		} catch (IllegalAccessException e) {
			throw new FieldAccessException("Security limitation prevents access to 'get' method in IntHashMap", e);
		} catch (InvocationTargetException e) {
			throw new RuntimeException("Exception occurred in Minecraft.", e);
		} catch (SecurityException e) {
			throw new FieldAccessException("Security limitation prevents access to 'scanPlayers' method in trackerEntry.", e);
		} catch (NoSuchMethodException e) {
			throw new FieldAccessException("Cannot find 'scanPlayers' method. Is ProtocolLib up to date?", e);
		}
	}

	/**
	 * Retrieve every client that is receiving information about a given entity.
	 * @param entity - the entity that is being tracked.
	 * @return Every client/player that is tracking the given entity.
	 * @throws FieldAccessException If reflection failed.
	 */
	public static List<Player> getEntityTrackers(Entity entity) {
		try {
			List<Player> result = new ArrayList<Player>();
			Object trackerEntry = getEntityTrackerEntry(entity.getWorld(), entity.getEntityId());

			if (trackedPlayersField == null)
				trackedPlayersField = FuzzyReflection.fromObject(trackerEntry).getFieldByType("java\\.util\\..*");

			Collection<?> trackedPlayers = (Collection<?>) FieldUtils.readField(trackedPlayersField, trackerEntry, false);

			// Wrap every player - we also ensure that the underlying tracker list is immutable
			for (Object tracker : trackedPlayers) {
				if (MinecraftReflection.isMinecraftPlayer(tracker)) {
					result.add((Player) MinecraftReflection.getBukkitEntity(tracker));
				}
			}
			return result;

		} catch (IllegalAccessException e) {
			throw new FieldAccessException("Security limitation prevented access to the list of tracked players.", e);
		} catch (InvocationTargetException e) {
			throw new FieldAccessException("Exception occurred in Minecraft.", e);
		}
	}

	/**
	 * Retrieve the entity tracker entry given a ID.
	 * @param world - world server.
	 * @param entityID - entity ID.
	 * @return The entity tracker entry.
	 * @throws FieldAccessException
	 */
	private static Object getEntityTrackerEntry(World world, int entityID) throws FieldAccessException, IllegalArgumentException, IllegalAccessException, InvocationTargetException {
		BukkitUnwrapper unwrapper = new BukkitUnwrapper();
		Object worldServer = unwrapper.unwrapItem(world);

		if (entityTrackerField == null)
			entityTrackerField = FuzzyReflection.fromObject(worldServer).
					getFieldByType("tracker", MinecraftReflection.getEntityTrackerClass());

		// Get the tracker
		Object tracker = null;

		try {
			tracker = FieldUtils.readField(entityTrackerField, worldServer, false);
		} catch (IllegalAccessException e) {
			throw new FieldAccessException("Cannot access 'tracker' field due to security limitations.", e);
		}

		if (trackedEntitiesField == null) {
			@SuppressWarnings("rawtypes")
			Set<Class> ignoredTypes = new HashSet<Class>();

			// Well, this is more difficult. But we're looking for a Minecraft object that is not
			// created by the constructor(s).
			for (Constructor<?> constructor : tracker.getClass().getConstructors()) {
				for (Class<?> type : constructor.getParameterTypes()) {
					ignoredTypes.add(type);
				}
			}

			// The Minecraft field that's NOT filled in by the constructor
			trackedEntitiesField = FuzzyReflection.fromObject(tracker, true).
					getFieldByType(MinecraftReflection.getMinecraftObjectRegex(), ignoredTypes);
		}

		// Read the entity hashmap
		Object trackedEntities = null;

		try {
			trackedEntities = FieldUtils.readField(trackedEntitiesField, tracker, true);
		} catch (IllegalAccessException e) {
			throw new FieldAccessException("Cannot access 'trackedEntities' field due to security limitations.", e);
		}

		// Getting the "get" method is pretty hard, but first - try to just get it by name
		if (hashGetMethod == null) {

			Class<?> type = trackedEntities.getClass();

			try {
				hashGetMethod = type.getMethod("get", int.class);
			} catch (NoSuchMethodException e) {

				Class<?>[] params = { int.class };

				// Then it's probably the lowest named method that takes an int-parameter
				for (Method method : type.getMethods()) {
					if (Arrays.equals(params, method.getParameterTypes())) {
						if (hashGetMethod == null ||
								method.getName().compareTo(hashGetMethod.getName()) < 0) {
							hashGetMethod = method;
						}
					}
				}
			}
		}

		// Wrap exceptions
		try {
			return hashGetMethod.invoke(trackedEntities, entityID);
		} catch (IllegalArgumentException e) {
			throw e;
		} catch (IllegalAccessException e) {
			throw new FieldAccessException("Security limitation prevents access to 'get' method in IntHashMap", e);
		} catch (InvocationTargetException e) {
			throw new RuntimeException("Exception occurred in Minecraft.", e);
		}
	}

	/**
	 * Retrieve entity from a ID, even it it's newly created.
	 * @return The asssociated entity.
	 * @throws FieldAccessException Reflection error.
	 */
	public static Entity getEntityFromID(World world, int entityID) throws FieldAccessException {
		try {
			Object trackerEntry = getEntityTrackerEntry(world, entityID);
			Object tracker = null;

			// Handle NULL cases
			if (trackerEntry != null) {
				if (trackerField == null) {
					try {
						trackerField = trackerEntry.getClass().getField("tracker");
					} catch (NoSuchFieldException e) {
						// Assume it's the first public entity field then
						trackerField = FuzzyReflection.fromObject(trackerEntry).getFieldByType(
								"tracker", MinecraftReflection.getEntityClass());
					}
				}

				tracker = FieldUtils.readField(trackerField, trackerEntry, true);
			}

			// If the tracker is NULL, we'll just assume this entity doesn't exist
			if (tracker != null)
				return (Entity) MinecraftReflection.getBukkitEntity(tracker);
			else
				return null;

		} catch (Exception e) {
			throw new FieldAccessException("Cannot find entity from ID " + entityID + ".", e);
		}
	}

	private static List<Object> unwrapBukkit(List<Player> players) {

		List<Object> output = Lists.newArrayList();
		BukkitUnwrapper unwrapper = new BukkitUnwrapper();

		// Get the NMS equivalent
		for (Player player : players) {
			Object result = unwrapper.unwrapItem(player);

			if (result != null)
				output.add(result);
			else
				throw new IllegalArgumentException("Cannot unwrap item " + player);
		}

		return output;
	}
}