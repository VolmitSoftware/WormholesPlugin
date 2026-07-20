package art.arcane.wormholes.platform;

import art.arcane.volmlib.util.scheduling.FoliaScheduler;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.ChunkSnapshot;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.Recipe;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginDescriptionFile;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;

public final class WormholesPlatform {
    private static final Method BUKKIT_GET_MINECRAFT_VERSION = resolveMethod(Bukkit.class, "getMinecraftVersion");
    private static final Method BUKKIT_GET_WORLD = resolveMethod(Bukkit.class, "getWorld", NamespacedKey.class);
    private static final Method BUKKIT_ADD_RECIPE = resolveMethod(Bukkit.class, "addRecipe", Recipe.class, boolean.class);
    private static final Method BUKKIT_REMOVE_RECIPE = resolveMethod(Bukkit.class, "removeRecipe", NamespacedKey.class, boolean.class);
    private static final Method SERVER_IS_ACCEPTING_TRANSFERS = resolveMethod(Server.class, "isAcceptingTransfers");
    private static final Method BUKKIT_IS_OWNED_BY_CURRENT_REGION = resolveMethod(
        Bukkit.class,
        "isOwnedByCurrentRegion",
        World.class,
        int.class,
        int.class,
        int.class,
        int.class
    );
    private static final Method WORLD_GET_CHUNK_AT_ASYNC = resolveMethod(
        World.class,
        "getChunkAtAsync",
        int.class,
        int.class,
        boolean.class
    );
    private static final Method CHUNK_GET_SNAPSHOT = resolveMethod(
        Chunk.class,
        "getChunkSnapshot",
        boolean.class,
        boolean.class,
        boolean.class,
        boolean.class
    );
    private static final Method BLOCK_GET_STATE = resolveMethod(Block.class, "getState", boolean.class);
    private static final Method PERSISTENT_DATA_SERIALIZE = resolveMethod(PersistentDataContainer.class, "serializeToBytes");
    private static final Method PLUGIN_NAMESPACE = resolveMethod(Plugin.class, "namespace");
    private static final Method PLAYER_GET_SEND_VIEW_DISTANCE = resolveMethod(Player.class, "getSendViewDistance");
    private static final Method PLAYER_IS_CHUNK_SENT = resolveMethod(Player.class, "isChunkSent", long.class);
    private static final Method LIVING_CAN_USE_EQUIPMENT_SLOT = resolveMethod(
        LivingEntity.class,
        "canUseEquipmentSlot",
        EquipmentSlot.class
    );
    private static final Method LIVING_IS_LEASHED = resolveMethod(LivingEntity.class, "isLeashed");
    private static final Method ENTITY_GET_SCHEDULER = resolveMethod(Entity.class, "getScheduler");
    private static final Method ENTITY_SCHEDULER_EXECUTE = ENTITY_GET_SCHEDULER == null
        ? null
        : resolveMethod(
            ENTITY_GET_SCHEDULER.getReturnType(),
            "execute",
            Plugin.class,
            Runnable.class,
            Runnable.class,
            long.class
        );
    private static final ThreadLocal<Location> ENTITY_LOCATION = ThreadLocal.withInitial(
        () -> new Location(null, 0.0D, 0.0D, 0.0D)
    );
    private static final Method ENTITY_TELEPORT_ASYNC = resolveMethod(
        Entity.class,
        "teleportAsync",
        Location.class,
        PlayerTeleportEvent.TeleportCause.class
    );

    private WormholesPlatform() {
    }

    public static String minecraftVersion() {
        String direct = invokeString(BUKKIT_GET_MINECRAFT_VERSION, null);
        return selectMinecraftVersion(direct, Bukkit.getBukkitVersion());
    }

    public static String pluginVersion(Plugin plugin) {
        if (plugin == null) {
            return "unknown";
        }
        PluginDescriptionFile description = plugin.getDescription();
        return selectPluginVersion(description == null ? null : description.getVersion());
    }

    public static String pluginNamespace(Plugin plugin) {
        Plugin activePlugin = Objects.requireNonNull(plugin, "plugin");
        String direct = invokeString(PLUGIN_NAMESPACE, activePlugin);
        if (direct != null && !direct.isBlank()) {
            return direct;
        }
        return activePlugin.getName()
            .toLowerCase(Locale.ENGLISH)
            .replaceAll("[^a-z0-9._-]", "_");
    }

