package art.arcane.wormholes.render;

import io.papermc.paper.math.Position;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Player;

import art.arcane.wormholes.Settings;
import art.arcane.wormholes.portal.ILocalPortal;

public final class ProjectionClaimArbiter {
    private final Map<UUID, ObserverClaims> observers;
    private final Map<UUID, ObserverFrame> frames;
    private int lastConflicts;
    private int lastWinnerChanges;
    private int lastReverts;
    private int lastBlockChanges;

    public ProjectionClaimArbiter() {
        this.observers = new HashMap<UUID, ObserverClaims>();
        this.frames = new HashMap<UUID, ObserverFrame>();
        this.lastConflicts = 0;
        this.lastWinnerChanges = 0;
        this.lastReverts = 0;
        this.lastBlockChanges = 0;
    }

    public synchronized void beginFrame(Player observer, World localWorld, boolean allowLightingUpdate) {
        if (observer == null || !observer.isOnline() || localWorld == null) {
            return;
        }
        frames.put(observer.getUniqueId(), new ObserverFrame(observer, localWorld, allowLightingUpdate));
    }

    public synchronized ClaimUpdateResult flushFrame(Player observer) {
        if (observer == null) {
            return ClaimUpdateResult.empty();
        }
        ObserverFrame frame = frames.remove(observer.getUniqueId());
        if (frame == null) {
            return ClaimUpdateResult.empty();
        }
        ObserverClaims observerClaims = observers.get(observer.getUniqueId());
        if (observerClaims == null) {
            return ClaimUpdateResult.empty();
        }
        ClaimUpdateResult result = applyResult(frame.observer, frame.localWorld, observerClaims, frame.result, frame.allowLightingUpdate);
        removeObserverIfEmpty(observer.getUniqueId(), observerClaims);
        return result;
    }

    public synchronized ClaimUpdateResult submit(Player observer,
                                                 ILocalPortal portal,
                                                 World localWorld,
                                                 Long2ObjectMap<ProjectedBlockClaim> claims,
                                                 double priorityDistance,
                                                 boolean allowLightingUpdate) {
        if (observer == null || portal == null || portal.getId() == null) {
            return ClaimUpdateResult.empty();
        }
        ObserverClaims observerClaims = observers.computeIfAbsent(observer.getUniqueId(), ignored -> new ObserverClaims());
        String tieKey = portal.getId().toString();
        ProjectionClaimSet.ProjectionClaimSetResult setResult = observerClaims.claimSet.replacePortalClaims(
            portal.getId(), tieKey, priorityDistance, claims);
        ObserverFrame frame = frames.get(observer.getUniqueId());
        if (frame != null) {
            frame.result.merge(setResult);
            return new ClaimUpdateResult(0, setResult.getConflicts(), setResult.getWinnerChanges(), setResult.getReverts());
        }
        return applyResult(observer, localWorld, observerClaims, setResult, allowLightingUpdate);
    }

    public synchronized ClaimUpdateResult release(Player observer, ILocalPortal portal, World localWorld, boolean allowLightingUpdate) {
        if (observer == null || portal == null || portal.getId() == null) {
            return ClaimUpdateResult.empty();
        }
        ObserverClaims observerClaims = observers.get(observer.getUniqueId());
        if (observerClaims == null) {
            return ClaimUpdateResult.empty();
        }
        ProjectionClaimSet.ProjectionClaimSetResult setResult = observerClaims.claimSet.releasePortal(portal.getId());
        ClaimUpdateResult result = applyResult(observer, localWorld, observerClaims, setResult, allowLightingUpdate);
        removeObserverIfEmpty(observer.getUniqueId(), observerClaims);
        return result;
    }

    public synchronized void releaseSilently(UUID observerId, UUID portalId) {
        ObserverClaims observerClaims = observers.get(observerId);
        if (observerClaims == null) {
            return;
        }
        observerClaims.claimSet.releasePortal(portalId);
        observerClaims.pendingLightingKeys.clear();
        removeObserverIfEmpty(observerId, observerClaims);
    }

    public synchronized void releaseObserver(UUID observerId) {
        ObserverClaims observerClaims = observers.remove(observerId);
        if (observerClaims == null) {
            return;
        }
        observerClaims.claimSet.clear();
        observerClaims.pendingLightingKeys.clear();
    }

    public synchronized void clear() {
        for (ObserverClaims observerClaims : observers.values()) {
            observerClaims.claimSet.clear();
            observerClaims.pendingLightingKeys.clear();
        }
        observers.clear();
        frames.clear();
    }

    public synchronized boolean hasPendingLighting(Player observer) {
        if (observer == null) {
            return false;
        }
        ObserverClaims observerClaims = observers.get(observer.getUniqueId());
        return observerClaims != null && !observerClaims.pendingLightingKeys.isEmpty();
    }

    public synchronized String getDiagnostics() {
        int observerCount = observers.size();
        int claimedCells = 0;
        int portalClaims = 0;
        for (ObserverClaims observerClaims : observers.values()) {
            claimedCells += observerClaims.claimSet.getClaimedCellCount();
            portalClaims += observerClaims.claimSet.getPortalClaimCount();
        }
        return "projectionClaims observers=" + observerCount
            + " portals=" + portalClaims
            + " cells=" + claimedCells
            + " conflicts=" + lastConflicts
            + " winnerChanges=" + lastWinnerChanges
            + " reverts=" + lastReverts
            + " blockChanges=" + lastBlockChanges;
    }

