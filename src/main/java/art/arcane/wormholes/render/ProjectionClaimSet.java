package art.arcane.wormholes.render;

import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;

final class ProjectionClaimSet {
    private static final double PRIORITY_EPSILON = 1.0E-7D;

    private final Map<UUID, PortalClaims> portals;
    private final Long2ObjectOpenHashMap<WinningClaim> winners;
    private final Long2ObjectOpenHashMap<ProjectedBlockClaim> winningClaims;
    private final Long2IntOpenHashMap claimCounts;
    private final Long2ObjectOpenHashMap<PortalClaims> singleOwners;

    ProjectionClaimSet() {
        this.portals = new HashMap<UUID, PortalClaims>();
        this.winners = new Long2ObjectOpenHashMap<WinningClaim>(256);
        this.winningClaims = new Long2ObjectOpenHashMap<ProjectedBlockClaim>(256);
        this.claimCounts = new Long2IntOpenHashMap(256);
        this.singleOwners = new Long2ObjectOpenHashMap<PortalClaims>(256);
    }

    ProjectionClaimSetResult replacePortalClaims(UUID portalId,
                                                 String tieKey,
                                                 double priorityDistance,
                                                 Long2ObjectMap<ProjectedBlockClaim> claims) {
        PortalClaims existing = portals.get(portalId);
        if (claims == null || claims.isEmpty()) {
            return releasePortal(portalId);
        }

        LongArrayList affected = new LongArrayList();
        PortalClaims portalClaims = existing == null ? new PortalClaims(portalId) : existing;
        boolean priorityChanged = existing != null && hasPriorityChanged(existing, tieKey, priorityDistance);

        if (existing != null) {
            Iterator<Long2ObjectMap.Entry<ProjectedBlockClaim>> existingIterator = existing.claims.long2ObjectEntrySet().iterator();
            while (existingIterator.hasNext()) {
                Long2ObjectMap.Entry<ProjectedBlockClaim> entry = existingIterator.next();
                long key = entry.getLongKey();
                if (claims.containsKey(key)) {
                    continue;
                }
                decrementClaimCount(key, portalClaims);
                affected.add(key);
                existingIterator.remove();
            }
        }

        for (Long2ObjectMap.Entry<ProjectedBlockClaim> entry : claims.long2ObjectEntrySet()) {
            long key = entry.getLongKey();
            ProjectedBlockClaim nextClaim = entry.getValue();
            ProjectedBlockClaim previousClaim = portalClaims.claims.get(key);
            if (previousClaim == null) {
                incrementClaimCount(key, portalClaims);
                portalClaims.claims.put(key, nextClaim);
                affected.add(key);
            } else if (!sameClaim(previousClaim, nextClaim)) {
                portalClaims.claims.put(key, nextClaim);
                affected.add(key);
            } else if (priorityChanged && claimCounts.get(key) > 1) {
                affected.add(key);
            }
        }

        portalClaims.tieKey = tieKey;
        portalClaims.priorityDistance = priorityDistance;
        portals.put(portalId, portalClaims);
        return recomputeAffected(affected);
    }

    ProjectionClaimSetResult releasePortal(UUID portalId) {
        PortalClaims existing = portals.remove(portalId);
        if (existing == null) {
            return new ProjectionClaimSetResult();
        }
        LongIterator existingIterator = existing.claims.keySet().iterator();
        LongArrayList affected = new LongArrayList(existing.claims.size());
        while (existingIterator.hasNext()) {
            long key = existingIterator.nextLong();
            affected.add(key);
            decrementClaimCount(key, existing);
        }
        existing.claims.clear();
        return recomputeAffected(affected);
    }

    void clear() {
        portals.clear();
        winners.clear();
        winningClaims.clear();
        claimCounts.clear();
        singleOwners.clear();
    }

    boolean isEmpty() {
        return winners.isEmpty();
    }

    Long2ObjectOpenHashMap<ProjectedBlockClaim> getWinningClaims() {
        return winningClaims;
    }

    ProjectedBlockClaim getWinningClaim(long key) {
        return winningClaims.get(key);
    }

