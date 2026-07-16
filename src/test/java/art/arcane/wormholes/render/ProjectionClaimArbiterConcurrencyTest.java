package art.arcane.wormholes.render;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.ints.IntOpenHashSet;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.bukkit.World;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;

import art.arcane.wormholes.portal.ILocalPortal;
import art.arcane.wormholes.render.view.ProjectionWorldView;

public final class ProjectionClaimArbiterConcurrencyTest {
    private static final long CELL_KEY = 42L;

    @Test
    public void framedSubmitFlushAndReleasePreserveSingleThreadedSemantics() throws Exception {
        ProjectionClaimArbiter arbiter = new ProjectionClaimArbiter();
        UUID observerId = UUID.fromString("00000000-0000-0000-0000-000000000011");
        Player observer = player(observerId);
        World world = world();
        ILocalPortal portalA = portal(UUID.fromString("00000000-0000-0000-0000-000000000021"));

        assertTrue(arbiter.isIdle());
        arbiter.beginFrame(observer, world, false);
        assertFalse(arbiter.isIdle());
        ProjectionClaimArbiter.ClaimUpdateResult submitResult = arbiter.submit(observer, portalA, world, singleClaim(blockData("a")), 2.0D, false);
        assertEquals(0, submitResult.getBlockChanges());
        assertEquals(1, submitResult.getWinnerChanges());
        assertEquals(0, submitResult.getConflicts());

        ProjectionClaimArbiter.ClaimUpdateResult flushResult = arbiter.flushFrame(observer);
        assertEquals(0, flushResult.getBlockChanges());
        assertEquals(1, flushResult.getWinnerChanges());

        ProjectionClaimArbiter.ClaimUpdateResult releaseResult = arbiter.release(observer, portalA, world, false);
        assertEquals(1, releaseResult.getReverts());
        assertTrue(arbiter.isIdle());
        assertTrue(observersMap(arbiter).isEmpty());
    }

    @Test
    public void releaseObserverAfterSubmitEmptiesSubsequentCalls() throws Exception {
        ProjectionClaimArbiter arbiter = new ProjectionClaimArbiter();
        UUID observerId = UUID.fromString("00000000-0000-0000-0000-000000000012");
        Player observer = player(observerId);
        World world = world();
        ILocalPortal portalA = portal(UUID.fromString("00000000-0000-0000-0000-000000000022"));

        arbiter.beginFrame(observer, world, true);
        arbiter.submit(observer, portalA, world, singleClaim(blockData("a")), 2.0D, true);
        arbiter.releaseObserver(observerId);

        assertFalse(arbiter.hasPendingLighting(observer));
        ProjectionClaimArbiter.ClaimUpdateResult flushResult = arbiter.flushFrame(observer);
        assertEquals(0, flushResult.getBlockChanges());
        assertEquals(0, flushResult.getWinnerChanges());
        ProjectionClaimArbiter.ClaimUpdateResult releaseResult = arbiter.release(observer, portalA, world, false);
        assertEquals(0, releaseResult.getReverts());
        assertTrue(observersMap(arbiter).isEmpty());
    }

    @Test
    public void silentPortalReleasePreservesOtherPortalBookkeeping() throws Exception {
        ProjectionClaimArbiter arbiter = new ProjectionClaimArbiter();
        UUID observerId = UUID.fromString("00000000-0000-0000-0000-000000000014");
        Player observer = player(observerId);
        World world = world();
        ILocalPortal portalA = portal(UUID.fromString("00000000-0000-0000-0000-000000000024"));
        ILocalPortal portalB = portal(UUID.fromString("00000000-0000-0000-0000-000000000025"));
        long portalAKey = CELL_KEY;
        long portalBKey = CELL_KEY + 1L;
        BlockData portalAData = blockData("a");
        BlockData portalBData = blockData("b");

        arbiter.submit(observer, portalA, world, singleClaim(portalAKey, portalAData), 2.0D, false);
        arbiter.submit(observer, portalB, world, singleClaim(portalBKey, portalBData), 3.0D, false);

        Object observerState = observersMap(arbiter).get(observerId);
        Long2ObjectOpenHashMap<BlockData> sentBlocks = sentBlocks(observerState);
        sentBlocks.put(portalAKey, portalAData);
        sentBlocks.put(portalBKey, portalBData);
        LongOpenHashSet pendingLighting = pendingLighting(observerState);
        pendingLighting.add(portalAKey);
        pendingLighting.add(portalBKey);

        arbiter.releaseSilently(observerId, portalA.getId());

        assertFalse(sentBlocks.containsKey(portalAKey));
        assertTrue(sentBlocks.containsKey(portalBKey));
        assertTrue(pendingLighting.contains(portalBKey));
        assertEquals(1, arbiter.release(observer, portalB, world, false).getReverts());
    }

