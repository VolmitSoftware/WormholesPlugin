package art.arcane.wormholes.render;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.protocol.world.chunk.LightData;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerUpdateLight;
import it.unimi.dsi.fastutil.ints.IntIterator;
import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import it.unimi.dsi.fastutil.ints.IntSet;
import it.unimi.dsi.fastutil.longs.Long2LongMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;

import java.util.BitSet;
import java.util.Iterator;

import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;

public final class ProjectorLighting {
    private static final int SECTION_NIBBLE_BYTES = 2048;
    private static final long NO_REMOTE_KEY = Long.MIN_VALUE;

    private final Long2ObjectOpenHashMap<IntOpenHashSet> sentChunkSections = new Long2ObjectOpenHashMap<IntOpenHashSet>(8);
    private final Long2ObjectOpenHashMap<IntOpenHashSet> chunkToSections = new Long2ObjectOpenHashMap<IntOpenHashSet>(8);

    public void apply(Player observer,
                      World localWorld,
                      Long2ObjectMap<ProjectedBlockClaim> projectedClaims,
                      LongSet dirtyLocalKeys) {
        if (observer == null || !observer.isOnline()) {
            return;
        }
        if (projectedClaims.isEmpty()) {
            return;
        }
        if (dirtyLocalKeys != null && dirtyLocalKeys.isEmpty()) {
            return;
        }

        LongSet currentChunks = collectCurrentChunkKeys(localWorld, projectedClaims.keySet());
        revertStaleChunks(observer, localWorld, currentChunks);

        chunkToSections.clear();
        LongSet dirtyKeys = dirtyLocalKeys == null ? projectedClaims.keySet() : dirtyLocalKeys;
        collectDirtySections(localWorld, dirtyKeys, chunkToSections);

        for (Long2ObjectMap.Entry<IntOpenHashSet> entry : chunkToSections.long2ObjectEntrySet()) {
            long chunkKey = entry.getLongKey();
            if (!currentChunks.contains(chunkKey)) {
                continue;
            }
            int chunkX = (int) (chunkKey >> 32);
            int chunkZ = (int) chunkKey;
            sendChunkLight(observer, localWorld, null, null, projectedClaims, chunkX, chunkZ, entry.getValue());
            recordSentSections(chunkKey, entry.getValue());
        }
    }

    public void apply(Player observer,
                      World localWorld,
                      World destWorld,
                      LongSet projectedKeys,
                      Long2LongMap projectedRemote,
                      LongSet dirtyLocalKeys) {
        apply(observer, localWorld, destWorld, projectedKeys, projectedRemote, dirtyLocalKeys, null);
    }

    public void apply(Player observer,
                      World localWorld,
                      World destWorld,
                      LongSet projectedKeys,
                      Long2LongMap projectedRemote,
                      LongSet dirtyLocalKeys,
                      LongSet currentChunkKeys) {
        if (observer == null || !observer.isOnline()) {
            return;
        }
        if (projectedKeys.isEmpty()) {
            return;
        }
        if (dirtyLocalKeys != null && dirtyLocalKeys.isEmpty()) {
            return;
        }

        projectedRemote.defaultReturnValue(NO_REMOTE_KEY);
        LongSet currentChunks = currentChunkKeys == null ? collectCurrentChunkKeys(localWorld, projectedKeys) : currentChunkKeys;
        revertStaleChunks(observer, localWorld, currentChunks);

        chunkToSections.clear();
        LongSet dirtyKeys = dirtyLocalKeys == null ? projectedKeys : dirtyLocalKeys;
        collectDirtySections(localWorld, dirtyKeys, chunkToSections);

        for (Long2ObjectMap.Entry<IntOpenHashSet> entry : chunkToSections.long2ObjectEntrySet()) {
            long chunkKey = entry.getLongKey();
            if (!currentChunks.contains(chunkKey)) {
                continue;
            }
            int chunkX = (int) (chunkKey >> 32);
            int chunkZ = (int) chunkKey;
            sendChunkLight(observer, localWorld, destWorld, projectedRemote, null, chunkX, chunkZ, entry.getValue());
            recordSentSections(chunkKey, entry.getValue());
        }
    }

