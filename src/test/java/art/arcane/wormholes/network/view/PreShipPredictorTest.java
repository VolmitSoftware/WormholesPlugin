package art.arcane.wormholes.network.view;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PreShipPredictorTest {
    private static final UUID PORTAL_ID = new UUID(0xAAAAL, 0xBBBBL);
    private static final UUID PLAYER_ID = new UUID(0xCCCCL, 0xDDDDL);

    private static PreShipPredictor.GatewayAccessor singleGateway(double gx, double gy, double gz,
                                                                   double nx, double ny, double nz) {
        PreShipPredictor.GatewayInfo info = new PreShipPredictor.GatewayInfo(PORTAL_ID, gx, gy, gz, nx, ny, nz);
        return (x, z, radius) -> List.of(info);
    }

    private static PreShipPredictor.Settings enabledSettings() {
        return new PreShipPredictor.Settings(true, 24.0D, 0.1D, 0.25D, 2.0D);
    }

    @Test
    void approachingPlayerOpensTicket() {
        PreShipPredictor predictor = new PreShipPredictor();
        PreShipPredictor.PlayerPose pose = new PreShipPredictor.PlayerPose(
            PLAYER_ID, "subA",
            0.0D, 64.0D, 0.0D,
            1.0D, 0.0D, 0.0D);
        PreShipPredictor.GatewayAccessor accessor = singleGateway(10.0D, 64.0D, 0.0D, 1.0D, 0.0D, 0.0D);
        List<PreShipPredictor.PreShipTicket> opened = predictor.tick(pose, accessor, enabledSettings(), 1000L);
        assertEquals(1, opened.size());
        assertTrue(predictor.isPreShipping("subA", PORTAL_ID));
    }

    @Test
    void stationaryPlayerOpensNoTicket() {
        PreShipPredictor predictor = new PreShipPredictor();
        PreShipPredictor.PlayerPose pose = new PreShipPredictor.PlayerPose(
            PLAYER_ID, "subStill",
            0.0D, 64.0D, 0.0D,
            0.0D, 0.0D, 0.0D);
        PreShipPredictor.GatewayAccessor accessor = singleGateway(10.0D, 64.0D, 0.0D, 1.0D, 0.0D, 0.0D);
        List<PreShipPredictor.PreShipTicket> opened = predictor.tick(pose, accessor, enabledSettings(), 1000L);
        assertTrue(opened.isEmpty());
        assertFalse(predictor.isPreShipping("subStill", PORTAL_ID));
    }

    @Test
    void turningAwayCancelsTicketAfterGrace() {
        PreShipPredictor predictor = new PreShipPredictor();
        PreShipPredictor.PlayerPose approaching = new PreShipPredictor.PlayerPose(
            PLAYER_ID, "subFlip",
            0.0D, 64.0D, 0.0D,
            1.0D, 0.0D, 0.0D);
        PreShipPredictor.PlayerPose retreating = new PreShipPredictor.PlayerPose(
            PLAYER_ID, "subFlip",
            0.0D, 64.0D, 0.0D,
            -1.0D, 0.0D, 0.0D);
        PreShipPredictor.GatewayAccessor accessor = singleGateway(10.0D, 64.0D, 0.0D, 1.0D, 0.0D, 0.0D);
        PreShipPredictor.Settings settings = enabledSettings();
        predictor.tick(approaching, accessor, settings, 1000L);
        assertTrue(predictor.isPreShipping("subFlip", PORTAL_ID));
        predictor.tick(retreating, accessor, settings, 1200L);
        List<PreShipPredictor.PreShipTicket> canceled = predictor.sweepCanceled(settings, 1200L + 2_000L);
        assertEquals(1, canceled.size());
        assertEquals("subFlip", canceled.get(0).getSubscriberId());
        assertFalse(predictor.isPreShipping("subFlip", PORTAL_ID));
    }

    @Test
    void promoteFlipsTicketState() {
        PreShipPredictor predictor = new PreShipPredictor();
        PreShipPredictor.PlayerPose pose = new PreShipPredictor.PlayerPose(
            PLAYER_ID, "subPromote",
            0.0D, 64.0D, 0.0D,
            1.0D, 0.0D, 0.0D);
        PreShipPredictor.GatewayAccessor accessor = singleGateway(10.0D, 64.0D, 0.0D, 1.0D, 0.0D, 0.0D);
        predictor.tick(pose, accessor, enabledSettings(), 1000L);
        PreShipPredictor.PreShipTicket promoted = predictor.promote("subPromote", PORTAL_ID);
        assertNotNull(promoted);
        assertTrue(promoted.isPromoted());
    }

    @Test
    void promoteOnMissingTicketReturnsNull() {
        PreShipPredictor predictor = new PreShipPredictor();
        assertNull(predictor.promote("never", PORTAL_ID));
    }

    @Test
    void multipleSubscribersAreTrackedIndependently() {
        PreShipPredictor predictor = new PreShipPredictor();
        PreShipPredictor.GatewayAccessor accessor = singleGateway(10.0D, 64.0D, 0.0D, 1.0D, 0.0D, 0.0D);
        PreShipPredictor.Settings settings = enabledSettings();
        PreShipPredictor.PlayerPose first = new PreShipPredictor.PlayerPose(
            new UUID(1L, 1L), "subA",
            0.0D, 64.0D, 0.0D,
            1.0D, 0.0D, 0.0D);
        PreShipPredictor.PlayerPose second = new PreShipPredictor.PlayerPose(
            new UUID(2L, 2L), "subB",
            0.0D, 64.0D, 0.0D,
            1.0D, 0.0D, 0.0D);
        predictor.tick(first, accessor, settings, 1000L);
        predictor.tick(second, accessor, settings, 1000L);
        assertEquals(2, predictor.activeTicketCount());
        predictor.cancel("subA", PORTAL_ID);
        assertEquals(1, predictor.activeTicketCount());
        assertTrue(predictor.isPreShipping("subB", PORTAL_ID));
        assertFalse(predictor.isPreShipping("subA", PORTAL_ID));
    }

    @Test
    void clearSubscriberRemovesAllSubscriberTickets() {
        PreShipPredictor predictor = new PreShipPredictor();
        PreShipPredictor.GatewayAccessor accessor = singleGateway(10.0D, 64.0D, 0.0D, 1.0D, 0.0D, 0.0D);
        PreShipPredictor.PlayerPose pose = new PreShipPredictor.PlayerPose(
            PLAYER_ID, "subClear",
            0.0D, 64.0D, 0.0D,
            1.0D, 0.0D, 0.0D);
        predictor.tick(pose, accessor, enabledSettings(), 1000L);
        assertTrue(predictor.isPreShipping("subClear", PORTAL_ID));
        predictor.clearSubscriber("subClear");
        assertFalse(predictor.isPreShipping("subClear", PORTAL_ID));
    }

    @Test
    void disabledSettingsReturnsEmpty() {
        PreShipPredictor predictor = new PreShipPredictor();
        PreShipPredictor.PlayerPose pose = new PreShipPredictor.PlayerPose(
            PLAYER_ID, "subDisabled",
            0.0D, 64.0D, 0.0D,
            1.0D, 0.0D, 0.0D);
        PreShipPredictor.GatewayAccessor accessor = singleGateway(10.0D, 64.0D, 0.0D, 1.0D, 0.0D, 0.0D);
        List<PreShipPredictor.PreShipTicket> opened = predictor.tick(pose, accessor, PreShipPredictor.Settings.disabled(), 1000L);
        assertTrue(opened.isEmpty());
    }

    @Test
    void outOfRangePlayerOpensNoTicket() {
        PreShipPredictor predictor = new PreShipPredictor();
        PreShipPredictor.PlayerPose pose = new PreShipPredictor.PlayerPose(
            PLAYER_ID, "subRange",
            0.0D, 64.0D, 0.0D,
            1.0D, 0.0D, 0.0D);
        PreShipPredictor.GatewayAccessor accessor = (x, z, radius) -> List.of(
            new PreShipPredictor.GatewayInfo(PORTAL_ID, 100.0D, 64.0D, 0.0D, 1.0D, 0.0D, 0.0D)
        );
        List<PreShipPredictor.PreShipTicket> opened = predictor.tick(pose, accessor, enabledSettings(), 1000L);
        assertTrue(opened.isEmpty());
    }

    @Test
    void cancelReturnsRemovedTicket() {
        PreShipPredictor predictor = new PreShipPredictor();
        PreShipPredictor.PlayerPose pose = new PreShipPredictor.PlayerPose(
            PLAYER_ID, "subCancel",
            0.0D, 64.0D, 0.0D,
            1.0D, 0.0D, 0.0D);
        PreShipPredictor.GatewayAccessor accessor = singleGateway(10.0D, 64.0D, 0.0D, 1.0D, 0.0D, 0.0D);
        predictor.tick(pose, accessor, enabledSettings(), 1000L);
        PreShipPredictor.PreShipTicket removed = predictor.cancel("subCancel", PORTAL_ID);
        assertNotNull(removed);
        assertFalse(predictor.isPreShipping("subCancel", PORTAL_ID));
    }
}
