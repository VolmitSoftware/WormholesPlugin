package art.arcane.wormholes.render;

import static org.junit.jupiter.api.Assertions.assertEquals;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;

import java.util.Arrays;

import org.bukkit.World;
import org.bukkit.block.data.BlockData;
import org.junit.jupiter.api.Test;

import art.arcane.wormholes.render.view.ProjectionWorldView;

public final class ProjectorLightingOverlayTest {
    @Test
    public void overlayWritesOnlyClaimedRemoteLitCellsInTargetSection() {
        byte[] skyArr = new byte[2048];
        byte[] blockArr = new byte[2048];
        Arrays.fill(skyArr, (byte) 0x33);
        Arrays.fill(blockArr, (byte) 0x44);
        Long2ObjectOpenHashMap<ProjectedBlockClaim> claims = new Long2ObjectOpenHashMap<ProjectedBlockClaim>(4);
        ProjectionWorldView view = stubView(12, 7, 0);
        claims.put(packKey(3, 70, 5), new ProjectedBlockClaim(null, view, packKey(100, 40, 100), false));
        claims.put(packKey(3, 90, 5), new ProjectedBlockClaim(null, view, packKey(100, 41, 100), false));
        claims.put(packKey(35, 70, 5), new ProjectedBlockClaim(null, view, packKey(100, 42, 100), false));
        claims.put(packKey(4, 70, 5), new ProjectedBlockClaim(null, null, ProjectedBlockClaim.NO_REMOTE_KEY, true));

        ProjectorLighting.overlayProjectedLight(claims, 0, 0, 4, 0, skyArr, blockArr);

        int nibbleIdx = ((70 - 64) << 8) | (5 << 4) | 3;
        assertEquals(12, readNibble(skyArr, nibbleIdx));
        assertEquals(7, readNibble(blockArr, nibbleIdx));
        int maskAirIdx = ((70 - 64) << 8) | (5 << 4) | 4;
        assertEquals(3, readNibble(skyArr, maskAirIdx));
        assertEquals(4, readNibble(blockArr, maskAirIdx));
        int untouched = 0;
        for (int i = 0; i < 4096; i++) {
            if (i == nibbleIdx) {
                continue;
            }
            if (readNibble(skyArr, i) == 3 && readNibble(blockArr, i) == 4) {
                untouched++;
            }
        }
        assertEquals(4095, untouched);
    }

    @Test
    public void overlayMixesSourceDarkenIntoLocalSkyExactlyLikeTheFullScan() {
        byte[] skyArr = new byte[2048];
        byte[] blockArr = new byte[2048];
        Long2ObjectOpenHashMap<ProjectedBlockClaim> claims = new Long2ObjectOpenHashMap<ProjectedBlockClaim>(1);
        ProjectionWorldView view = stubView(15, 0, 11);
        claims.put(packKey(0, 64, 0), new ProjectedBlockClaim(null, view, packKey(200, 40, 200), false));

        ProjectorLighting.overlayProjectedLight(claims, 0, 0, 4, 11, skyArr, blockArr);

        int rawSky = 15;
        int rawBlock = 0;
        int sourceSkyBrightness = Math.max(0, rawSky - 11);
        int expectedSky = Math.min(15, sourceSkyBrightness + 11);
        int target = Math.max(rawBlock, sourceSkyBrightness);
        int expectedBlock = target > 15 - 11 ? target : rawBlock;
        assertEquals(expectedSky, readNibble(skyArr, 0));
        assertEquals(expectedBlock, readNibble(blockArr, 0));
    }

    @Test
    public void overlaySkipsUnavailableLightAndOutOfRangeRemoteY() {
        byte[] skyArr = new byte[2048];
        byte[] blockArr = new byte[2048];
        Arrays.fill(skyArr, (byte) 0x99);
        Arrays.fill(blockArr, (byte) 0x99);
        Long2ObjectOpenHashMap<ProjectedBlockClaim> claims = new Long2ObjectOpenHashMap<ProjectedBlockClaim>(2);
        ProjectionWorldView unavailableView = stubView(-1, -1, 0);
        ProjectionWorldView normalView = stubView(5, 5, 0);
        claims.put(packKey(0, 64, 0), new ProjectedBlockClaim(null, unavailableView, packKey(0, 40, 0), false));
        claims.put(packKey(1, 64, 0), new ProjectedBlockClaim(null, normalView, packKey(0, 5000, 0), false));

        ProjectorLighting.overlayProjectedLight(claims, 0, 0, 4, 0, skyArr, blockArr);

        assertEquals(9, readNibble(skyArr, 0));
        assertEquals(9, readNibble(blockArr, 0));
        assertEquals(9, readNibble(skyArr, 1));
        assertEquals(9, readNibble(blockArr, 1));
    }

    @Test
    public void writeLightNibblePacksLowAndHighNibbles() {
        byte[] skyArr = new byte[2048];
        byte[] blockArr = new byte[2048];

        ProjectorLighting.writeLightNibble(skyArr, blockArr, 0, 5, 9);
        ProjectorLighting.writeLightNibble(skyArr, blockArr, 1, 12, 3);

        assertEquals(5, readNibble(skyArr, 0));
        assertEquals(12, readNibble(skyArr, 1));
        assertEquals(9, readNibble(blockArr, 0));
        assertEquals(3, readNibble(blockArr, 1));
    }

    private static int readNibble(byte[] arr, int nibbleIdx) {
        int byteIdx = nibbleIdx >> 1;
        if ((nibbleIdx & 1) == 0) {
            return arr[byteIdx] & 0x0F;
        }
        return (arr[byteIdx] >> 4) & 0x0F;
    }

    private static ProjectionWorldView stubView(int sky, int block, int skyDarken) {
        return new ProjectionWorldView() {
            @Override
            public World getWorld() {
                return null;
            }

            @Override
            public int getMinHeight() {
                return -64;
            }

            @Override
            public int getMaxHeight() {
                return 320;
            }

            @Override
            public BlockData sampleBlockData(int x, int y, int z) {
                return null;
            }

            @Override
            public String sampleBiome(int x, int y, int z) {
                return null;
            }

            @Override
            public int getLight(int x, int y, int z) {
                if (sky < 0 || block < 0) {
                    return ProjectionWorldView.LIGHT_UNAVAILABLE;
                }
                return ProjectionWorldView.packLight(sky, block);
            }

            @Override
            public int getSkyDarken() {
                return skyDarken;
            }
        };
    }

    private static long packKey(int x, int y, int z) {
        return (((long) x & 0x3FFFFFFL) << 38) | ((((long) y) & 0xFFFL) << 26) | (((long) z) & 0x3FFFFFFL);
    }
}
