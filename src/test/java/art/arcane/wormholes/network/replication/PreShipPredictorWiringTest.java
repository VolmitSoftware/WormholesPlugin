package art.arcane.wormholes.network.replication;

import art.arcane.wormholes.network.view.PreShipPredictor;
import art.arcane.wormholes.network.view.ViewSlice;

import org.bukkit.World;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PreShipPredictorWiringTest {
    private static final UUID PORTAL_ID = new UUID(0xAAAAL, 0xBBBBL);
    private static final UUID PLAYER_ID = new UUID(0xCCCCL, 0xDDDDL);
    private static final String PEER = "peer-pre";

    @Test
    void approachingPlayerSchedulesPreShipBulkRegistration(@TempDir Path dir) {
        TestNetworkSink sink = new TestNetworkSink(dir);
        ChunkReplicationManager replication = sink.getReplicationManager();
        World world = StubWorld.create(UUID.randomUUID());

        PreShipPredictor predictor = new PreShipPredictor();
        PreShipPredictor.Settings settings = new PreShipPredictor.Settings(true, 24.0D, 0.1D, 0.25D, 2.0D);
        PreShipPredictor.GatewayAccessor accessor = (x, z, radius) -> List.of(
            new PreShipPredictor.GatewayInfo(PORTAL_ID, 10.0D, 64.0D, 0.0D, 1.0D, 0.0D, 0.0D));
        PreShipPredictor.PlayerPose pose = new PreShipPredictor.PlayerPose(
            PLAYER_ID, PEER, 0.0D, 64.0D, 0.0D, 1.0D, 0.0D, 0.0D);
        List<PreShipPredictor.PreShipTicket> opened = predictor.tick(pose, accessor, settings, 1_000L);
        assertEquals(1, opened.size());

        long chunkKey = ViewSlice.columnKey(0, 0);
        replication.subscribePreShip(PEER, PORTAL_ID, world, List.of(chunkKey));
        assertEquals(1, replication.totalSubscriptionCount());
        assertFalse(replication.isPreShipPromoted(PEER, PORTAL_ID, chunkKey));
    }

    @Test
    void traversalPromotesPreShipSubscription(@TempDir Path dir) {
        TestNetworkSink sink = new TestNetworkSink(dir);
        ChunkReplicationManager replication = sink.getReplicationManager();
        World world = StubWorld.create(UUID.randomUUID());
        long chunkKey = ViewSlice.columnKey(2, 2);
        replication.subscribePreShip(PEER, PORTAL_ID, world, List.of(chunkKey));

        PreShipPredictor predictor = new PreShipPredictor();
        PreShipPredictor.Settings settings = new PreShipPredictor.Settings(true, 24.0D, 0.1D, 0.25D, 2.0D);
        PreShipPredictor.PlayerPose pose = new PreShipPredictor.PlayerPose(
            PLAYER_ID, PEER, 0.0D, 64.0D, 0.0D, 1.0D, 0.0D, 0.0D);
        PreShipPredictor.GatewayAccessor accessor = (x, z, radius) -> List.of(
            new PreShipPredictor.GatewayInfo(PORTAL_ID, 10.0D, 64.0D, 0.0D, 1.0D, 0.0D, 0.0D));
        predictor.tick(pose, accessor, settings, 1_000L);

        PreShipPredictor.PreShipTicket promoted = predictor.promote(PEER, PORTAL_ID);
        assertTrue(promoted != null && promoted.isPromoted());

        replication.promotePreShip(PEER, PORTAL_ID);
        assertTrue(replication.isPreShipPromoted(PEER, PORTAL_ID, chunkKey));
    }

    @Test
    void cancellingPreShipReleasesSubscription(@TempDir Path dir) {
        TestNetworkSink sink = new TestNetworkSink(dir);
        ChunkReplicationManager replication = sink.getReplicationManager();
        World world = StubWorld.create(UUID.randomUUID());
        long chunkKey = ViewSlice.columnKey(3, 3);
        replication.subscribePreShip(PEER, PORTAL_ID, world, List.of(chunkKey));
        assertEquals(1, replication.totalSubscriptionCount());
        replication.cancelPreShip(PEER, PORTAL_ID);
        assertEquals(0, replication.totalSubscriptionCount());
    }
}
