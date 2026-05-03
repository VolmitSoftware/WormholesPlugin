package art.arcane.wormholes.render;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.UUID;

import org.bukkit.block.data.BlockData;
import org.junit.jupiter.api.Test;

public final class ProjectionClaimSetTest {
    private static final long CELL_KEY = 42L;

    @Test
    public void nearestPortalClaimWinsSharedCell() {
        ProjectionClaimSet set = new ProjectionClaimSet();
        UUID farPortal = UUID.fromString("00000000-0000-0000-0000-000000000002");
        UUID nearPortal = UUID.fromString("00000000-0000-0000-0000-000000000001");
        BlockData farData = blockData("far");
        BlockData nearData = blockData("near");

        set.replacePortalClaims(farPortal, farPortal.toString(), 8.0D, singleClaim(farData));
        ProjectionClaimSet.ProjectionClaimSetResult result = set.replacePortalClaims(nearPortal, nearPortal.toString(), 2.0D, singleClaim(nearData));

        assertSame(nearData, set.getWinningClaim(CELL_KEY).getData());
        assertEquals(1, result.getConflicts());
        assertEquals(1, result.getWinnerChanges());
        assertTrue(result.getPacketChangeKeys().contains(CELL_KEY));
    }

    @Test
    public void losingPortalUpdateDoesNotOverwriteWinner() {
        ProjectionClaimSet set = new ProjectionClaimSet();
        UUID farPortal = UUID.fromString("00000000-0000-0000-0000-000000000002");
        UUID nearPortal = UUID.fromString("00000000-0000-0000-0000-000000000001");
        BlockData nearData = blockData("near");

        set.replacePortalClaims(farPortal, farPortal.toString(), 8.0D, singleClaim(blockData("far-a")));
        set.replacePortalClaims(nearPortal, nearPortal.toString(), 2.0D, singleClaim(nearData));
        ProjectionClaimSet.ProjectionClaimSetResult result = set.replacePortalClaims(farPortal, farPortal.toString(), 8.0D, singleClaim(blockData("far-b")));

        assertSame(nearData, set.getWinningClaim(CELL_KEY).getData());
        assertEquals(1, result.getConflicts());
        assertEquals(0, result.getWinnerChanges());
        assertFalse(result.getPacketChangeKeys().contains(CELL_KEY));
    }

    @Test
    public void releasingWinnerFallsBackToNextClaimBeforeRealBlock() {
        ProjectionClaimSet set = new ProjectionClaimSet();
        UUID farPortal = UUID.fromString("00000000-0000-0000-0000-000000000002");
        UUID nearPortal = UUID.fromString("00000000-0000-0000-0000-000000000001");
        BlockData farData = blockData("far");

        set.replacePortalClaims(farPortal, farPortal.toString(), 8.0D, singleClaim(farData));
        set.replacePortalClaims(nearPortal, nearPortal.toString(), 2.0D, singleClaim(blockData("near")));
        ProjectionClaimSet.ProjectionClaimSetResult fallback = set.releasePortal(nearPortal);

        assertSame(farData, set.getWinningClaim(CELL_KEY).getData());
        assertEquals(1, fallback.getWinnerChanges());
        assertEquals(0, fallback.getReverts());
        assertTrue(fallback.getPacketChangeKeys().contains(CELL_KEY));

        ProjectionClaimSet.ProjectionClaimSetResult revert = set.releasePortal(farPortal);
        assertNull(set.getWinningClaim(CELL_KEY));
        assertEquals(1, revert.getReverts());
        assertTrue(revert.getPacketChangeKeys().contains(CELL_KEY));
    }

    @Test
    public void equalDistanceTieBreaksByPortalId() {
        ProjectionClaimSet set = new ProjectionClaimSet();
        UUID highPortal = UUID.fromString("00000000-0000-0000-0000-000000000009");
        UUID lowPortal = UUID.fromString("00000000-0000-0000-0000-000000000001");
        BlockData lowData = blockData("low");

        set.replacePortalClaims(highPortal, highPortal.toString(), 4.0D, singleClaim(blockData("high")));
        set.replacePortalClaims(lowPortal, lowPortal.toString(), 4.0D, singleClaim(lowData));

        assertSame(lowData, set.getWinningClaim(CELL_KEY).getData());
        assertTrue(ProjectionClaimSet.isHigherPriority(4.0D, lowPortal.toString(), 4.0D, highPortal.toString()));
    }