    public static boolean isAcceptingTransfers(Server server) {
        Object result = invokeNoThrow(SERVER_IS_ACCEPTING_TRANSFERS, Objects.requireNonNull(server));
        return result instanceof Boolean accepting && accepting.booleanValue();
    }

    public static World getWorld(NamespacedKey key) {
        NamespacedKey worldKey = Objects.requireNonNull(key, "key");
        Object direct = invokeNoThrow(BUKKIT_GET_WORLD, null, worldKey);
        if (direct instanceof World world) {
            return world;
        }
        return findWorld(Bukkit.getWorlds(), worldKey);
    }

    public static CompletableFuture<Chunk> loadChunk(
        Plugin plugin,
        World world,
        int chunkX,
        int chunkZ
    ) {
        return loadChunk(plugin, world, chunkX, chunkZ, true);
    }

    public static CompletableFuture<Chunk> loadChunk(
        Plugin plugin,
        World world,
        int chunkX,
        int chunkZ,
        boolean generate
    ) {
        Plugin activePlugin = Objects.requireNonNull(plugin, "plugin");
        World activeWorld = Objects.requireNonNull(world, "world");
        if (WORLD_GET_CHUNK_AT_ASYNC != null) {
            return invokeChunkFuture(activeWorld, chunkX, chunkZ, generate);
        }

        CompletableFuture<Chunk> future = new CompletableFuture<>();
        Runnable loader = () -> completeChunk(future, activeWorld, chunkX, chunkZ, generate);
        Server server = activePlugin.getServer();
        if (FoliaScheduler.isFoliaThreading(server)) {
            if (!FoliaScheduler.runRegion(activePlugin, activeWorld, chunkX, chunkZ, loader)) {
                future.completeExceptionally(new IllegalStateException("Owning region rejected chunk load"));
            }
            return future;
        }
        if (Bukkit.isPrimaryThread()) {
            loader.run();
            return future;
        }
        try {
            Bukkit.getScheduler().runTask(activePlugin, loader);
        } catch (RuntimeException exception) {
            future.completeExceptionally(exception);
        }
        return future;
    }

    public static CompletableFuture<Boolean> teleport(
        Plugin plugin,
        Entity entity,
        Location target,
        PlayerTeleportEvent.TeleportCause cause
    ) {
        Plugin activePlugin = Objects.requireNonNull(plugin, "plugin");
        Entity activeEntity = Objects.requireNonNull(entity, "entity");
        Location destination = Objects.requireNonNull(target, "target");
        PlayerTeleportEvent.TeleportCause teleportCause = Objects.requireNonNull(cause, "cause");
        if (ENTITY_TELEPORT_ASYNC != null) {
            return invokeTeleportFuture(activeEntity, destination, teleportCause);
        }

        CompletableFuture<Boolean> future = new CompletableFuture<>();
        Runnable teleporter = () -> completeTeleport(future, activeEntity, destination, teleportCause);
        Server server = activePlugin.getServer();
        if (FoliaScheduler.isFoliaThreading(server)) {
            if (!FoliaScheduler.runEntity(activePlugin, activeEntity, teleporter)) {
                future.completeExceptionally(new IllegalStateException("Owning entity scheduler rejected teleport"));
            }
            return future;
        }
        if (Bukkit.isPrimaryThread()) {
            teleporter.run();
            return future;
        }
        try {
            Bukkit.getScheduler().runTask(activePlugin, teleporter);
        } catch (RuntimeException exception) {
            future.completeExceptionally(exception);
        }
        return future;
    }

    public static boolean scheduleEntity(
        Plugin plugin,
        Entity entity,
        Runnable task,
        Runnable retired,
        long delayTicks
    ) {
        Plugin activePlugin = Objects.requireNonNull(plugin, "plugin");
        Entity activeEntity = Objects.requireNonNull(entity, "entity");
        Runnable activeTask = Objects.requireNonNull(task, "task");
        Runnable retiredTask = Objects.requireNonNull(retired, "retired");
        long safeDelay = Math.max(0L, delayTicks);
        Object scheduler = invokeNoThrow(ENTITY_GET_SCHEDULER, activeEntity);
        if (scheduler != null) {
            Object result = invokeNoThrow(
                ENTITY_SCHEDULER_EXECUTE,
                scheduler,
                activePlugin,
                activeTask,
                retiredTask,
                safeDelay
            );
            if (result instanceof Boolean scheduled) {
                return scheduled;
            }
        }
        return FoliaScheduler.runEntity(activePlugin, activeEntity, activeTask, safeDelay, retiredTask);
    }