    @Test
    public void unresolvedLightingRevertKeepsObserverStateAlive() throws Exception {
        ProjectionWorldView unavailableView = unavailableLightView();
        ProjectionClaimArbiter arbiter = new ProjectionClaimArbiter(ignored -> unavailableView);
        UUID observerId = UUID.fromString("00000000-0000-0000-0000-000000000015");
        World world = world();
        AtomicReference<World> playerWorld = new AtomicReference<World>();
        AtomicBoolean online = new AtomicBoolean(true);
        Player observer = player(observerId, playerWorld, online);
        ILocalPortal portal = portal(UUID.fromString("00000000-0000-0000-0000-000000000026"));

        arbiter.submit(observer, portal, world, singleClaim(blockData("a")), 2.0D, false);
        Object observerState = observersMap(arbiter).get(observerId);
        ProjectorLighting lighting = lighting(observerState);
        Field field = ProjectorLighting.class.getDeclaredField("sentChunkSections");
        field.setAccessible(true);
        @SuppressWarnings("unchecked")
        Long2ObjectOpenHashMap<IntOpenHashSet> sent = (Long2ObjectOpenHashMap<IntOpenHashSet>) field.get(lighting);
        sent.put(7L, new IntOpenHashSet(new int[] { 2 }));

        playerWorld.set(world);
        arbiter.release(observer, portal, world, true);

        assertFalse(arbiter.isIdle());
        assertTrue(arbiter.hasPendingLighting(observer));

        online.set(false);
        arbiter.retryPending(observer, world);

        assertTrue(arbiter.isIdle());
    }

    @Test
    public void concurrentRandomOperationsNeverThrowAndDrainClean() throws Exception {
        ProjectionClaimArbiter arbiter = new ProjectionClaimArbiter();
        World world = world();
        UUID[] observerIds = new UUID[] {
            UUID.fromString("00000000-0000-0000-0000-000000000031"),
            UUID.fromString("00000000-0000-0000-0000-000000000032"),
            UUID.fromString("00000000-0000-0000-0000-000000000033"),
            UUID.fromString("00000000-0000-0000-0000-000000000034")
        };
        Player[] observerPlayers = new Player[observerIds.length];
        for (int i = 0; i < observerIds.length; i++) {
            observerPlayers[i] = player(observerIds[i]);
        }
        ILocalPortal portalA = portal(UUID.fromString("00000000-0000-0000-0000-000000000041"));
        ILocalPortal portalB = portal(UUID.fromString("00000000-0000-0000-0000-000000000042"));
        List<Throwable> failures = new ArrayList<Throwable>();
        AtomicInteger opsRun = new AtomicInteger();

        Thread[] threads = new Thread[8];
        for (int t = 0; t < threads.length; t++) {
            threads[t] = new Thread(() -> {
                try {
                    ThreadLocalRandom random = ThreadLocalRandom.current();
                    for (int i = 0; i < 1000; i++) {
                        int pick = random.nextInt(observerIds.length);
                        Player observer = observerPlayers[pick];
                        ILocalPortal portal = random.nextBoolean() ? portalA : portalB;
                        int op = random.nextInt(6);
                        if (op == 0) {
                            arbiter.beginFrame(observer, world, random.nextBoolean());
                        } else if (op == 1) {
                            arbiter.submit(observer, portal, world, singleClaim(blockData("x")), random.nextDouble(1.0D, 16.0D), random.nextBoolean());
                        } else if (op == 2) {
                            arbiter.flushFrame(observer);
                        } else if (op == 3) {
                            arbiter.release(observer, portal, world, random.nextBoolean());
                        } else if (op == 4) {
                            arbiter.releaseSilently(observerIds[pick], portal.getId());
                        } else {
                            arbiter.releaseObserver(observerIds[pick]);
                        }
                        opsRun.incrementAndGet();
                    }
                } catch (Throwable failure) {
                    synchronized (failures) {
                        failures.add(failure);
                    }
                }
            });
            threads[t].start();
        }
        for (Thread thread : threads) {
            thread.join(30_000L);
        }

        assertTrue(failures.isEmpty(), () -> "concurrent ops failed: " + failures);
        assertEquals(8_000, opsRun.get());
        for (UUID observerId : observerIds) {
            arbiter.releaseObserver(observerId);
        }
        assertTrue(observersMap(arbiter).isEmpty());
    }

