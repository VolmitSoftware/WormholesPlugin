package art.arcane.wormholes.service;

import art.arcane.volmlib.util.director.compat.DirectorEngineFactory;
import art.arcane.volmlib.util.director.runtime.DirectorRuntimeEngine;
import art.arcane.volmlib.util.director.runtime.DirectorRuntimeNode;
import art.arcane.wormholes.commands.CommandWormholes;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WormholesCommandServiceTest {
    @Test
    void normalizeHelpArgsKeepsLegacyHelpSpellings() {
        assertArrayEquals(new String[]{"help=1"}, WormholesCommandService.normalizeHelpArgs(new String[]{"help"}));
        assertArrayEquals(new String[]{"help=2"}, WormholesCommandService.normalizeHelpArgs(new String[]{"?", "2"}));
        assertArrayEquals(new String[]{"rune", "help=3"}, WormholesCommandService.normalizeHelpArgs(new String[]{"rune", "help", "3"}));
        assertArrayEquals(new String[]{"rune", "portal"}, WormholesCommandService.normalizeHelpArgs(new String[]{"rune", "portal"}));
    }

    @Test
    void directorTreeIncludesRootAliasesAndFlatCommands() {
        DirectorRuntimeEngine engine = DirectorEngineFactory.create(new CommandWormholes(null));
        DirectorRuntimeNode root = engine.getRoot();

        assertTrue(root.getDescriptor().getAliases().containsAll(List.of("wh", "wormhole", "worm", "whole", "portal", "w")));
        assertNotNull(findChild(root, "wand"));
        assertNotNull(findChild(root, "rune"));
        assertNotNull(findChild(root, "reload"));
        assertNotNull(findChild(root, "reset"));
        assertNotNull(findChild(root, "debug"));
        assertNotNull(findChild(root, "info"));

        assertTrue(findChild(root, "rune").getDescriptor().getAliases().contains("runes"));
        assertTrue(findChild(root, "reload").getDescriptor().getAliases().contains("rl"));
        assertTrue(findChild(root, "info").getDescriptor().getAliases().containsAll(List.of("guide", "instructions")));
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
