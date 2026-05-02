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
}