    @Test
    public void submitVersusReleaseObserverRaceKeepsRevertsConsistent() throws Exception {
        ProjectionClaimArbiter arbiter = new ProjectionClaimArbiter();
        UUID observerId = UUID.fromString("00000000-0000-0000-0000-000000000013");
        Player observer = player(observerId);
        World world = world();
        ILocalPortal portalA = portal(UUID.fromString("00000000-0000-0000-0000-000000000023"));
        List<Throwable> failures = new ArrayList<Throwable>();

        Thread submitter = new Thread(() -> {
            try {
                for (int i = 0; i < 2000; i++) {
                    arbiter.submit(observer, portalA, world, singleClaim(blockData("s")), 2.0D, false);
                }
            } catch (Throwable failure) {
                synchronized (failures) {
                    failures.add(failure);
                }
            }
        });
        Thread releaser = new Thread(() -> {
            try {
                for (int i = 0; i < 2000; i++) {
                    arbiter.releaseObserver(observerId);
                }
            } catch (Throwable failure) {
                synchronized (failures) {
                    failures.add(failure);
                }
            }
        });
        submitter.start();
        releaser.start();
        submitter.join(30_000L);
        releaser.join(30_000L);
        assertTrue(failures.isEmpty(), () -> "racing ops failed: " + failures);

        arbiter.submit(observer, portalA, world, singleClaim(blockData("final")), 2.0D, false);
        ProjectionClaimArbiter.ClaimUpdateResult releaseResult = arbiter.release(observer, portalA, world, false);
        assertEquals(1, releaseResult.getReverts());
        assertTrue(observersMap(arbiter).isEmpty());
    }

    private static Map<?, ?> observersMap(ProjectionClaimArbiter arbiter) throws Exception {
        Field field = ProjectionClaimArbiter.class.getDeclaredField("observers");
        field.setAccessible(true);
        return (Map<?, ?>) field.get(arbiter);
    }

    @SuppressWarnings("unchecked")
    private static Long2ObjectOpenHashMap<BlockData> sentBlocks(Object observerState) throws Exception {
        Field field = observerState.getClass().getDeclaredField("sentBlocks");
        field.setAccessible(true);
        return (Long2ObjectOpenHashMap<BlockData>) field.get(observerState);
    }

    private static LongOpenHashSet pendingLighting(Object observerState) throws Exception {
        Field field = observerState.getClass().getDeclaredField("pendingLightingKeys");
        field.setAccessible(true);
        return (LongOpenHashSet) field.get(observerState);
    }

    private static ProjectorLighting lighting(Object observerState) throws Exception {
        Field field = observerState.getClass().getDeclaredField("lighting");
        field.setAccessible(true);
        return (ProjectorLighting) field.get(observerState);
    }

    private static Long2ObjectOpenHashMap<ProjectedBlockClaim> singleClaim(BlockData data) {
        return singleClaim(CELL_KEY, data);
    }

    private static Long2ObjectOpenHashMap<ProjectedBlockClaim> singleClaim(long key, BlockData data) {
        Long2ObjectOpenHashMap<ProjectedBlockClaim> claims = new Long2ObjectOpenHashMap<ProjectedBlockClaim>(1);
        claims.put(key, new ProjectedBlockClaim(data, null, ProjectedBlockClaim.NO_REMOTE_KEY, false));
        return claims;
    }

