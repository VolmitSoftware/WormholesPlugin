package art.arcane.wormholes.render;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;

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
    public void validSectionCountIgnoresOutOfWorldAndNullSections() {
        int minSection = -4;
        int maxSection = 19;
        HashSet<Integer> dirtySections = new HashSet<Integer>();
        dirtySections.add(Integer.valueOf(-6));
        dirtySections.add(Integer.valueOf(-4));
        dirtySections.add(Integer.valueOf(0));
        dirtySections.add(Integer.valueOf(19));
        dirtySections.add(Integer.valueOf(20));
        dirtySections.add(null);

        assertEquals(3, ProjectorLighting.countValidSections(dirtySections, minSection, maxSection));
    }
}
