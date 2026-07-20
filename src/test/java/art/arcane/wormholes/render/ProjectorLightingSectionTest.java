package art.arcane.wormholes.render;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;

import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.bukkit.World;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;

import com.github.retrooper.packetevents.protocol.world.chunk.LightData;

import art.arcane.wormholes.Settings;
import art.arcane.wormholes.render.view.ProjectionWorldView;

public final class ProjectorLightingSectionTest {
    @Test
    public void sectionFilterRejectsLightSectionsOutsideWorldBounds() {
        int minSection = -4;
        int maxSection = 19;

        assertFalse(ProjectorLighting.isSectionInsideWorld(-6, minSection, maxSection));
        assertFalse(ProjectorLighting.isSectionInsideWorld(-5, minSection, maxSection));
        assertTrue(ProjectorLighting.isSectionInsideWorld(-4, minSection, maxSection));
        assertTrue(ProjectorLighting.isSectionInsideWorld(19, minSection, maxSection));
        assertFalse(ProjectorLighting.isSectionInsideWorld(20, minSection, maxSection));
    }

    @Test
    public void validSectionCountIgnoresOutOfWorldSections() {
        int minSection = -4;
        int maxSection = 19;
        IntOpenHashSet dirtySections = new IntOpenHashSet();
        dirtySections.add(-6);
        dirtySections.add(-4);
        dirtySections.add(0);
        dirtySections.add(19);
        dirtySections.add(20);

        assertEquals(3, ProjectorLighting.countValidSections(dirtySections, minSection, maxSection));
    }

    @Test
    public void adaptiveLightingBudgetClampsToAtLeastOneSection() {
        assertEquals(1, ProjectorLighting.lightingSectionBudget(true, 0));
        assertEquals(2, ProjectorLighting.lightingSectionBudget(true, 2));
        assertEquals(Integer.MAX_VALUE, ProjectorLighting.lightingSectionBudget(false, 2));
    }

    @Test
    public void sectionSelectionLeavesUnsentSectionsPending() {
        IntOpenHashSet pending = new IntOpenHashSet();
        pending.add(1);
        pending.add(2);
        pending.add(3);

        IntOpenHashSet selected = ProjectorLighting.selectSections(pending, 2);
        ProjectorLighting.removeSections(pending, selected);

        assertEquals(2, selected.size());
        assertEquals(1, pending.size());
    }

    @Test
    public void validSectionsAreSerializedInAscendingMaskOrder() {
        IntOpenHashSet sections = new IntOpenHashSet();
        sections.add(7);
        sections.add(-2);
        sections.add(3);
        sections.add(30);

        int[] ordered = ProjectorLighting.sortedValidSections(sections, -4, 19);

        assertEquals(3, ordered.length);
        assertEquals(-2, ordered[0]);
        assertEquals(3, ordered[1]);
        assertEquals(7, ordered[2]);
    }

    @Test
    public void unsentLightingRemainsPendingWithoutSamplingAndSendsOnceAfterLoad() {
        AtomicBoolean chunkSent = new AtomicBoolean(false);
        AtomicInteger localSamples = new AtomicInteger();
        List<LightData> packets = new ArrayList<LightData>();
        ProjectorLighting lighting = new ProjectorLighting(
            (observer, chunkX, chunkZ) -> chunkSent.get(),
            (observer, chunkX, chunkZ, data) -> packets.add(data)
        );
        Player observer = onlinePlayer();
        ProjectionWorldView localView = lightView(localSamples, 15, 0);
        ProjectionWorldView remoteView = lightView(new AtomicInteger(), 9, 6);
        long localKey = packKey(1, 64, 1);
        Long2ObjectOpenHashMap<ProjectedBlockClaim> claims = new Long2ObjectOpenHashMap<ProjectedBlockClaim>();
        claims.put(localKey, new ProjectedBlockClaim(null, remoteView, packKey(20, 64, 20), false));
        LongOpenHashSet dirty = new LongOpenHashSet();
        dirty.add(localKey);

        lighting.apply(observer, localView, claims, dirty);

        assertEquals(0, localSamples.get());
        assertTrue(packets.isEmpty());
        assertTrue(lighting.hasPendingUpdates());

        chunkSent.set(true);
        lighting.apply(observer, localView, claims, new LongOpenHashSet());
        assertEquals(4096, localSamples.get());
        assertEquals(1, packets.size());
        assertFalse(lighting.hasPendingUpdates());

        lighting.apply(observer, localView, claims, new LongOpenHashSet());
        assertEquals(1, packets.size());
    }