    public void apply(Player observer,
                      World localWorld,
                      World destWorld,
                      LongSet projectedKeys,
                      Long2LongMap projectedRemote) {
        apply(observer, localWorld, destWorld, projectedKeys, projectedRemote, projectedKeys);
    }

    private LongOpenHashSet collectCurrentChunkKeys(World localWorld, LongSet projectedKeys) {
        LongOpenHashSet currentChunkKeys = new LongOpenHashSet(8);
        LongIterator iterator = projectedKeys.iterator();
        while (iterator.hasNext()) {
            long packed = iterator.nextLong();
            int worldX = unpackX(packed);
            int worldY = unpackY(packed);
            int worldZ = unpackZ(packed);
            if (!isWorldYInsideWorld(localWorld, worldY)) {
                continue;
            }
            long chunkKey = (((long) (worldX >> 4)) << 32) | (((long) (worldZ >> 4)) & 0xFFFFFFFFL);
            currentChunkKeys.add(chunkKey);
        }
        return currentChunkKeys;
    }

    private void collectDirtySections(World localWorld, LongSet dirtyKeys, Long2ObjectOpenHashMap<IntOpenHashSet> chunkToSections) {
        LongIterator iterator = dirtyKeys.iterator();
        while (iterator.hasNext()) {
            long packed = iterator.nextLong();
            int worldX = unpackX(packed);
            int worldY = unpackY(packed);
            int worldZ = unpackZ(packed);
            if (!isWorldYInsideWorld(localWorld, worldY)) {
                continue;
            }
            long chunkKey = (((long) (worldX >> 4)) << 32) | (((long) (worldZ >> 4)) & 0xFFFFFFFFL);
            IntOpenHashSet set = chunkToSections.get(chunkKey);
            if (set == null) {
                set = new IntOpenHashSet(4);
                chunkToSections.put(chunkKey, set);
            }
            set.add(worldY >> 4);
        }
    }

    private void revertStaleChunks(Player observer, World localWorld, LongSet currentChunkKeys) {
        Iterator<Long2ObjectMap.Entry<IntOpenHashSet>> staleIt = sentChunkSections.long2ObjectEntrySet().iterator();
        while (staleIt.hasNext()) {
            Long2ObjectMap.Entry<IntOpenHashSet> entry = staleIt.next();
            long key = entry.getLongKey();
            if (currentChunkKeys.contains(key)) {
                continue;
            }
            int chunkX = (int) (key >> 32);
            int chunkZ = (int) key;
            sendLocalChunkLight(observer, localWorld, chunkX, chunkZ, entry.getValue());
            staleIt.remove();
        }
    }

    public void revert(Player observer, World localWorld) {
        if (sentChunkSections.isEmpty()) {
            return;
        }
        if (observer == null || !observer.isOnline()) {
            sentChunkSections.clear();
            return;
        }
        if (localWorld == null) {
            sentChunkSections.clear();
            return;
        }

        for (Long2ObjectMap.Entry<IntOpenHashSet> entry : sentChunkSections.long2ObjectEntrySet()) {
            long key = entry.getLongKey();
            int chunkX = (int) (key >> 32);
            int chunkZ = (int) key;
            sendLocalChunkLight(observer, localWorld, chunkX, chunkZ, entry.getValue());
        }
        sentChunkSections.clear();
    }

    private void recordSentSections(long chunkKey, IntSet sections) {
        IntOpenHashSet sent = sentChunkSections.get(chunkKey);
        if (sent == null) {
            sent = new IntOpenHashSet(sections.size());
            sentChunkSections.put(chunkKey, sent);
        }
        sent.addAll(sections);
    }

