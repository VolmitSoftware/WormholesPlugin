package art.arcane.wormholes.render;

import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.ToIntFunction;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Player;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.util.Vector3i;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerMultiBlockChange;

import io.github.retrooper.packetevents.util.SpigotConversionUtil;

import art.arcane.wormholes.Settings;
import art.arcane.wormholes.Wormholes;
import art.arcane.wormholes.portal.ILocalPortal;
import art.arcane.wormholes.render.view.ProjectionWorldView;
import art.arcane.wormholes.render.view.ProjectionWorldViewProvider;
import art.arcane.wormholes.service.WormholesTelemetry;

public final class ProjectionClaimArbiter {
    private final ConcurrentHashMap<UUID, ObserverClaims> observers;
    private final ConcurrentHashMap<BlockData, Integer> blockGlobalIds;
    private final ProjectionWorldViewProvider viewProvider;
    private volatile boolean blockMappingFailed;

    public ProjectionClaimArbiter() {
        this(ProjectionWorldViewProvider.live());
    }

    public ProjectionClaimArbiter(ProjectionWorldViewProvider viewProvider) {
        this.observers = new ConcurrentHashMap<UUID, ObserverClaims>();
        this.blockGlobalIds = new ConcurrentHashMap<BlockData, Integer>();
        this.viewProvider = viewProvider;
        this.blockMappingFailed = false;
    }

    public void beginFrame(Player observer, World localWorld, boolean allowLightingUpdate) {
        if (observer == null || !observer.isOnline() || localWorld == null) {
            return;
        }
        UUID observerId = observer.getUniqueId();
        while (true) {
            ObserverClaims state = observers.computeIfAbsent(observerId, ignored -> new ObserverClaims());
            synchronized (state) {
                if (state.retired) {
                    continue;
                }
                state.frame = new ObserverFrame(observer, localWorld, allowLightingUpdate);
                return;
            }
        }
    }

    public ClaimUpdateResult flushFrame(Player observer) {
        if (observer == null) {
            return ClaimUpdateResult.empty();
        }
        UUID observerId = observer.getUniqueId();
        ObserverClaims state = observers.get(observerId);
        if (state == null) {
            return ClaimUpdateResult.empty();
        }
        synchronized (state) {
            if (state.retired) {
                return ClaimUpdateResult.empty();
            }
            ObserverFrame frame = state.frame;
            state.frame = null;
            if (frame == null) {
                return ClaimUpdateResult.empty();
            }
            ClaimUpdateResult result = applyResult(frame.observer, frame.localWorld, state, frame.result, frame.allowLightingUpdate);
            removeObserverIfEmpty(observerId, state);
            return result;
        }
    }

    public ClaimUpdateResult submit(Player observer,
                                    ILocalPortal portal,
                                    World localWorld,
                                    Long2ObjectMap<ProjectedBlockClaim> claims,
                                    double priorityDistance,
                                    boolean allowLightingUpdate) {
        if (observer == null || portal == null || portal.getId() == null) {
            return ClaimUpdateResult.empty();
        }
        UUID observerId = observer.getUniqueId();
        while (true) {
            ObserverClaims state = observers.computeIfAbsent(observerId, ignored -> new ObserverClaims());
            synchronized (state) {
                if (state.retired) {
                    continue;
                }
                String tieKey = portal.getId().toString();
                ProjectionClaimSet.ProjectionClaimSetResult setResult = state.claimSet.replacePortalClaims(
                    portal.getId(), tieKey, priorityDistance, claims);
                ObserverFrame frame = state.frame;
                if (frame != null) {
                    if (allowLightingUpdate) {
                        frame.allowLightingUpdate = true;
                    }
                    frame.result.merge(setResult);
                    return new ClaimUpdateResult(0, setResult.getConflicts(), setResult.getWinnerChanges(), setResult.getReverts());
                }
                return applyResult(observer, localWorld, state, setResult, allowLightingUpdate);
            }
        }
    }

