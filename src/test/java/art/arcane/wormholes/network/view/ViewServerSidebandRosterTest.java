package art.arcane.wormholes.network.view;

import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class ViewServerSidebandRosterTest {
    @Test
    void sidebandRosterDropsEntityRotatedOutOfNearestSet() {
        UUID stillNear = UUID.randomUUID();
        UUID rotatedOut = UUID.randomUUID();
        UUID rotatedIn = UUID.randomUUID();
        Set<UUID> allPresent = Set.of(stillNear, rotatedOut, rotatedIn);
        Set<UUID> nearest = Set.of(stillNear, rotatedIn);

        Set<UUID> roster = ViewServer.presentIdsForPeer(true, allPresent, nearest);

        assertEquals(nearest, roster);
        assertFalse(roster.contains(rotatedOut));
    }

    @Test
    void rawRosterKeepsEveryPresentEntity() {
        Set<UUID> allPresent = Set.of(UUID.randomUUID(), UUID.randomUUID());

        assertEquals(allPresent, ViewServer.presentIdsForPeer(false, allPresent, Set.of()));
    }

    @Test
    void sidebandRosterIsEmptyWhenNoEntityIsAllowed() {
        Set<UUID> allPresent = Set.of(UUID.randomUUID());

        assertEquals(Set.of(), ViewServer.presentIdsForPeer(true, allPresent, null));
    }
}