    @Test
    public void realProjectionBeatsNearerMaskAirClaim() {
        ProjectionClaimSet set = new ProjectionClaimSet();
        UUID maskPortal = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID blockPortal = UUID.fromString("00000000-0000-0000-0000-000000000002");
        BlockData blockData = blockData("block");

        set.replacePortalClaims(maskPortal, maskPortal.toString(), 1.0D, singleMaskClaim(blockData("mask")));
        set.replacePortalClaims(blockPortal, blockPortal.toString(), 8.0D, singleClaim(blockData));

        assertSame(blockData, set.getWinningClaim(CELL_KEY).getData());
        assertTrue(ProjectionClaimSet.isHigherPriority(8.0D, blockPortal.toString(), singleClaimValue(blockData),
            1.0D, maskPortal.toString(), singleMaskClaimValue(blockData("mask"))));
    }

    @Test
    public void stableResubmitsDoNotProducePacketChanges() {
        ProjectionClaimSet set = new ProjectionClaimSet();
        UUID portal = UUID.fromString("00000000-0000-0000-0000-000000000001");
        BlockData data = blockData("stable");

        ProjectionClaimSet.ProjectionClaimSetResult first = set.replacePortalClaims(portal, portal.toString(), 2.0D, singleClaim(data));
        ProjectionClaimSet.ProjectionClaimSetResult second = set.replacePortalClaims(portal, portal.toString(), 2.0D, singleClaim(data));

        assertEquals(1, first.getPacketChangeKeys().size());
        assertEquals(0, second.getPacketChangeKeys().size());
        assertEquals(0, second.getWinnerChanges());
    }

    @Test
    public void recursivePortalFallbackPolicyMasksOnlyBlockedApertures() {
        assertTrue(PortalProjector.shouldMaskRecursivePortalAperture(true, false, 0));
        assertTrue(PortalProjector.shouldMaskRecursivePortalAperture(true, true, 3));
        assertTrue(PortalProjector.shouldMaskRecursivePortalAperture(false, false, 3));
        assertFalse(PortalProjector.shouldMaskRecursivePortalAperture(true, false, 1));
    }

    @Test
    public void maskAirAlwaysProjectsWhileRemoteAirSkipsLocalAir() {
        assertTrue(PortalProjector.shouldProjectAirSample(PortalProjector.ProjectedSampleKind.MASK_AIR, true));
        assertTrue(PortalProjector.shouldProjectAirSample(PortalProjector.ProjectedSampleKind.MASK_AIR, false));
        assertFalse(PortalProjector.shouldProjectAirSample(PortalProjector.ProjectedSampleKind.REMOTE_AIR, true));
        assertTrue(PortalProjector.shouldProjectAirSample(PortalProjector.ProjectedSampleKind.REMOTE_AIR, false));
        assertFalse(PortalProjector.shouldProjectAirSample(PortalProjector.ProjectedSampleKind.NO_SAMPLE, false));
    }

    private static Long2ObjectOpenHashMap<ProjectedBlockClaim> singleClaim(BlockData data) {
        Long2ObjectOpenHashMap<ProjectedBlockClaim> claims = new Long2ObjectOpenHashMap<ProjectedBlockClaim>(1);
        claims.put(CELL_KEY, singleClaimValue(data));
        return claims;
    }

    private static Long2ObjectOpenHashMap<ProjectedBlockClaim> singleMaskClaim(BlockData data) {
        Long2ObjectOpenHashMap<ProjectedBlockClaim> claims = new Long2ObjectOpenHashMap<ProjectedBlockClaim>(1);
        claims.put(CELL_KEY, singleMaskClaimValue(data));
        return claims;
    }

    private static ProjectedBlockClaim singleClaimValue(BlockData data) {
        return new ProjectedBlockClaim(data, null, ProjectedBlockClaim.NO_REMOTE_KEY, false);
    }

    private static ProjectedBlockClaim singleMaskClaimValue(BlockData data) {
        return new ProjectedBlockClaim(data, null, ProjectedBlockClaim.NO_REMOTE_KEY, true);
    }

    private static BlockData blockData(String name) {
        InvocationHandler handler = new NamedBlockData(name);
        return (BlockData) Proxy.newProxyInstance(BlockData.class.getClassLoader(), new Class<?>[] { BlockData.class }, handler);
    }

    private static final class NamedBlockData implements InvocationHandler {
        private final String name;

        private NamedBlockData(String name) {
            this.name = name;
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
                return name;
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
