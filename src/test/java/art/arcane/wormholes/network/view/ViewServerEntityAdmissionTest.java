package art.arcane.wormholes.network.view;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ViewServerEntityAdmissionTest {
    @Test
    void selectionIsIndependentOfConcurrentArrivalOrder() {
        UUID nearestMob = new UUID(0L, 1L);
        UUID fartherMob = new UUID(0L, 2L);
        UUID nearestPlayer = new UUID(0L, 3L);
        UUID fartherPlayer = new UUID(0L, 4L);
        UUID closestMob = new UUID(0L, 5L);
        List<ViewServer.EntityRank> candidates = List.of(
            new ViewServer.EntityRank(nearestMob, false, 1.0D),
            new ViewServer.EntityRank(fartherMob, false, 2.0D),
            new ViewServer.EntityRank(nearestPlayer, true, 100.0D),
            new ViewServer.EntityRank(fartherPlayer, true, 200.0D),
            new ViewServer.EntityRank(closestMob, false, 0.5D)
        );
        Set<UUID> expected = Set.of(nearestPlayer, fartherPlayer, closestMob);

        for (int offset = 0; offset < candidates.size(); offset++) {
            List<ViewServer.EntityRank> order = new ArrayList<>(candidates);
            Collections.rotate(order, offset);
            assertEquals(expected, admitted(order, 3));
            Collections.reverse(order);
            assertEquals(expected, admitted(order, 3));
        }
    }

    @Test
    void stableUuidTieBreakSelectsTheSameEntity() {
        UUID first = new UUID(0L, 1L);
        UUID second = new UUID(0L, 2L);
        List<ViewServer.EntityRank> candidates = List.of(
            new ViewServer.EntityRank(second, false, 4.0D),
            new ViewServer.EntityRank(first, false, 4.0D)
        );

        assertEquals(Set.of(first), admitted(candidates, 1));
        assertEquals(Set.of(first), admitted(candidates.reversed(), 1));
    }

    private static Set<UUID> admitted(List<ViewServer.EntityRank> candidates, int limit) {
        ViewServer.EntityAdmission<UUID> admission = new ViewServer.EntityAdmission<>(limit);
        for (ViewServer.EntityRank candidate : candidates) {
            admission.admit(candidate, candidate.id());
        }
        Set<UUID> admitted = admission.admittedIds();
        assertEquals(admitted, Set.copyOf(admission.selectedEntities()));
        return admitted;
    }
}
