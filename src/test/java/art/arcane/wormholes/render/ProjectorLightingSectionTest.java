package art.arcane.wormholes.render;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import it.unimi.dsi.fastutil.ints.IntOpenHashSet;

import org.junit.jupiter.api.Test;

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
}