    public ClaimUpdateResult release(Player observer, ILocalPortal portal, World localWorld, boolean allowLightingUpdate) {
        if (observer == null || portal == null || portal.getId() == null) {
            return ClaimUpdateResult.empty();
        }
        UUID observerId = observer.getUniqueId();
        ObserverClaims state = observers.get(observerId);
        if (state == null) {
            return ClaimUpdateResult.empty();
        }
        synchronized (state) {
            if (state.retired) {
                return ClaimUpdateResult.empty();
            }
            ProjectionClaimSet.ProjectionClaimSetResult setResult = state.claimSet.releasePortal(portal.getId());
            ClaimUpdateResult result = applyResult(observer, localWorld, state, setResult, allowLightingUpdate);
            removeObserverIfEmpty(observerId, state);
            return result;
        }
    }

    public void releaseSilently(UUID observerId, UUID portalId) {
        ObserverClaims state = observers.get(observerId);
        if (state == null) {
            return;
        }
        synchronized (state) {
            if (state.retired) {
                return;
            }
            ProjectionClaimSet.ProjectionClaimSetResult releaseResult = state.claimSet.releasePortal(portalId);
            LongIterator changedKeys = releaseResult.getPacketChangeKeys().iterator();
            while (changedKeys.hasNext()) {
                long key = changedKeys.nextLong();
                state.sentBlocks.remove(key);
                state.pendingRevertKeys.remove(key);
            }
            removeObserverIfEmpty(observerId, state);
        }
    }

    public void releaseObserver(UUID observerId) {
        ObserverClaims state = observers.get(observerId);
        if (state == null) {
            return;
        }
        synchronized (state) {
            if (state.retired) {
                return;
            }
            state.claimSet.clear();
            state.pendingLightingKeys.clear();
            state.pendingRevertKeys.clear();
            state.sentBlocks.clear();
            state.frame = null;
            state.retired = true;
            observers.remove(observerId, state);
        }
    }

    public void clear() {
        for (Map.Entry<UUID, ObserverClaims> entry : observers.entrySet()) {
            ObserverClaims state = entry.getValue();
            synchronized (state) {
                state.retired = true;
                state.claimSet.clear();
                state.pendingLightingKeys.clear();
                state.pendingRevertKeys.clear();
                state.sentBlocks.clear();
                state.frame = null;
            }
            observers.remove(entry.getKey(), state);
        }
    }

    public boolean hasPendingLighting(Player observer) {
        if (observer == null) {
            return false;
        }
        ObserverClaims state = observers.get(observer.getUniqueId());
        if (state == null) {
            return false;
        }
        synchronized (state) {
            if (state.retired) {
                return false;
            }
            return !state.pendingLightingKeys.isEmpty();
        }
    }

    public ClaimUpdateResult retryPending(Player observer, World localWorld) {
        if (observer == null) {
            return ClaimUpdateResult.empty();
        }
        UUID observerId = observer.getUniqueId();
        ObserverClaims state = observers.get(observerId);
        if (state == null) {
            return ClaimUpdateResult.empty();
        }
        synchronized (state) {
            if (state.retired || state.pendingRevertKeys.isEmpty()) {
                return ClaimUpdateResult.empty();
            }
            ClaimUpdateResult result = applyResult(observer, localWorld, state,
                new ProjectionClaimSet.ProjectionClaimSetResult(), false);
            removeObserverIfEmpty(observerId, state);
            return result;
        }
    }

