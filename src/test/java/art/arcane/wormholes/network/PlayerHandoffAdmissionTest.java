package art.arcane.wormholes.network;

import org.junit.jupiter.api.Test;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlayerHandoffAdmissionTest {
    @Test
    void exactRetryReplaysAckWithoutAnotherReservation() {
        PlayerHandoffAdmission admission = new PlayerHandoffAdmission();
        PlayerHandoffAdmission.Request request = request(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), true, 4.0D);

        PlayerHandoffAdmission.Decision first = admission.decide(attempt(request, null, 1_000L, 60_000L, 1_000L));
        PlayerHandoffAdmission.Decision replay = admission.decide(attempt(request, "destination server is full", 1_100L, 60_000L, 1_000L));

        assertEquals(PlayerHandoffAdmission.Status.ACCEPTED, first.status());
        assertEquals(PlayerHandoffAdmission.Status.REPLAY_ACCEPTED, replay.status());
        assertEquals(first.reservation(), replay.reservation());
        assertEquals(1, admission.activeReservations(1_100L));
    }

    @Test
    void transferIdCannotBeReusedWithChangedIdentity() {
        PlayerHandoffAdmission admission = new PlayerHandoffAdmission();
        UUID transferId = UUID.randomUUID();
        PlayerHandoffAdmission.Request original = request(transferId, UUID.randomUUID(), UUID.randomUUID(), true, 4.0D);
        PlayerHandoffAdmission.Request changed = request(transferId, original.playerId(), original.exitPortalId(), true, 5.0D);

        admission.decide(attempt(original, null, 0L, 60_000L, 1_000L));
        PlayerHandoffAdmission.Decision collision = admission.decide(attempt(changed, null, 10L, 60_000L, 1_000L));

        assertEquals(PlayerHandoffAdmission.Status.DENIED, collision.status());
        assertEquals("transfer id belongs to another handoff", collision.reason());
        assertEquals(PlayerHandoffAdmission.Status.REPLAY_ACCEPTED,
            admission.decide(attempt(original, null, 20L, 60_000L, 1_000L)).status());
    }

    @Test
    void secondTransferForPendingPlayerIsDenied() {
        PlayerHandoffAdmission admission = new PlayerHandoffAdmission();
        UUID playerId = UUID.randomUUID();
        PlayerHandoffAdmission.Request first = request(UUID.randomUUID(), playerId, UUID.randomUUID(), true, 1.0D);
        PlayerHandoffAdmission.Request second = request(UUID.randomUUID(), playerId, UUID.randomUUID(), false, 2.0D);

        admission.decide(attempt(first, null, 0L, 5_000L, 1_000L));
        PlayerHandoffAdmission.Decision decision = admission.decide(attempt(second, null, 100L, 5_000L, 1_000L));

        assertFalse(decision.accepted());
        assertEquals("another handoff is already pending", decision.reason());
        assertEquals(4_900L, decision.retryAfterMillis());
        assertEquals(1, admission.activeReservations(100L));
    }

    @Test
    void destinationDenialIsStableAcrossPacketRetry() {
        PlayerHandoffAdmission admission = new PlayerHandoffAdmission();
        PlayerHandoffAdmission.Request request = request(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), true, 1.0D);

        PlayerHandoffAdmission.Decision denied = admission.decide(attempt(request, "player is not whitelisted", 0L, 60_000L, 1_000L));
        PlayerHandoffAdmission.Decision replay = admission.decide(attempt(request, null, 500L, 60_000L, 1_000L));

        assertEquals(PlayerHandoffAdmission.Status.DENIED, denied.status());
        assertEquals(PlayerHandoffAdmission.Status.REPLAY_DENIED, replay.status());
        assertEquals("player is not whitelisted", replay.reason());
        assertEquals(500L, replay.retryAfterMillis());
        assertEquals(0, admission.activeReservations(500L));
    }

    @Test
    void consumedArrivalStillEnforcesPerPlayerRateLimit() {
        PlayerHandoffAdmission admission = new PlayerHandoffAdmission();
        UUID playerId = UUID.randomUUID();
        PlayerHandoffAdmission.Request first = request(UUID.randomUUID(), playerId, UUID.randomUUID(), true, 1.0D);
        PlayerHandoffAdmission.Request tooSoon = request(UUID.randomUUID(), playerId, UUID.randomUUID(), true, 2.0D);
        PlayerHandoffAdmission.Request boundary = request(UUID.randomUUID(), playerId, UUID.randomUUID(), true, 3.0D);

        admission.decide(attempt(first, null, 0L, 60_000L, 1_000L));
        PlayerHandoffAdmission.Reservation firstArrival = admission.claimArrival(playerId, 100L);
        assertNotNull(firstArrival);
        assertTrue(admission.completeArrival(firstArrival, 100L));
        PlayerHandoffAdmission.Decision blocked = admission.decide(attempt(tooSoon, null, 999L, 60_000L, 1_000L));
        PlayerHandoffAdmission.Decision accepted = admission.decide(attempt(boundary, null, 1_000L, 60_000L, 1_000L));

        assertEquals("handoff rate limited", blocked.reason());
        assertEquals(PlayerHandoffAdmission.Status.ACCEPTED, accepted.status());
    }

    @Test
    void staleCancelCannotRemoveNewerReservation() {
        PlayerHandoffAdmission admission = new PlayerHandoffAdmission();
        UUID playerId = UUID.randomUUID();
        PlayerHandoffAdmission.Request first = request(UUID.randomUUID(), playerId, UUID.randomUUID(), true, 1.0D);
        PlayerHandoffAdmission.Request second = request(UUID.randomUUID(), playerId, UUID.randomUUID(), true, 2.0D);

        admission.decide(attempt(first, null, 0L, 60_000L, 1_000L));
        assertTrue(admission.cancel(cancellation(first, 100L)));
        assertEquals(PlayerHandoffAdmission.Status.ACCEPTED,
            admission.decide(attempt(second, null, 1_100L, 60_000L, 1_000L)).status());

        assertFalse(admission.cancel(cancellation(first, 1_200L)));
        PlayerHandoffAdmission.Reservation arrival = admission.claimArrival(playerId, 1_200L);
        assertNotNull(arrival);
        assertEquals(second.transferId(), arrival.request().transferId());
    }

    @Test
    void cancelProcessedBeforeScheduledRequestLeavesNoReservation() {
        PlayerHandoffAdmission admission = new PlayerHandoffAdmission();
        PlayerHandoffAdmission.Request request = request(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), true, 1.0D);

        assertFalse(admission.cancel(cancellation(request, 100L)));
        PlayerHandoffAdmission.Decision decision = admission.decide(attempt(request, null, 101L, 60_000L, 1_000L));

        assertEquals(PlayerHandoffAdmission.Status.REPLAY_DENIED, decision.status());
        assertEquals("handoff cancelled", decision.reason());
        assertEquals(0, admission.activeReservations(101L));
    }

    @Test
    void cancelledAdmissionCannotQueueLateAck() {
        PlayerHandoffAdmission admission = new PlayerHandoffAdmission();
        PlayerHandoffAdmission.Request request = request(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), true, 1.0D);
        AtomicBoolean senderCalled = new AtomicBoolean();

        admission.decide(attempt(request, null, 0L, 60_000L, 1_000L));
        admission.cancel(cancellation(request, 100L));
        boolean queued = admission.queueAcknowledgement(request, 101L, () -> {
            senderCalled.set(true);
            return true;
        });

        assertFalse(queued);
        assertFalse(senderCalled.get());
    }

    @Test
    void failedPlacementCanReleaseAndReclaimReservation() {
        PlayerHandoffAdmission admission = new PlayerHandoffAdmission();
        UUID playerId = UUID.randomUUID();
        PlayerHandoffAdmission.Request request = request(UUID.randomUUID(), playerId, UUID.randomUUID(), true, 1.0D);

        admission.decide(attempt(request, null, 0L, 60_000L, 1_000L));
        PlayerHandoffAdmission.Reservation firstClaim = admission.claimArrival(playerId, 100L);

        assertNotNull(firstClaim);
        assertNull(admission.claimArrival(playerId, 101L));
        assertTrue(admission.releaseArrival(firstClaim, 102L));
        PlayerHandoffAdmission.Reservation retry = admission.claimArrival(playerId, 103L);
        assertNotNull(retry);
        assertEquals(firstClaim.request(), retry.request());
        assertNotEquals(firstClaim.claimToken(), retry.claimToken());
        assertTrue(admission.completeArrival(retry, 104L));
        assertEquals(0, admission.activeReservations(104L));
    }

    @Test
    void stalePlacementCallbackCannotReleaseReconnectedClaim() {
        PlayerHandoffAdmission admission = new PlayerHandoffAdmission();
        UUID playerId = UUID.randomUUID();
        PlayerHandoffAdmission.Request request = request(UUID.randomUUID(), playerId, UUID.randomUUID(), true, 1.0D);

        admission.decide(attempt(request, null, 0L, 60_000L, 1_000L));
        PlayerHandoffAdmission.Reservation retiredClaim = admission.claimArrival(playerId, 100L);
        assertNotNull(retiredClaim);
        assertTrue(admission.releaseArrival(retiredClaim, 101L));

        PlayerHandoffAdmission.Reservation reconnectedClaim = admission.claimArrival(playerId, 102L);
        assertNotNull(reconnectedClaim);
        assertFalse(admission.releaseArrival(retiredClaim, 103L));
        assertFalse(admission.completeArrival(retiredClaim, 104L));
        assertTrue(admission.completeArrival(reconnectedClaim, 105L));
        assertEquals(0, admission.activeReservations(105L));
    }

    @Test
    void expiredReservationReleasesPlayer() {
        PlayerHandoffAdmission admission = new PlayerHandoffAdmission();
        UUID playerId = UUID.randomUUID();
        PlayerHandoffAdmission.Request expired = request(UUID.randomUUID(), playerId, UUID.randomUUID(), true, 1.0D);
        PlayerHandoffAdmission.Request replacement = request(UUID.randomUUID(), playerId, UUID.randomUUID(), true, 2.0D);

        admission.decide(attempt(expired, null, 0L, 500L, 100L));
        PlayerHandoffAdmission.Decision accepted = admission.decide(attempt(replacement, null, 500L, 500L, 100L));

        assertEquals(PlayerHandoffAdmission.Status.ACCEPTED, accepted.status());
        assertNull(admission.claimArrival(UUID.randomUUID(), 500L));
    }

    private static PlayerHandoffAdmission.Attempt attempt(
        PlayerHandoffAdmission.Request request,
        String denialReason,
        long nowMillis,
        long arrivalTtlMillis,
        long rateLimitMillis
    ) {
        return new PlayerHandoffAdmission.Attempt(request, denialReason, nowMillis, arrivalTtlMillis, rateLimitMillis);
    }

    private static PlayerHandoffAdmission.Cancellation cancellation(PlayerHandoffAdmission.Request request, long nowMillis) {
        return new PlayerHandoffAdmission.Cancellation(
            request.peerName(),
            request.transferId(),
            request.playerId(),
            nowMillis,
            1_000L,
            60_000L
        );
    }

    private static PlayerHandoffAdmission.Request request(
        UUID transferId,
        UUID playerId,
        UUID portalId,
        boolean directTransfer,
        double pointX
    ) {
        WireTraversive traversive = new WireTraversive(
            "N",
            "E",
            "U",
            0.0D,
            64.0D,
            0.0D,
            pointX,
            65.0D,
            0.0D,
            0.0D,
            0.0D,
            -1.0D,
            0.0D,
            0.0D,
            -1.0D,
            true
        );
        return new PlayerHandoffAdmission.Request(
            transferId,
            playerId,
            "Traveler",
            "alpha",
            portalId,
            directTransfer,
            traversive
        );
    }
}
