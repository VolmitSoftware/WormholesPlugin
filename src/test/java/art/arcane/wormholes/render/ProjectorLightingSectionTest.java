package art.arcane.wormholes.render;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;

import java.lang.reflect.Field;
import java.lang.reflect.Proxy;

import org.bukkit.World;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;

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
}