    static void groupBySection(Long2ObjectMap<BlockData> blockChanges,
                               ToIntFunction<BlockData> idResolver,
                               Long2ObjectMap<List<WrapperPlayServerMultiBlockChange.EncodedBlock>> sectionsOut,
                               Long2ObjectMap<BlockData> fallbackOut) {
        for (Long2ObjectMap.Entry<BlockData> change : blockChanges.long2ObjectEntrySet()) {
            long key = change.getLongKey();
            BlockData data = change.getValue();
            int x = unpackX(key);
            int y = unpackY(key);
            int z = unpackZ(key);
            int id = idResolver.applyAsInt(data);
            if (id < 0 || (id == 0 && !ProjectionWorldView.isAir(data.getMaterial()))) {
                fallbackOut.put(key, data);
                continue;
            }
            long sectionKey = packSectionKey(x >> 4, y >> 4, z >> 4);
            List<WrapperPlayServerMultiBlockChange.EncodedBlock> entries = sectionsOut.get(sectionKey);
            if (entries == null) {
                entries = new ArrayList<WrapperPlayServerMultiBlockChange.EncodedBlock>(8);
                sectionsOut.put(sectionKey, entries);
            }
            entries.add(new WrapperPlayServerMultiBlockChange.EncodedBlock(id, x, y, z));
        }
    }

    static int unpackSectionX(long key) {
        long raw = (key >> 38) & 0x3FFFFFFL;
        return (int) ((raw << 38) >> 38);
    }

    static int unpackSectionY(long key) {
        long raw = (key >> 26) & 0xFFFL;
        return (int) ((raw << 52) >> 52);
    }

    static int unpackSectionZ(long key) {
        long raw = key & 0x3FFFFFFL;
        return (int) ((raw << 38) >> 38);
    }