    public static int sendViewDistance(Player player) {
        if (player == null) {
            return 0;
        }
        Object direct = invokeNoThrow(PLAYER_GET_SEND_VIEW_DISTANCE, player);
        if (direct instanceof Number number) {
            return number.intValue();
        }
        return player.getClientViewDistance();
    }

    public static boolean supportsSentChunkQuery() {
        return PLAYER_IS_CHUNK_SENT != null;
    }

    public static boolean isChunkSent(Player player, int chunkX, int chunkZ) {
        if (player == null || PLAYER_IS_CHUNK_SENT == null) {
            return false;
        }
        long chunkKey = ((long) chunkX & 0xFFFFFFFFL) | (((long) chunkZ & 0xFFFFFFFFL) << 32);
        return Boolean.TRUE.equals(invokeNoThrow(PLAYER_IS_CHUNK_SENT, player, Long.valueOf(chunkKey)));
    }

    public static boolean isLeashed(LivingEntity entity) {
        LivingEntity living = Objects.requireNonNull(entity, "entity");
        Method method = LIVING_IS_LEASHED == null
            ? resolveMethod(living.getClass(), "isLeashed")
            : LIVING_IS_LEASHED;
        return Boolean.TRUE.equals(invokeNoThrow(method, living));
    }

    public static boolean hasChangedPosition(PlayerMoveEvent event) {
        if (event == null || event.getTo() == null) {
            return false;
        }
        Location from = event.getFrom();
        Location to = event.getTo();
        return from.getX() != to.getX()
            || from.getY() != to.getY()
            || from.getZ() != to.getZ()
            || !Objects.equals(from.getWorld(), to.getWorld());
    }

    public static ChunkSnapshot chunkSnapshot(
        Chunk chunk,
        boolean includeMaxBlockY,
        boolean includeBiome,
        boolean includeBiomeTempRain,
        boolean includeLightData
    ) {
        Chunk activeChunk = Objects.requireNonNull(chunk, "chunk");
        if (CHUNK_GET_SNAPSHOT == null) {
            return activeChunk.getChunkSnapshot(includeMaxBlockY, includeBiome, includeBiomeTempRain);
        }
        try {
            Object snapshot = CHUNK_GET_SNAPSHOT.invoke(
                activeChunk,
                includeMaxBlockY,
                includeBiome,
                includeBiomeTempRain,
                includeLightData
            );
            if (snapshot instanceof ChunkSnapshot chunkSnapshot) {
                return chunkSnapshot;
            }
            throw new IllegalStateException("Chunk snapshot API returned no snapshot");
        } catch (IllegalAccessException exception) {
            throw new IllegalStateException("Chunk snapshot API is inaccessible", exception);
        } catch (InvocationTargetException exception) {
            throw propagate("Chunk snapshot capture failed", exception.getCause());
        }
    }

    public static BlockState blockState(Block block, boolean useSnapshot) {
        Block activeBlock = Objects.requireNonNull(block, "block");
        if (BLOCK_GET_STATE == null) {
            return activeBlock.getState();
        }
        try {
            Object state = BLOCK_GET_STATE.invoke(activeBlock, useSnapshot);
            if (state instanceof BlockState blockState) {
                return blockState;
            }
            throw new IllegalStateException("Block state API returned no state");
        } catch (IllegalAccessException exception) {
            throw new IllegalStateException("Block state API is inaccessible", exception);
        } catch (InvocationTargetException exception) {
            throw propagate("Block state capture failed", exception.getCause());
        }
    }