    private ClaimUpdateResult applyResult(Player observer,
                                          World localWorld,
                                          ObserverClaims observerClaims,
                                          ProjectionClaimSet.ProjectionClaimSetResult setResult,
                                          boolean allowLightingUpdate) {
        boolean canSend = observer != null && observer.isOnline() && localWorld != null && localWorld.equals(observer.getWorld());
        Map<Position, BlockData> blockChanges = new HashMap<Position, BlockData>(setResult.getPacketChangeKeys().size());
        if (canSend) {
            LongIterator packetIterator = setResult.getPacketChangeKeys().iterator();
            while (packetIterator.hasNext()) {
                long key = packetIterator.nextLong();
                ProjectedBlockClaim winner = observerClaims.claimSet.getWinningClaim(key);
                if (winner == null) {
                    int x = unpackX(key);
                    int y = unpackY(key);
                    int z = unpackZ(key);
                    Block localBlock = localWorld.getBlockAt(x, y, z);
                    blockChanges.put(Position.block(x, y, z), localBlock.getBlockData());
                } else {
                    blockChanges.put(Position.block(unpackX(key), unpackY(key), unpackZ(key)), winner.getData());
                }
            }
            if (!blockChanges.isEmpty()) {
                observer.sendMultiBlockChange(blockChanges);
            }
        }

        observerClaims.pendingLightingKeys.addAll(setResult.getDirtyLightingKeys());
        applyLighting(observer, localWorld, observerClaims, canSend, allowLightingUpdate);

        ClaimUpdateResult result = new ClaimUpdateResult(blockChanges.size(), setResult.getConflicts(),
            setResult.getWinnerChanges(), setResult.getReverts());
        lastConflicts = result.getConflicts();
        lastWinnerChanges = result.getWinnerChanges();
        lastReverts = result.getReverts();
        lastBlockChanges = result.getBlockChanges();
        return result;
    }

    private void applyLighting(Player observer, World localWorld, ObserverClaims observerClaims, boolean canSend, boolean allowLightingUpdate) {
        if (!canSend) {
            observerClaims.pendingLightingKeys.clear();
            return;
        }
        if (!Settings.LIGHTING_FIDELITY) {
            observerClaims.lighting.revert(observer, localWorld);
            observerClaims.pendingLightingKeys.clear();
            return;
        }
        if (observerClaims.claimSet.isEmpty()) {
            observerClaims.lighting.revert(observer, localWorld);
            observerClaims.pendingLightingKeys.clear();
            return;
        }
        if (!allowLightingUpdate || observerClaims.pendingLightingKeys.isEmpty()) {
            return;
        }
        observerClaims.lighting.apply(observer, localWorld, observerClaims.claimSet.getWinningClaims(),
            observerClaims.pendingLightingKeys);
        observerClaims.pendingLightingKeys.clear();
    }

    private void removeObserverIfEmpty(UUID observerId, ObserverClaims observerClaims) {
        if (observerClaims.claimSet.isEmpty()) {
            observers.remove(observerId);
        }
    }

    private static int unpackX(long key) {
        long raw = (key >> 38) & 0x3FFFFFFL;
        return (int) ((raw << 38) >> 38);
    }

    private static int unpackY(long key) {
        long raw = (key >> 26) & 0xFFFL;
        return (int) ((raw << 52) >> 52);
    }

    private static int unpackZ(long key) {
        long raw = key & 0x3FFFFFFL;
        return (int) ((raw << 38) >> 38);
    }

    public static final class ClaimUpdateResult {
        private static final ClaimUpdateResult EMPTY = new ClaimUpdateResult(0, 0, 0, 0);

        private final int blockChanges;
        private final int conflicts;
        private final int winnerChanges;
        private final int reverts;

        private ClaimUpdateResult(int blockChanges, int conflicts, int winnerChanges, int reverts) {
            this.blockChanges = blockChanges;
            this.conflicts = conflicts;
            this.winnerChanges = winnerChanges;
            this.reverts = reverts;
        }

        static ClaimUpdateResult empty() {
            return EMPTY;
        }

        public int getBlockChanges() {
            return blockChanges;
        }

        public int getConflicts() {
            return conflicts;
        }

        public int getWinnerChanges() {
            return winnerChanges;
        }

        public int getReverts() {
            return reverts;
        }
    }

    private static final class ObserverClaims {
        private final ProjectionClaimSet claimSet;
        private final LongOpenHashSet pendingLightingKeys;
        private final ProjectorLighting lighting;

        private ObserverClaims() {
            this.claimSet = new ProjectionClaimSet();
            this.pendingLightingKeys = new LongOpenHashSet();
            this.lighting = new ProjectorLighting();
        }
    }

    private static final class ObserverFrame {
        private final Player observer;
        private final World localWorld;
        private final boolean allowLightingUpdate;
        private final ProjectionClaimSet.ProjectionClaimSetResult result;

        private ObserverFrame(Player observer, World localWorld, boolean allowLightingUpdate) {
            this.observer = observer;
            this.localWorld = localWorld;
            this.allowLightingUpdate = allowLightingUpdate;
            this.result = new ProjectionClaimSet.ProjectionClaimSetResult();
        }
    }
}