    @Test
    public void removingOneSectionRestoresItWhileRetainingAnotherInTheSameChunk() {
        boolean adaptiveLighting = Settings.ADAPTIVE_LIGHTING;
        Settings.ADAPTIVE_LIGHTING = false;
        try {
            List<LightData> packets = new ArrayList<LightData>();
            ProjectorLighting lighting = new ProjectorLighting(
                (observer, chunkX, chunkZ) -> true,
                (observer, chunkX, chunkZ, data) -> packets.add(data)
            );
            Player observer = onlinePlayer();
            ProjectionWorldView localView = lightView(new AtomicInteger(), 15, 0);
            ProjectionWorldView remoteView = lightView(new AtomicInteger(), 8, 7);
            long sectionFour = packKey(1, 64, 1);
            long sectionFive = packKey(1, 80, 1);
            Long2ObjectOpenHashMap<ProjectedBlockClaim> claims = new Long2ObjectOpenHashMap<ProjectedBlockClaim>();
            claims.put(sectionFour, new ProjectedBlockClaim(null, remoteView, packKey(20, 64, 20), false));
            claims.put(sectionFive, new ProjectedBlockClaim(null, remoteView, packKey(20, 80, 20), false));
            LongOpenHashSet dirty = new LongOpenHashSet();
            dirty.add(sectionFour);
            dirty.add(sectionFive);

            lighting.apply(observer, localView, claims, dirty);

            assertEquals(1, packets.size());
            assertTrue(packets.get(0).getBlockLightMask().get(9));
            assertTrue(packets.get(0).getBlockLightMask().get(10));

            packets.clear();
            claims.remove(sectionFour);
            LongOpenHashSet removed = new LongOpenHashSet();
            removed.add(sectionFour);
            lighting.apply(observer, localView, claims, removed);

            assertEquals(1, packets.size());
            assertTrue(packets.get(0).getBlockLightMask().get(9));
            assertFalse(packets.get(0).getBlockLightMask().get(10));
            assertFalse(lighting.isIdle());

            lighting.revert(observer, localView);
            assertTrue(lighting.isIdle());
        } finally {
            Settings.ADAPTIVE_LIGHTING = adaptiveLighting;
        }
    }

    @Test
    public void sentLightingKeepsTheRendererNonIdleUntilReverted() throws Exception {
        ProjectorLighting lighting = new ProjectorLighting();
        assertTrue(lighting.isIdle());

        Field field = ProjectorLighting.class.getDeclaredField("sentChunkSections");
        field.setAccessible(true);
        @SuppressWarnings("unchecked")
        Long2ObjectOpenHashMap<IntOpenHashSet> sent = (Long2ObjectOpenHashMap<IntOpenHashSet>) field.get(lighting);
        sent.put(7L, new IntOpenHashSet(new int[] { 2 }));

        assertFalse(lighting.isIdle());
    }

    @Test
    public void pendingSectionsDrainWithoutFreshDirtyKeys() throws Exception {
        ProjectorLighting lighting = new ProjectorLighting();
        Field field = ProjectorLighting.class.getDeclaredField("pendingChunkSections");
        field.setAccessible(true);
        @SuppressWarnings("unchecked")
        Long2ObjectOpenHashMap<IntOpenHashSet> pending = (Long2ObjectOpenHashMap<IntOpenHashSet>) field.get(lighting);
        pending.put(7L, new IntOpenHashSet(new int[] { 2 }));
        Long2ObjectOpenHashMap<ProjectedBlockClaim> claims = new Long2ObjectOpenHashMap<ProjectedBlockClaim>();
        claims.put(0L, new ProjectedBlockClaim(null, null, ProjectedBlockClaim.NO_REMOTE_KEY, false));
        Player observer = (Player) Proxy.newProxyInstance(Player.class.getClassLoader(), new Class<?>[] { Player.class },
            (proxy, method, args) -> "isOnline".equals(method.getName()) ? Boolean.TRUE : null);
        ProjectionWorldView view = new ProjectionWorldView() {
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
            public org.bukkit.block.data.BlockData sampleBlockData(int x, int y, int z) {
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

        lighting.apply(observer, view, claims, new LongOpenHashSet());

        assertFalse(lighting.hasPendingUpdates());
    }

    private static Player onlinePlayer() {
        return (Player) Proxy.newProxyInstance(Player.class.getClassLoader(), new Class<?>[] { Player.class },
            (proxy, method, args) -> {
                if ("isOnline".equals(method.getName())) {
                    return Boolean.TRUE;
                }
                if (method.getReturnType() == Boolean.TYPE) {
                    return Boolean.FALSE;
                }
                return null;
            });
    }

    private static ProjectionWorldView lightView(AtomicInteger samples, int sky, int block) {
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
                samples.incrementAndGet();
                return ProjectionWorldView.packLight(sky, block);
            }

            @Override
            public int getSkyDarken() {
                return 0;
            }
        };
    }

    private static long packKey(int x, int y, int z) {
        return (((long) x & 0x3FFFFFFL) << 38)
            | ((((long) y) & 0xFFFL) << 26)
            | (((long) z) & 0x3FFFFFFL);
    }
}
