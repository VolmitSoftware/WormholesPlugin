package art.arcane.wormholes.render;

import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Player;

import art.arcane.wormholes.Settings;
import art.arcane.wormholes.portal.ILocalPortal;
import art.arcane.wormholes.service.WormholesTelemetry;

public final class ProjectionClaimArbiter {
    private final Map<UUID, ObserverClaims> observers;
    private final Map<UUID, ObserverFrame> frames;

    public ProjectionClaimArbiter() {
        this.observers = new HashMap<UUID, ObserverClaims>();
        this.frames = new HashMap<UUID, ObserverFrame>();
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
            if (allowLightingUpdate) {
                frame.allowLightingUpdate = true;
            }
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
        observerClaims.sentBlocks.clear();
        removeObserverIfEmpty(observerId, observerClaims);
    }

    public synchronized void releaseObserver(UUID observerId) {
        ObserverClaims observerClaims = observers.remove(observerId);
        if (observerClaims == null) {
            return;
        }
        observerClaims.claimSet.clear();
        observerClaims.pendingLightingKeys.clear();
        observerClaims.sentBlocks.clear();
    }

    public synchronized void clear() {
        for (ObserverClaims observerClaims : observers.values()) {
            observerClaims.claimSet.clear();
            observerClaims.pendingLightingKeys.clear();
            observerClaims.sentBlocks.clear();
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

    private ClaimUpdateResult applyResult(Player observer,
                                          World localWorld,
                                          ObserverClaims observerClaims,
                                          ProjectionClaimSet.ProjectionClaimSetResult setResult,
                                          boolean allowLightingUpdate) {
        boolean canSend = observer != null && observer.isOnline() && localWorld != null && localWorld.equals(observer.getWorld());
        int expectedChanges = setResult.getPacketChangeKeys().size();
        int mapCapacity = expectedChanges <= 2 ? 4 : (expectedChanges * 4 / 3) + 2;
        Long2ObjectMap<BlockData> blockChanges = new Long2ObjectOpenHashMap<BlockData>(mapCapacity);
        if (canSend) {
            LongIterator packetIterator = setResult.getPacketChangeKeys().iterator();
            while (packetIterator.hasNext()) {
                long key = packetIterator.nextLong();
                ProjectedBlockClaim winner = observerClaims.claimSet.getWinningClaim(key);
                if (winner == null) {
                    int x = unpackX(key);
                    int y = unpackY(key);
                    int z = unpackZ(key);
                    if (!observerClaims.sentBlocks.containsKey(key)) {
                        continue;
                    }
                    Block localBlock = localWorld.getBlockAt(x, y, z);
                    BlockData localData = localBlock.getBlockData();
                    BlockData sentData = observerClaims.sentBlocks.get(key);
                    observerClaims.sentBlocks.remove(key);
                    if (sentData.equals(localData)) {
                        continue;
                    }
                    blockChanges.put(key, localData);
                } else {
                    BlockData sentData = observerClaims.sentBlocks.get(key);
                    BlockData winnerData = winner.getData();
                    if (sentData != null && sentData.equals(winnerData)) {
                        continue;
                    }
                    observerClaims.sentBlocks.put(key, winnerData);
                    blockChanges.put(key, winnerData);
                }
            }
            if (!blockChanges.isEmpty()) {
                for (Long2ObjectMap.Entry<BlockData> change : blockChanges.long2ObjectEntrySet()) {
                    long k = change.getLongKey();
                    observer.sendBlockChange(new Location(localWorld, unpackX(k), unpackY(k), unpackZ(k)), change.getValue());
                    WormholesTelemetry.countBlockChange();
                }
            }
        }

        observerClaims.pendingLightingKeys.addAll(setResult.getDirtyLightingKeys());
        applyLighting(observer, localWorld, observerClaims, canSend, allowLightingUpdate);

        return new ClaimUpdateResult(blockChanges.size(), setResult.getConflicts(),
            setResult.getWinnerChanges(), setResult.getReverts());
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
        private final Long2ObjectOpenHashMap<BlockData> sentBlocks;
        private final ProjectorLighting lighting;

        private ObserverClaims() {
            this.claimSet = new ProjectionClaimSet();
            this.pendingLightingKeys = new LongOpenHashSet();
            this.sentBlocks = new Long2ObjectOpenHashMap<BlockData>(256);
            this.lighting = new ProjectorLighting();
        }
    }

    private static final class ObserverFrame {
        private final Player observer;
        private final World localWorld;
        private final ProjectionClaimSet.ProjectionClaimSetResult result;
        private boolean allowLightingUpdate;

        private ObserverFrame(Player observer, World localWorld, boolean allowLightingUpdate) {
            this.observer = observer;
            this.localWorld = localWorld;
            this.allowLightingUpdate = allowLightingUpdate;
            this.result = new ProjectionClaimSet.ProjectionClaimSetResult();
        }
    }
}
