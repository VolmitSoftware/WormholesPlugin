package art.arcane.wormholes.render;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.function.ToIntFunction;

import org.bukkit.Material;
import org.bukkit.block.data.BlockData;
import org.junit.jupiter.api.Test;

import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerMultiBlockChange;

public final class ProjectionBlockSectionBatchTest {
    @Test
    public void blocksSpanningThreeSectionsGroupIntoThreeSectionKeys() {
        Long2ObjectOpenHashMap<BlockData> changes = new Long2ObjectOpenHashMap<BlockData>(4);
        changes.put(packKey(1, 65, 2), blockData(Material.STONE));
        changes.put(packKey(2, 66, 3), blockData(Material.STONE));
        changes.put(packKey(18, 65, 2), blockData(Material.STONE));
        changes.put(packKey(1, 82, 2), blockData(Material.STONE));
        Long2ObjectOpenHashMap<List<WrapperPlayServerMultiBlockChange.EncodedBlock>> sections =
            new Long2ObjectOpenHashMap<List<WrapperPlayServerMultiBlockChange.EncodedBlock>>(4);
        Long2ObjectOpenHashMap<BlockData> fallback = new Long2ObjectOpenHashMap<BlockData>(4);
        ToIntFunction<BlockData> resolver = ignored -> 42;

        ProjectionClaimArbiter.groupBySection(changes, resolver, sections, fallback);

        assertEquals(3, sections.size());
        assertTrue(fallback.isEmpty());
        int totalEntries = 0;
        boolean sawBaseSection = false;
        for (Long2ObjectMap.Entry<List<WrapperPlayServerMultiBlockChange.EncodedBlock>> entry : sections.long2ObjectEntrySet()) {
            long sectionKey = entry.getLongKey();
            totalEntries += entry.getValue().size();
            int sectionX = ProjectionClaimArbiter.unpackSectionX(sectionKey);
            int sectionY = ProjectionClaimArbiter.unpackSectionY(sectionKey);
            int sectionZ = ProjectionClaimArbiter.unpackSectionZ(sectionKey);
            if (sectionX == 0 && sectionY == 4 && sectionZ == 0) {
                sawBaseSection = true;
                assertEquals(2, entry.getValue().size());
            } else {
                assertTrue((sectionX == 1 && sectionY == 4 && sectionZ == 0)
                    || (sectionX == 0 && sectionY == 5 && sectionZ == 0));
            }
        }
        assertEquals(4, totalEntries);
        assertTrue(sawBaseSection);
    }

    @Test
    public void negativeCoordsAndNegativeSectionYRoundTrip() {
        Long2ObjectOpenHashMap<BlockData> changes = new Long2ObjectOpenHashMap<BlockData>(1);
        changes.put(packKey(-5, -30, -20), blockData(Material.STONE));
        Long2ObjectOpenHashMap<List<WrapperPlayServerMultiBlockChange.EncodedBlock>> sections =
            new Long2ObjectOpenHashMap<List<WrapperPlayServerMultiBlockChange.EncodedBlock>>(1);
        Long2ObjectOpenHashMap<BlockData> fallback = new Long2ObjectOpenHashMap<BlockData>(1);

        ProjectionClaimArbiter.groupBySection(changes, ignored -> 9, sections, fallback);

        assertEquals(1, sections.size());
        long sectionKey = sections.long2ObjectEntrySet().iterator().next().getLongKey();
        assertEquals(-1, ProjectionClaimArbiter.unpackSectionX(sectionKey));
        assertEquals(-2, ProjectionClaimArbiter.unpackSectionY(sectionKey));
        assertEquals(-2, ProjectionClaimArbiter.unpackSectionZ(sectionKey));
    }