    private void sendChunkLight(Player observer,
                                World localWorld,
                                World destWorld,
                                Long2LongMap projectedRemote,
                                Long2ObjectMap<ProjectedBlockClaim> projectedClaims,
                                int chunkX, int chunkZ, IntSet dirtySections) {
        int minSec = localWorld.getMinHeight() >> 4;
        int maxSec = (localWorld.getMaxHeight() - 1) >> 4;
        int destMinY = destWorld == null ? 0 : destWorld.getMinHeight();
        int destMaxY = destWorld == null ? -1 : destWorld.getMaxHeight() - 1;
        int maskBitCount = (maxSec - minSec + 1) + 2;
        int sectionCount = countValidSections(dirtySections, minSec, maxSec);
        if (sectionCount == 0) {
            return;
        }

        BitSet blockMask = new BitSet(maskBitCount);
        BitSet skyMask = new BitSet(maskBitCount);
        BitSet emptyBlockMask = new BitSet(maskBitCount);
        BitSet emptySkyMask = new BitSet(maskBitCount);

        byte[][] skyArrays = new byte[sectionCount][];
        byte[][] blockArrays = new byte[sectionCount][];

        int arrIdx = 0;
        IntIterator sectionIterator = dirtySections.iterator();
        while (sectionIterator.hasNext()) {
            int section = sectionIterator.nextInt();
            if (!isSectionInsideWorld(section, minSec, maxSec)) {
                continue;
            }
            int sectionMinY = section << 4;
            int maskIndex = (section - minSec) + 1;

            byte[] skyArr = new byte[SECTION_NIBBLE_BYTES];
            byte[] blockArr = new byte[SECTION_NIBBLE_BYTES];

            for (int ly = 0; ly < 16; ly++) {
                int y = sectionMinY + ly;
                for (int lz = 0; lz < 16; lz++) {
                    int z = (chunkZ << 4) + lz;
                    for (int lx = 0; lx < 16; lx++) {
                        int x = (chunkX << 4) + lx;

                        long localKey = packKey(x, y, z);
                        ProjectedBlockClaim claim = projectedClaims == null ? null : projectedClaims.get(localKey);
                        long remoteKey = claim == null && projectedRemote != null ? projectedRemote.get(localKey) : claim == null ? NO_REMOTE_KEY : claim.getLightRemoteKey();
                        World sourceWorld = claim == null ? destWorld : claim.getLightWorld();

                        int sky;
                        int block;
                        if (remoteKey != NO_REMOTE_KEY && sourceWorld != null) {
                            int rx = unpackX(remoteKey);
                            int ry = unpackY(remoteKey);
                            int rz = unpackZ(remoteKey);
                            int sourceMinY = claim == null ? destMinY : sourceWorld.getMinHeight();
                            int sourceMaxY = claim == null ? destMaxY : sourceWorld.getMaxHeight() - 1;
                            if (ry < sourceMinY || ry > sourceMaxY) {
                                Block local = localWorld.getBlockAt(x, y, z);
                                sky = local.getLightFromSky();
                                block = local.getLightFromBlocks();
                            } else {
                                Block remote = sourceWorld.getBlockAt(rx, ry, rz);
                                sky = remote.getLightFromSky();
                                block = remote.getLightFromBlocks();
                            }
                        } else {
                            Block local = localWorld.getBlockAt(x, y, z);
                            sky = local.getLightFromSky();
                            block = local.getLightFromBlocks();
                        }

                        int nibbleIdx = (ly << 8) | (lz << 4) | lx;
                        int byteIdx = nibbleIdx >> 1;
                        if ((nibbleIdx & 1) == 0) {
                            skyArr[byteIdx] = (byte) ((skyArr[byteIdx] & 0xF0) | (sky & 0x0F));
                            blockArr[byteIdx] = (byte) ((blockArr[byteIdx] & 0xF0) | (block & 0x0F));
                        } else {
                            skyArr[byteIdx] = (byte) ((skyArr[byteIdx] & 0x0F) | ((sky & 0x0F) << 4));
                            blockArr[byteIdx] = (byte) ((blockArr[byteIdx] & 0x0F) | ((block & 0x0F) << 4));
                        }
                    }
                }
            }

            skyArrays[arrIdx] = skyArr;
            blockArrays[arrIdx] = blockArr;
            blockMask.set(maskIndex);
            skyMask.set(maskIndex);
            arrIdx++;
        }

        LightData data = new LightData(true, blockMask, skyMask, emptyBlockMask, emptySkyMask,
            sectionCount, sectionCount, skyArrays, blockArrays);
        WrapperPlayServerUpdateLight pkt = new WrapperPlayServerUpdateLight(chunkX, chunkZ, data);
        PacketEvents.getAPI().getPlayerManager().sendPacket(observer, pkt);
    }