    private ClaimUpdateResult applyResult(Player observer,
                                          World localWorld,
                                          ObserverClaims observerClaims,
                                          ProjectionClaimSet.ProjectionClaimSetResult setResult,
                                          boolean allowLightingUpdate) {
        boolean canSend = observer != null && observer.isOnline() && localWorld != null && localWorld.equals(observer.getWorld());
        LongOpenHashSet packetKeys = new LongOpenHashSet(setResult.getPacketChangeKeys());
        packetKeys.addAll(observerClaims.pendingRevertKeys);
        observerClaims.pendingRevertKeys.clear();
        int expectedChanges = packetKeys.size();
        int mapCapacity = expectedChanges <= 2 ? 4 : (expectedChanges * 4 / 3) + 2;
        Long2ObjectMap<BlockData> blockChanges = new Long2ObjectOpenHashMap<BlockData>(mapCapacity);
        if (canSend) {
            ProjectionWorldView localView = viewProvider.view(localWorld);
            LongIterator packetIterator = packetKeys.iterator();
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
                    BlockData localData = localView == null ? null : localView.sampleBlockData(x, y, z);
                    if (localData == null) {
                        observerClaims.pendingRevertKeys.add(key);
                        continue;
                    }
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
                sendBlockChanges(observer, localWorld, blockChanges);
            }
        } else {
            observerClaims.sentBlocks.clear();
            observerClaims.pendingRevertKeys.clear();
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
            observerClaims.lighting.revert(observer, viewProvider.view(localWorld));
            observerClaims.pendingLightingKeys.clear();
            return;
        }
        if (observerClaims.claimSet.isEmpty()) {
            observerClaims.lighting.revert(observer, viewProvider.view(localWorld));
            observerClaims.pendingLightingKeys.clear();
            return;
        }
        if (!allowLightingUpdate || observerClaims.pendingLightingKeys.isEmpty()) {
            return;
        }
        ProjectionWorldView localView = viewProvider.view(localWorld);
        if (localView == null) {
            return;
        }
        observerClaims.lighting.apply(observer, localView, observerClaims.claimSet.getWinningClaims(),
            observerClaims.pendingLightingKeys);
        observerClaims.pendingLightingKeys.clear();
    }

    private void sendBlockChanges(Player observer, World localWorld, Long2ObjectMap<BlockData> blockChanges) {
        if (blockChanges.size() == 1 || blockMappingFailed) {
            for (Long2ObjectMap.Entry<BlockData> change : blockChanges.long2ObjectEntrySet()) {
                long key = change.getLongKey();
                observer.sendBlockChange(new Location(localWorld, unpackX(key), unpackY(key), unpackZ(key)), change.getValue());
                WormholesTelemetry.countBlockChange();
            }
            return;
        }
        Long2ObjectOpenHashMap<List<WrapperPlayServerMultiBlockChange.EncodedBlock>> sections =
            new Long2ObjectOpenHashMap<List<WrapperPlayServerMultiBlockChange.EncodedBlock>>(8);
        Long2ObjectOpenHashMap<BlockData> fallback = new Long2ObjectOpenHashMap<BlockData>(4);
        groupBySection(blockChanges, this::resolveGlobalId, sections, fallback);
        for (Long2ObjectMap.Entry<List<WrapperPlayServerMultiBlockChange.EncodedBlock>> entry : sections.long2ObjectEntrySet()) {
            long sectionKey = entry.getLongKey();
            List<WrapperPlayServerMultiBlockChange.EncodedBlock> entries = entry.getValue();
            WrapperPlayServerMultiBlockChange.EncodedBlock[] blocks =
                entries.toArray(new WrapperPlayServerMultiBlockChange.EncodedBlock[entries.size()]);
            Vector3i sectionPos = new Vector3i(unpackSectionX(sectionKey), unpackSectionY(sectionKey), unpackSectionZ(sectionKey));
            WrapperPlayServerMultiBlockChange wrapper = new WrapperPlayServerMultiBlockChange(sectionPos, null, blocks);
            PacketEvents.getAPI().getPlayerManager().sendPacket(observer, wrapper);
            for (int i = 0; i < blocks.length; i++) {
                WormholesTelemetry.countBlockChange();
            }
        }
        for (Long2ObjectMap.Entry<BlockData> change : fallback.long2ObjectEntrySet()) {
            long key = change.getLongKey();
            observer.sendBlockChange(new Location(localWorld, unpackX(key), unpackY(key), unpackZ(key)), change.getValue());
            WormholesTelemetry.countBlockChange();
        }
    }

    private int resolveGlobalId(BlockData data) {
        Integer cached = blockGlobalIds.get(data);
        if (cached != null) {
            return cached.intValue();
        }
        int id;
        try {
            id = SpigotConversionUtil.fromBukkitBlockData(data).getGlobalId();
        } catch (RuntimeException ex) {
            blockMappingFailed = true;
            Wormholes.w("[ClaimArbiter] block state mapping failed for " + data.getAsString() + ", falling back to bukkit block updates");
            ex.printStackTrace();
            return -1;
        }
        blockGlobalIds.put(data, Integer.valueOf(id));
        return id;
    }

    private void removeObserverIfEmpty(UUID observerId, ObserverClaims state) {
        if (state.claimSet.isEmpty() && state.frame == null && state.pendingRevertKeys.isEmpty() && state.sentBlocks.isEmpty()) {
            state.retired = true;
            observers.remove(observerId, state);
        }
    }

    private static long packSectionKey(int sectionX, int sectionY, int sectionZ) {
        return (((long) sectionX & 0x3FFFFFFL) << 38) | ((((long) sectionY) & 0xFFFL) << 26) | (((long) sectionZ) & 0x3FFFFFFL);
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
        private final LongOpenHashSet pendingRevertKeys;
        private final Long2ObjectOpenHashMap<BlockData> sentBlocks;
        private final ProjectorLighting lighting;
        private ObserverFrame frame;
        private boolean retired;

        private ObserverClaims() {
            this.claimSet = new ProjectionClaimSet();
            this.pendingLightingKeys = new LongOpenHashSet();
            this.pendingRevertKeys = new LongOpenHashSet();
            this.sentBlocks = new Long2ObjectOpenHashMap<BlockData>(256);
            this.lighting = new ProjectorLighting();
            this.frame = null;
            this.retired = false;
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