    @Test
    public void encodedBlockPreservesGlobalCoordsAndPacksInSectionBits() {
        Long2ObjectOpenHashMap<BlockData> changes = new Long2ObjectOpenHashMap<BlockData>(1);
        changes.put(packKey(-5, -30, -20), blockData(Material.STONE));
        Long2ObjectOpenHashMap<List<WrapperPlayServerMultiBlockChange.EncodedBlock>> sections =
            new Long2ObjectOpenHashMap<List<WrapperPlayServerMultiBlockChange.EncodedBlock>>(1);
        Long2ObjectOpenHashMap<BlockData> fallback = new Long2ObjectOpenHashMap<BlockData>(1);

        ProjectionClaimArbiter.groupBySection(changes, ignored -> 7, sections, fallback);

        WrapperPlayServerMultiBlockChange.EncodedBlock encoded = sections.long2ObjectEntrySet().iterator().next().getValue().get(0);
        assertEquals(-5, encoded.getX());
        assertEquals(-30, encoded.getY());
        assertEquals(-20, encoded.getZ());
        long expected = ((long) 7 << 12) | ((-5 & 15) << 8) | ((-20 & 15) << 4) | (-30 & 15);
        assertEquals(expected, encoded.toLong());
    }

    @Test
    public void zeroIdForNonAirMaterialFallsBackWhileAirStaysBatched() {
        Long2ObjectOpenHashMap<BlockData> changes = new Long2ObjectOpenHashMap<BlockData>(2);
        long stoneKey = packKey(1, 65, 2);
        long airKey = packKey(2, 65, 2);
        changes.put(stoneKey, blockData(Material.STONE));
        changes.put(airKey, blockData(Material.AIR));
        Long2ObjectOpenHashMap<List<WrapperPlayServerMultiBlockChange.EncodedBlock>> sections =
            new Long2ObjectOpenHashMap<List<WrapperPlayServerMultiBlockChange.EncodedBlock>>(2);
        Long2ObjectOpenHashMap<BlockData> fallback = new Long2ObjectOpenHashMap<BlockData>(2);

        ProjectionClaimArbiter.groupBySection(changes, ignored -> 0, sections, fallback);

        assertEquals(1, fallback.size());
        assertTrue(fallback.containsKey(stoneKey));
        assertEquals(1, sections.size());
        assertEquals(1, sections.long2ObjectEntrySet().iterator().next().getValue().size());
    }

    @Test
    public void mappingFailureSentinelRoutesToFallback() {
        Long2ObjectOpenHashMap<BlockData> changes = new Long2ObjectOpenHashMap<BlockData>(1);
        long key = packKey(4, 70, -9);
        changes.put(key, blockData(Material.STONE));
        Long2ObjectOpenHashMap<List<WrapperPlayServerMultiBlockChange.EncodedBlock>> sections =
            new Long2ObjectOpenHashMap<List<WrapperPlayServerMultiBlockChange.EncodedBlock>>(1);
        Long2ObjectOpenHashMap<BlockData> fallback = new Long2ObjectOpenHashMap<BlockData>(1);

        ProjectionClaimArbiter.groupBySection(changes, ignored -> -1, sections, fallback);

        assertTrue(sections.isEmpty());
        assertEquals(1, fallback.size());
        assertTrue(fallback.containsKey(key));
    }

    private static long packKey(int x, int y, int z) {
        return (((long) x & 0x3FFFFFFL) << 38) | ((((long) y) & 0xFFFL) << 26) | (((long) z) & 0x3FFFFFFL);
    }

    private static BlockData blockData(Material material) {
        InvocationHandler handler = new MaterialBlockData(material);
        return (BlockData) Proxy.newProxyInstance(BlockData.class.getClassLoader(), new Class<?>[] { BlockData.class }, handler);
    }

    private static final class MaterialBlockData implements InvocationHandler {
        private final Material material;

        private MaterialBlockData(Material material) {
            this.material = material;
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) {
            String methodName = method.getName();
            if ("equals".equals(methodName)) {
                return proxy == args[0];
            }
            if ("hashCode".equals(methodName)) {
                return Integer.valueOf(System.identityHashCode(proxy));
            }
            if ("toString".equals(methodName) || "getAsString".equals(methodName)) {
                return material.name();
            }
            if ("getMaterial".equals(methodName)) {
                return material;
            }
            if ("clone".equals(methodName)) {
                return proxy;
            }
            Class<?> returnType = method.getReturnType();
            if (returnType == Boolean.TYPE) {
                return Boolean.FALSE;
            }
            if (returnType == Integer.TYPE) {
                return Integer.valueOf(0);
            }
            if (returnType == Float.TYPE) {
                return Float.valueOf(0.0F);
            }
            return null;
        }
    }
}