    private static Player player(UUID id) {
        InvocationHandler handler = (proxy, method, args) -> {
            String methodName = method.getName();
            if ("getUniqueId".equals(methodName)) {
                return id;
            }
            if ("isOnline".equals(methodName)) {
                return Boolean.TRUE;
            }
            if ("getWorld".equals(methodName)) {
                return null;
            }
            return defaultValue(proxy, method, args, "player-" + id);
        };
        return (Player) Proxy.newProxyInstance(Player.class.getClassLoader(), new Class<?>[] { Player.class }, handler);
    }

    private static Player player(UUID id, AtomicReference<World> playerWorld, AtomicBoolean online) {
        InvocationHandler handler = (proxy, method, args) -> {
            String methodName = method.getName();
            if ("getUniqueId".equals(methodName)) {
                return id;
            }
            if ("isOnline".equals(methodName)) {
                return Boolean.valueOf(online.get());
            }
            if ("getWorld".equals(methodName)) {
                return playerWorld.get();
            }
            return defaultValue(proxy, method, args, "player-" + id);
        };
        return (Player) Proxy.newProxyInstance(Player.class.getClassLoader(), new Class<?>[] { Player.class }, handler);
    }

    private static ProjectionWorldView unavailableLightView() {
        return new ProjectionWorldView() {
            @Override
            public World getWorld() {
                return null;
            }

            @Override
            public int getMinHeight() {
                return -64;
            }

            @Override
            public int getMaxHeight() {
                return 320;
            }

            @Override
            public BlockData sampleBlockData(int x, int y, int z) {
                return null;
            }

            @Override
            public String sampleBiome(int x, int y, int z) {
                return "minecraft:plains";
            }

            @Override
            public int getLight(int x, int y, int z) {
                return ProjectionWorldView.LIGHT_UNAVAILABLE;
            }

            @Override
            public int getSkyDarken() {
                return 0;
            }
        };
    }

    private static World world() {
        InvocationHandler handler = (proxy, method, args) -> defaultValue(proxy, method, args, "test-world");
        return (World) Proxy.newProxyInstance(World.class.getClassLoader(), new Class<?>[] { World.class }, handler);
    }

    private static ILocalPortal portal(UUID id) {
        InvocationHandler handler = (proxy, method, args) -> {
            if ("getId".equals(method.getName())) {
                return id;
            }
            return defaultValue(proxy, method, args, "portal-" + id);
        };
        return (ILocalPortal) Proxy.newProxyInstance(ILocalPortal.class.getClassLoader(), new Class<?>[] { ILocalPortal.class }, handler);
    }

    private static BlockData blockData(String name) {
        InvocationHandler handler = (proxy, method, args) -> defaultValue(proxy, method, args, name);
        return (BlockData) Proxy.newProxyInstance(BlockData.class.getClassLoader(), new Class<?>[] { BlockData.class }, handler);
    }

    private static Object defaultValue(Object proxy, Method method, Object[] args, String name) {
        String methodName = method.getName();
        if ("equals".equals(methodName)) {
            return proxy == args[0];
        }
        if ("hashCode".equals(methodName)) {
            return Integer.valueOf(System.identityHashCode(proxy));
        }
        if ("toString".equals(methodName)) {
            return name;
        }
        Class<?> returnType = method.getReturnType();
        if (returnType == Boolean.TYPE) {
            return Boolean.FALSE;
        }
        if (returnType == Integer.TYPE) {
            return Integer.valueOf(0);
        }
        if (returnType == Long.TYPE) {
            return Long.valueOf(0L);
        }
        if (returnType == Double.TYPE) {
            return Double.valueOf(0.0D);
        }
        if (returnType == Float.TYPE) {
            return Float.valueOf(0.0F);
        }
        if (returnType == Short.TYPE) {
            return Short.valueOf((short) 0);
        }
        if (returnType == Byte.TYPE) {
            return Byte.valueOf((byte) 0);
        }
        if (returnType == Character.TYPE) {
            return Character.valueOf('\0');
        }
        return null;
    }
}