    static int countValidSections(IntSet dirtySections, int minSection, int maxSection) {
        int count = 0;
        IntIterator iterator = dirtySections.iterator();
        while (iterator.hasNext()) {
            int section = iterator.nextInt();
            if (isSectionInsideWorld(section, minSection, maxSection)) {
                count++;
            }
        }
        return count;
    }

    static boolean isSectionInsideWorld(int section, int minSection, int maxSection) {
        return section >= minSection && section <= maxSection;
    }

    private void sendLocalChunkLight(Player observer, World localWorld, int chunkX, int chunkZ, IntSet sections) {
        int minSec = localWorld.getMinHeight() >> 4;
        int maxSec = (localWorld.getMaxHeight() - 1) >> 4;
        int maskBitCount = (maxSec - minSec + 1) + 2;
        int sectionCount = countValidSections(sections, minSec, maxSec);
        if (sectionCount == 0) {
            return;
        }

        BitSet blockMask = new BitSet(maskBitCount);
        BitSet skyMask = new BitSet(maskBitCount);
        BitSet emptyBlockMask = new BitSet(maskBitCount);
        BitSet emptySkyMask = new BitSet(maskBitCount);

        byte[][] skyArrays = new byte[sectionCount][];
        byte[][] blockArrays = new byte[sectionCount][];

        int arrIdx = 0;
        IntIterator sectionIterator = sections.iterator();
        while (sectionIterator.hasNext()) {
            int section = sectionIterator.nextInt();
            if (!isSectionInsideWorld(section, minSec, maxSec)) {
                continue;
            }
            int sectionMinY = section << 4;
            int maskIndex = (section - minSec) + 1;

            byte[] skyArr = new byte[SECTION_NIBBLE_BYTES];
            byte[] blockArr = new byte[SECTION_NIBBLE_BYTES];

            for (int ly = 0; ly < 16; ly++) {
                int y = sectionMinY + ly;
                for (int lz = 0; lz < 16; lz++) {
                    int z = (chunkZ << 4) + lz;
                    for (int lx = 0; lx < 16; lx++) {
                        int x = (chunkX << 4) + lx;
                        Block local = localWorld.getBlockAt(x, y, z);
                        int sky = local.getLightFromSky();
                        int block = local.getLightFromBlocks();

                        int nibbleIdx = (ly << 8) | (lz << 4) | lx;
                        int byteIdx = nibbleIdx >> 1;
                        if ((nibbleIdx & 1) == 0) {
                            skyArr[byteIdx] = (byte) ((skyArr[byteIdx] & 0xF0) | (sky & 0x0F));
                            blockArr[byteIdx] = (byte) ((blockArr[byteIdx] & 0xF0) | (block & 0x0F));
                        } else {
                            skyArr[byteIdx] = (byte) ((skyArr[byteIdx] & 0x0F) | ((sky & 0x0F) << 4));
                            blockArr[byteIdx] = (byte) ((blockArr[byteIdx] & 0x0F) | ((block & 0x0F) << 4));
                        }
                    }
                }
            }

            skyArrays[arrIdx] = skyArr;
            blockArrays[arrIdx] = blockArr;
            blockMask.set(maskIndex);
            skyMask.set(maskIndex);
            arrIdx++;
        }

        LightData data = new LightData(true, blockMask, skyMask, emptyBlockMask, emptySkyMask,
            sectionCount, sectionCount, skyArrays, blockArrays);
        WrapperPlayServerUpdateLight pkt = new WrapperPlayServerUpdateLight(chunkX, chunkZ, data);
        PacketEvents.getAPI().getPlayerManager().sendPacket(observer, pkt);
    }

    private static boolean isWorldYInsideWorld(World world, int y) {
        return y >= world.getMinHeight() && y < world.getMaxHeight();
    }

    private static long packKey(int x, int y, int z) {
        return (((long) x & 0x3FFFFFFL) << 38) | ((((long) y) & 0xFFFL) << 26) | (((long) z) & 0x3FFFFFFL);
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
}