    private ProjectionClaimSetResult recomputeAffected(LongArrayList affected) {
        ProjectionClaimSetResult result = new ProjectionClaimSetResult(affected.size());
        LongIterator iterator = affected.iterator();
        while (iterator.hasNext()) {
            long key = iterator.nextLong();
            WinningClaim previous = winners.get(key);
            WinnerChoice choice = chooseWinner(key);
            if (choice.claimCount > 1) {
                result.conflicts++;
            }
            WinningClaim next = choice.winner;
            if (next == null) {
                if (previous != null) {
                    winners.remove(key);
                    winningClaims.remove(key);
                    result.packetChangeKeys.add(key);
                    result.dirtyLightingKeys.add(key);
                    result.reverts++;
                }
                continue;
            }

            boolean ownerChanged = previous == null || !previous.portalId.equals(next.portalId);
            boolean dataChanged = previous == null || !previous.claim.getData().equals(next.claim.getData());
            boolean lightChanged = previous == null || !previous.claim.sameLightSource(next.claim);
            winners.put(key, next);
            winningClaims.put(key, next.claim);
            if (dataChanged) {
                result.packetChangeKeys.add(key);
            }
            if (ownerChanged) {
                result.winnerChanges++;
            }
            if (lightChanged) {
                result.dirtyLightingKeys.add(key);
            }
        }
        return result;
    }

    private WinnerChoice chooseWinner(long key) {
        int storedClaimCount = claimCounts.get(key);
        if (storedClaimCount <= 0) {
            return new WinnerChoice(null, 0);
        }
        if (storedClaimCount == 1) {
            PortalClaims singleOwner = singleOwners.get(key);
            if (singleOwner != null) {
                ProjectedBlockClaim claim = singleOwner.claims.get(key);
                if (claim != null) {
                    return new WinnerChoice(new WinningClaim(singleOwner.portalId, singleOwner.tieKey, singleOwner.priorityDistance, claim), 1);
                }
            }
        }

        WinningClaim best = null;
        int claimCount = 0;
        Iterator<PortalClaims> iterator = portals.values().iterator();
        while (iterator.hasNext()) {
            PortalClaims portalClaims = iterator.next();
            ProjectedBlockClaim claim = portalClaims.claims.get(key);
            if (claim == null) {
                continue;
            }
            claimCount++;
            if (best == null || isHigherPriority(portalClaims.priorityDistance, portalClaims.tieKey, claim, best.priorityDistance, best.tieKey, best.claim)) {
                best = new WinningClaim(portalClaims.portalId, portalClaims.tieKey, portalClaims.priorityDistance, claim);
            }
        }
        return new WinnerChoice(best, claimCount);
    }

    static boolean isHigherPriority(double candidateDistance, String candidateTieKey, double currentDistance, String currentTieKey) {
        return isHigherPriority(candidateDistance, candidateTieKey, null, currentDistance, currentTieKey, null);
    }

    static boolean isHigherPriority(double candidateDistance,
                                    String candidateTieKey,
                                    ProjectedBlockClaim candidateClaim,
                                    double currentDistance,
                                    String currentTieKey,
                                    ProjectedBlockClaim currentClaim) {
        if (candidateClaim != null && currentClaim != null) {
            if (candidateClaim.isMaskAir() && !currentClaim.isMaskAir()) {
                return false;
            }
            if (!candidateClaim.isMaskAir() && currentClaim.isMaskAir()) {
                return true;
            }
        }
        if (candidateDistance < currentDistance - PRIORITY_EPSILON) {
            return true;
        }
        if (candidateDistance > currentDistance + PRIORITY_EPSILON) {
            return false;
        }
        return candidateTieKey.compareTo(currentTieKey) < 0;
    }

    private static boolean hasPriorityChanged(PortalClaims existing, String tieKey, double priorityDistance) {
        if (!existing.tieKey.equals(tieKey)) {
            return true;
        }
        return Math.abs(existing.priorityDistance - priorityDistance) > PRIORITY_EPSILON;
    }

    private static boolean sameClaim(ProjectedBlockClaim previous, ProjectedBlockClaim next) {
        if (previous == next) {
            return true;
        }
        if (previous == null || next == null) {
            return false;
        }
        return previous.isMaskAir() == next.isMaskAir()
            && previous.getData().equals(next.getData())
            && previous.sameLightSource(next);
    }

    private void incrementClaimCount(long key, PortalClaims owner) {
        int count = claimCounts.get(key);
        if (count == 0) {
            claimCounts.put(key, 1);
            singleOwners.put(key, owner);
            return;
        }
        if (count == 1) {
            singleOwners.remove(key);
        }
        claimCounts.put(key, count + 1);
    }

    private void decrementClaimCount(long key, PortalClaims removedOwner) {
        int count = claimCounts.get(key);
        if (count <= 1) {
            claimCounts.remove(key);
            singleOwners.remove(key);
            return;
        }
        if (count == 2) {
            claimCounts.put(key, 1);
            PortalClaims remainingOwner = findRemainingOwner(key, removedOwner);
            if (remainingOwner == null) {
                singleOwners.remove(key);
            } else {
                singleOwners.put(key, remainingOwner);
            }
            return;
        }
        claimCounts.put(key, count - 1);
    }

