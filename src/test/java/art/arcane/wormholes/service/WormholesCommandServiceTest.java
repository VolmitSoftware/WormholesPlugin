package art.arcane.wormholes.service;

import art.arcane.volmlib.util.director.compat.DirectorEngineFactory;
import art.arcane.volmlib.util.director.runtime.DirectorRuntimeEngine;
import art.arcane.volmlib.util.director.runtime.DirectorRuntimeNode;
import art.arcane.wormholes.commands.CommandWormholes;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class WormholesCommandServiceTest {
    @Test
    void normalizeHelpArgsKeepsLegacyHelpSpellings() {
        assertArrayEquals(new String[]{"help=1"}, WormholesCommandService.normalizeHelpArgs(new String[]{"help"}));
        assertArrayEquals(new String[]{"help=2"}, WormholesCommandService.normalizeHelpArgs(new String[]{"?", "2"}));
        assertArrayEquals(new String[]{"wand", "help=3"}, WormholesCommandService.normalizeHelpArgs(new String[]{"wand", "help", "3"}));
        assertArrayEquals(new String[]{"wand", "rune=portal"}, WormholesCommandService.normalizeHelpArgs(new String[]{"wand", "rune=portal"}));
    }

    @Test
    void directorTreeIncludesRootAliasAndFlatCommands() {
        DirectorRuntimeEngine engine = DirectorEngineFactory.create(new CommandWormholes(null));
        DirectorRuntimeNode root = engine.getRoot();

        assertEquals(List.of("wh"), List.copyOf(root.getDescriptor().getAliases()));
        assertNotNull(findChild(root, "wand"));
        assertNotNull(findChild(root, "reload"));
        assertNotNull(findChild(root, "info"));
        assertNull(findChild(root, "rune"));
        assertNull(findChild(root, "reset"));
        assertNull(findChild(root, "debug"));
    }

    private DirectorRuntimeNode findChild(DirectorRuntimeNode root, String name) {
        for (DirectorRuntimeNode child : root.getChildren()) {
            if (child.getDescriptor().getName().equals(name)) {
                return child;
            }
        }

        return null;
    }
}
