package art.arcane.wormholes.render;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.protocol.world.chunk.LightData;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerUpdateLight;

import java.util.BitSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;

import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Player;

public final class ProjectorLighting {
    private static final int SECTION_NIBBLE_BYTES = 2048;

    private final HashSet<Long> sentChunkKeys = new HashSet<Long>();

    public void apply(Player observer,
                      World localWorld,
                      World destWorld,
                      Map<Long, BlockData> projected,
                      Map<Long, Long> projectedRemote) {
        if (observer == null || !observer.isOnline()) {
            return;
        }
        if (projected.isEmpty()) {
            return;
        }

        HashMap<Long, HashSet<Integer>> chunkToSections = new HashMap<Long, HashSet<Integer>>(8);
        for (Long key : projected.keySet()) {
            long packed = key.longValue();
            int worldX = unpackX(packed);
            int worldY = unpackY(packed);
            int worldZ = unpackZ(packed);
            long chunkKey = (((long) (worldX >> 4)) << 32) | (((long) (worldZ >> 4)) & 0xFFFFFFFFL);
            HashSet<Integer> set = chunkToSections.get(chunkKey);
            if (set == null) {
                set = new HashSet<Integer>(4);
                chunkToSections.put(chunkKey, set);
            }
            set.add(worldY >> 4);
        }

        HashSet<Long> currentChunkKeys = new HashSet<Long>(chunkToSections.keySet());
        Iterator<Long> staleIt = sentChunkKeys.iterator();
        while (staleIt.hasNext()) {
            Long key = staleIt.next();
            if (currentChunkKeys.contains(key)) {
                continue;
            }
            long packed = key.longValue();
            int chunkX = (int) (packed >> 32);
            int chunkZ = (int) packed;
            sendLocalChunkLight(observer, localWorld, chunkX, chunkZ);
            staleIt.remove();
        }

        for (Map.Entry<Long, HashSet<Integer>> entry : chunkToSections.entrySet()) {
            long chunkKey = entry.getKey().longValue();
            int chunkX = (int) (chunkKey >> 32);
            int chunkZ = (int) chunkKey;
            sendChunkLight(observer, localWorld, destWorld, projected, projectedRemote, chunkX, chunkZ, entry.getValue());
            sentChunkKeys.add(Long.valueOf(chunkKey));
        }
    }

    public void revert(Player observer, World localWorld) {
        if (sentChunkKeys.isEmpty()) {
            return;
        }
        if (observer == null || !observer.isOnline()) {
            sentChunkKeys.clear();
            return;
        }
        if (localWorld == null) {
            sentChunkKeys.clear();
            return;
        }

        for (Long key : sentChunkKeys) {
            long packed = key.longValue();
            int chunkX = (int) (packed >> 32);
            int chunkZ = (int) packed;
            sendLocalChunkLight(observer, localWorld, chunkX, chunkZ);
        }
        sentChunkKeys.clear();
    }

    private void sendChunkLight(Player observer,
                                World localWorld,
                                World destWorld,
                                Map<Long, BlockData> projected,
                                Map<Long, Long> projectedRemote,
                                int chunkX, int chunkZ, Set<Integer> dirtySections) {
        int minSec = localWorld.getMinHeight() >> 4;
        int maskBitCount = (localWorld.getMaxHeight() >> 4) - minSec + 2;

        BitSet blockMask = new BitSet(maskBitCount);
        BitSet skyMask = new BitSet(maskBitCount);
        BitSet emptyBlockMask = new BitSet(maskBitCount);
        BitSet emptySkyMask = new BitSet(maskBitCount);

        int sectionCount = dirtySections.size();
        byte[][] skyArrays = new byte[sectionCount][];
        byte[][] blockArrays = new byte[sectionCount][];

        int arrIdx = 0;
        for (Integer secObj : dirtySections) {
            int section = secObj.intValue();
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
                        BlockData proj = projected.get(Long.valueOf(localKey));

                        int sky;
                        int block;
                        if (proj != null) {
                            Long remoteKey = projectedRemote.get(Long.valueOf(localKey));
                            int rx = remoteKey == null ? x : unpackX(remoteKey.longValue());
                            int ry = remoteKey == null ? y : unpackY(remoteKey.longValue());
                            int rz = remoteKey == null ? z : unpackZ(remoteKey.longValue());
                            if (ry < destWorld.getMinHeight() || ry > destWorld.getMaxHeight() - 1) {
                                Block local = localWorld.getBlockAt(x, y, z);
                                sky = local.getLightFromSky();
                                block = local.getLightFromBlocks();
                            } else {
                                Block remote = destWorld.getBlockAt(rx, ry, rz);
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

    private void sendLocalChunkLight(Player observer, World localWorld, int chunkX, int chunkZ) {
        int minSec = localWorld.getMinHeight() >> 4;
        int maxSec = (localWorld.getMaxHeight() >> 4) - 1;
        int sectionTotal = maxSec - minSec + 1;
        int maskBitCount = sectionTotal + 2;

        BitSet blockMask = new BitSet(maskBitCount);
        BitSet skyMask = new BitSet(maskBitCount);
        BitSet emptyBlockMask = new BitSet(maskBitCount);
        BitSet emptySkyMask = new BitSet(maskBitCount);

        byte[][] skyArrays = new byte[sectionTotal][];
        byte[][] blockArrays = new byte[sectionTotal][];

        int arrIdx = 0;
        for (int section = minSec; section <= maxSec; section++) {
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
            sectionTotal, sectionTotal, skyArrays, blockArrays);
        WrapperPlayServerUpdateLight pkt = new WrapperPlayServerUpdateLight(chunkX, chunkZ, data);
        PacketEvents.getAPI().getPlayerManager().sendPacket(observer, pkt);
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