    private PortalClaims findRemainingOwner(long key, PortalClaims removedOwner) {
        Iterator<PortalClaims> iterator = portals.values().iterator();
        while (iterator.hasNext()) {
            PortalClaims portalClaims = iterator.next();
            if (portalClaims == removedOwner) {
                continue;
            }
            if (portalClaims.claims.containsKey(key)) {
                return portalClaims;
            }
        }
        return null;
    }

    static final class ProjectionClaimSetResult {
        private final LongArrayList packetChangeKeys;
        private final LongArrayList dirtyLightingKeys;
        private LongOpenHashSet mergedPacketChangeKeys;
        private LongOpenHashSet mergedDirtyLightingKeys;
        private int conflicts;
        private int winnerChanges;
        private int reverts;

        ProjectionClaimSetResult() {
            this(0);
        }

        ProjectionClaimSetResult(int expectedKeys) {
            this.packetChangeKeys = new LongArrayList(expectedKeys);
            this.dirtyLightingKeys = new LongArrayList(expectedKeys);
            this.mergedPacketChangeKeys = null;
            this.mergedDirtyLightingKeys = null;
            this.conflicts = 0;
            this.winnerChanges = 0;
            this.reverts = 0;
        }

        LongArrayList getPacketChangeKeys() {
            return packetChangeKeys;
        }

        LongArrayList getDirtyLightingKeys() {
            return dirtyLightingKeys;
        }

        int getConflicts() {
            return conflicts;
        }

        int getWinnerChanges() {
            return winnerChanges;
        }

        int getReverts() {
            return reverts;
        }

        boolean isEmpty() {
            return packetChangeKeys.isEmpty() && dirtyLightingKeys.isEmpty() && conflicts == 0 && winnerChanges == 0 && reverts == 0;
        }

        void merge(ProjectionClaimSetResult other) {
            if (other == null || other.isEmpty()) {
                return;
            }
            appendUniquePacketKeys(other.packetChangeKeys);
            appendUniqueLightingKeys(other.dirtyLightingKeys);
            conflicts += other.conflicts;
            winnerChanges += other.winnerChanges;
            reverts += other.reverts;
        }

        private void appendUniquePacketKeys(LongArrayList keys) {
            if (keys.isEmpty()) {
                return;
            }
            if (mergedPacketChangeKeys == null) {
                mergedPacketChangeKeys = new LongOpenHashSet(packetChangeKeys);
            }
            LongIterator iterator = keys.iterator();
            while (iterator.hasNext()) {
                long key = iterator.nextLong();
                if (!mergedPacketChangeKeys.add(key)) {
                    continue;
                }
                packetChangeKeys.add(key);
            }
        }

        private void appendUniqueLightingKeys(LongArrayList keys) {
            if (keys.isEmpty()) {
                return;
            }
            if (mergedDirtyLightingKeys == null) {
                mergedDirtyLightingKeys = new LongOpenHashSet(dirtyLightingKeys);
            }
            LongIterator iterator = keys.iterator();
            while (iterator.hasNext()) {
                long key = iterator.nextLong();
                if (!mergedDirtyLightingKeys.add(key)) {
                    continue;
                }
                dirtyLightingKeys.add(key);
            }
        }
    }

    private static final class PortalClaims {
        private final UUID portalId;
        private final Long2ObjectOpenHashMap<ProjectedBlockClaim> claims;
        private String tieKey;
        private double priorityDistance;

        private PortalClaims(UUID portalId) {
            this.portalId = portalId;
            this.claims = new Long2ObjectOpenHashMap<ProjectedBlockClaim>(256);
            this.tieKey = portalId.toString();
            this.priorityDistance = 0.0D;
        }
    }

    private static final class WinnerChoice {
        private final WinningClaim winner;
        private final int claimCount;

        private WinnerChoice(WinningClaim winner, int claimCount) {
            this.winner = winner;
            this.claimCount = claimCount;
        }
    }

    private static final class WinningClaim {
        private final UUID portalId;
        private final String tieKey;
        private final double priorityDistance;
        private final ProjectedBlockClaim claim;

        private WinningClaim(UUID portalId, String tieKey, double priorityDistance, ProjectedBlockClaim claim) {
            this.portalId = portalId;
            this.tieKey = tieKey;
            this.priorityDistance = priorityDistance;
            this.claim = claim;
        }
    }
}