    public static byte[] serializePersistentData(PersistentDataContainer container) throws IOException {
        PersistentDataContainer activeContainer = Objects.requireNonNull(container, "container");
        if (PERSISTENT_DATA_SERIALIZE != null) {
            try {
                Object serialized = PERSISTENT_DATA_SERIALIZE.invoke(activeContainer);
                if (serialized instanceof byte[] bytes) {
                    return bytes;
                }
                throw new IOException("Persistent data API returned no bytes");
            } catch (IllegalAccessException exception) {
                throw new IOException("Persistent data API is inaccessible", exception);
            } catch (InvocationTargetException exception) {
                Throwable cause = exception.getCause();
                if (cause instanceof IOException ioException) {
                    throw ioException;
                }
                throw new IOException("Persistent data serialization failed", cause);
            }
        }
        return serializePersistentDataFallback(activeContainer);
    }

    public static boolean addRecipe(Recipe recipe, boolean resetRecipes) {
        Recipe activeRecipe = Objects.requireNonNull(recipe, "recipe");
        Object direct = invokeNoThrow(BUKKIT_ADD_RECIPE, null, activeRecipe, resetRecipes);
        return direct instanceof Boolean added ? added : Bukkit.addRecipe(activeRecipe);
    }

    public static boolean removeRecipe(NamespacedKey key, boolean resetRecipes) {
        NamespacedKey recipeKey = Objects.requireNonNull(key, "key");
        Object direct = invokeNoThrow(BUKKIT_REMOVE_RECIPE, null, recipeKey, resetRecipes);
        return direct instanceof Boolean removed ? removed : Bukkit.removeRecipe(recipeKey);
    }

    public static ItemStack itemStack(Material material) {
        return new ItemStack(Objects.requireNonNull(material, "material"));
    }

    public static String keyString(NamespacedKey key) {
        return Objects.requireNonNull(key, "key").toString();
    }

    public static boolean canUseEquipmentSlot(LivingEntity entity, EquipmentSlot slot) {
        LivingEntity living = Objects.requireNonNull(entity, "entity");
        EquipmentSlot equipmentSlot = Objects.requireNonNull(slot, "slot");
        Object direct = invokeNoThrow(LIVING_CAN_USE_EQUIPMENT_SLOT, living, equipmentSlot);
        if (direct instanceof Boolean supported) {
            return supported;
        }
        if (living.getEquipment() == null) {
            return false;
        }
        try {
            living.getEquipment().getItem(equipmentSlot);
            return true;
        } catch (IllegalArgumentException | UnsupportedOperationException exception) {
            return false;
        }
    }

    public static void entityPosition(Entity entity, double[] output) {
        Entity activeEntity = Objects.requireNonNull(entity, "entity");
        if (output == null || output.length < 5) {
            throw new IllegalArgumentException("output must contain at least five elements");
        }
        Location location = ENTITY_LOCATION.get();
        try {
            activeEntity.getLocation(location);
            output[0] = location.getX();
            output[1] = location.getY();
            output[2] = location.getZ();
            output[3] = location.getYaw();
            output[4] = location.getPitch();
        } finally {
            location.setWorld(null);
        }
    }

    public static boolean isOwnedByCurrentRegion(Entity entity) {
        return FoliaScheduler.isOwnedByCurrentRegion(entity);
    }

    public static boolean isOwnedByCurrentRegion(World world, int chunkX, int chunkZ) {
        return FoliaScheduler.isOwnedByCurrentRegion(world, chunkX, chunkZ);
    }

    public static boolean isOwnedByCurrentRegion(
        World world,
        int minChunkX,
        int minChunkZ,
        int maxChunkX,
        int maxChunkZ
    ) {
        World activeWorld = Objects.requireNonNull(world, "world");
        int lowerChunkX = Math.min(minChunkX, maxChunkX);
        int lowerChunkZ = Math.min(minChunkZ, maxChunkZ);
        int upperChunkX = Math.max(minChunkX, maxChunkX);
        int upperChunkZ = Math.max(minChunkZ, maxChunkZ);
        Object direct = invokeNoThrow(
            BUKKIT_IS_OWNED_BY_CURRENT_REGION,
            null,
            activeWorld,
            lowerChunkX,
            lowerChunkZ,
            upperChunkX,
            upperChunkZ
        );
        if (direct instanceof Boolean owned) {
            return owned;
        }

        Server server = Bukkit.getServer();
        if (!FoliaScheduler.isFoliaThreading(server)) {
            return Bukkit.isPrimaryThread();
        }
        for (int chunkX = lowerChunkX; chunkX <= upperChunkX; chunkX++) {
            for (int chunkZ = lowerChunkZ; chunkZ <= upperChunkZ; chunkZ++) {
                if (!FoliaScheduler.isOwnedByCurrentRegion(activeWorld, chunkX, chunkZ)) {
                    return false;
                }
            }
        }
        return true;
    }

    static String selectMinecraftVersion(String direct, String bukkitVersion) {
        if (direct != null && !direct.isBlank()) {
            return direct.trim();
        }
        if (bukkitVersion == null || bukkitVersion.isBlank()) {
            return "unknown";
        }
        String value = bukkitVersion.trim();
        int separator = value.indexOf('-');
        return separator <= 0 ? value : value.substring(0, separator);
    }

    static String selectPluginVersion(String version) {
        return version == null || version.isBlank() ? "unknown" : version.trim();
    }

    static World findWorld(Iterable<World> worlds, NamespacedKey key) {
        if (worlds == null || key == null) {
            return null;
        }
        for (World world : worlds) {
            if (world != null && key.equals(world.getKey())) {
                return world;
            }
        }
        return null;
    }

    private static byte[] serializePersistentDataFallback(PersistentDataContainer container) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream output = new DataOutputStream(bytes)) {
            List<NamespacedKey> keys = new ArrayList<>(container.getKeys());
            keys.sort(Comparator.comparing(NamespacedKey::toString));
            output.writeInt(keys.size());
            for (NamespacedKey key : keys) {
                output.writeUTF(key.toString());
                writePersistentValue(output, container, key);
            }
        }
        return bytes.toByteArray();
    }

    private static void writePersistentValue(
        DataOutputStream output,
        PersistentDataContainer container,
        NamespacedKey key
    ) throws IOException {
        if (container.has(key, PersistentDataType.BYTE)) {
            output.writeByte(1);
            output.writeByte(container.getOrDefault(key, PersistentDataType.BYTE, Byte.valueOf((byte) 0)));
        } else if (container.has(key, PersistentDataType.SHORT)) {
            output.writeByte(2);
            output.writeShort(container.getOrDefault(key, PersistentDataType.SHORT, Short.valueOf((short) 0)));
        } else if (container.has(key, PersistentDataType.INTEGER)) {
            output.writeByte(3);
            output.writeInt(container.getOrDefault(key, PersistentDataType.INTEGER, Integer.valueOf(0)));
        } else if (container.has(key, PersistentDataType.LONG)) {
            output.writeByte(4);
            output.writeLong(container.getOrDefault(key, PersistentDataType.LONG, Long.valueOf(0L)));
        } else if (container.has(key, PersistentDataType.FLOAT)) {
            output.writeByte(5);
            output.writeFloat(container.getOrDefault(key, PersistentDataType.FLOAT, Float.valueOf(0.0F)));
        } else if (container.has(key, PersistentDataType.DOUBLE)) {
            output.writeByte(6);
            output.writeDouble(container.getOrDefault(key, PersistentDataType.DOUBLE, Double.valueOf(0.0D)));
        } else if (container.has(key, PersistentDataType.STRING)) {
            output.writeByte(7);
            output.writeUTF(container.getOrDefault(key, PersistentDataType.STRING, ""));
        } else if (container.has(key, PersistentDataType.BYTE_ARRAY)) {
            output.writeByte(8);
            writeByteArray(output, container.getOrDefault(key, PersistentDataType.BYTE_ARRAY, new byte[0]));
        } else if (container.has(key, PersistentDataType.INTEGER_ARRAY)) {
            output.writeByte(9);
            writeIntArray(output, container.getOrDefault(key, PersistentDataType.INTEGER_ARRAY, new int[0]));
        } else if (container.has(key, PersistentDataType.LONG_ARRAY)) {
            output.writeByte(10);
            writeLongArray(output, container.getOrDefault(key, PersistentDataType.LONG_ARRAY, new long[0]));
        } else if (container.has(key, PersistentDataType.TAG_CONTAINER)) {
            output.writeByte(11);
            byte[] nested = serializePersistentDataFallback(container.get(key, PersistentDataType.TAG_CONTAINER));
            writeByteArray(output, nested);
        } else if (container.has(key, PersistentDataType.TAG_CONTAINER_ARRAY)) {
            output.writeByte(12);
            PersistentDataContainer[] nested = container.getOrDefault(
                key,
                PersistentDataType.TAG_CONTAINER_ARRAY,
                new PersistentDataContainer[0]
            );
            output.writeInt(nested.length);
            for (PersistentDataContainer child : nested) {
                writeByteArray(output, serializePersistentDataFallback(child));
            }
        } else {
            output.writeByte(0);
        }
    }

    private static void writeByteArray(DataOutputStream output, byte[] values) throws IOException {
        output.writeInt(values.length);
        output.write(values);
    }

    private static void writeIntArray(DataOutputStream output, int[] values) throws IOException {
        output.writeInt(values.length);
        for (int value : values) {
            output.writeInt(value);
        }
    }

    private static void writeLongArray(DataOutputStream output, long[] values) throws IOException {
        output.writeInt(values.length);
        for (long value : values) {
            output.writeLong(value);
        }
    }

    private static CompletableFuture<Chunk> invokeChunkFuture(
        World world,
        int chunkX,
        int chunkZ,
        boolean generate
    ) {
        try {
            Object value = WORLD_GET_CHUNK_AT_ASYNC.invoke(world, chunkX, chunkZ, generate);
            if (!(value instanceof CompletableFuture<?> future)) {
                return CompletableFuture.failedFuture(new IllegalStateException("Async chunk API returned no future"));
            }
            return future.thenApply(result -> {
                if (result instanceof Chunk chunk) {
                    return chunk;
                }
                throw new IllegalStateException("Async chunk API returned no chunk");
            });
        } catch (IllegalAccessException exception) {
            return CompletableFuture.failedFuture(exception);
        } catch (InvocationTargetException exception) {
            return CompletableFuture.failedFuture(exception.getCause());
        }
    }

    private static CompletableFuture<Boolean> invokeTeleportFuture(
        Entity entity,
        Location target,
        PlayerTeleportEvent.TeleportCause cause
    ) {
        try {
            Object value = ENTITY_TELEPORT_ASYNC.invoke(entity, target, cause);
            if (!(value instanceof CompletableFuture<?> future)) {
                return CompletableFuture.failedFuture(new IllegalStateException("Async teleport API returned no future"));
            }
            return future.thenApply(result -> Boolean.TRUE.equals(result));
        } catch (IllegalAccessException exception) {
            return CompletableFuture.failedFuture(exception);
        } catch (InvocationTargetException exception) {
            return CompletableFuture.failedFuture(exception.getCause());
        }
    }

    private static void completeChunk(
        CompletableFuture<Chunk> future,
        World world,
        int chunkX,
        int chunkZ,
        boolean generate
    ) {
        try {
            future.complete(world.getChunkAt(chunkX, chunkZ, generate));
        } catch (RuntimeException exception) {
            future.completeExceptionally(exception);
        }
    }

    private static void completeTeleport(
        CompletableFuture<Boolean> future,
        Entity entity,
        Location target,
        PlayerTeleportEvent.TeleportCause cause
    ) {
        try {
            future.complete(entity.teleport(target, cause));
        } catch (RuntimeException exception) {
            future.completeExceptionally(exception);
        }
    }

    private static Method resolveMethod(Class<?> owner, String name, Class<?>... parameterTypes) {
        try {
            return owner.getMethod(name, parameterTypes);
        } catch (NoSuchMethodException exception) {
            return null;
        }
    }

    private static String invokeString(Method method, Object owner, Object... arguments) {
        Object value = invokeNoThrow(method, owner, arguments);
        return value instanceof String string ? string : null;
    }

    private static Object invokeNoThrow(Method method, Object owner, Object... arguments) {
        if (method == null) {
            return null;
        }
        try {
            return method.invoke(owner, arguments);
        } catch (IllegalAccessException | InvocationTargetException exception) {
            return null;
        }
    }

    private static RuntimeException propagate(String message, Throwable cause) {
        return cause instanceof RuntimeException runtimeException
            ? runtimeException
            : new IllegalStateException(message, cause);
    }
}
