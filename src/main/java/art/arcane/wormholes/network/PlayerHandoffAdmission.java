package art.arcane.wormholes.network;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.BooleanSupplier;

final class PlayerHandoffAdmission {
    enum Status {
        ACCEPTED,
        REPLAY_ACCEPTED,
        DENIED,
        REPLAY_DENIED
    }

    private enum ArrivalState {
        RESERVED,
        PLACING,
        CONSUMED
    }

    record Request(
        UUID transferId,
        UUID playerId,
        String playerName,
        String peerName,
        UUID exitPortalId,
        boolean directTransfer,
        WireTraversive traversive
    ) {
        Request {
            Objects.requireNonNull(transferId);
            Objects.requireNonNull(playerId);
            Objects.requireNonNull(playerName);
            Objects.requireNonNull(peerName);
            Objects.requireNonNull(exitPortalId);
            Objects.requireNonNull(traversive);
        }
    }

    record Attempt(
        Request request,
        String denialReason,
        long nowMillis,
        long arrivalTtlMillis,
        long rateLimitMillis
    ) {
        Attempt {
            Objects.requireNonNull(request);
        }
    }

    record Reservation(Request request, long expiresAtMillis, long claimToken) {
    }

    record Cancellation(
        String peerName,
        UUID transferId,
        UUID playerId,
        long nowMillis,
        long rateLimitMillis,
        long tombstoneTtlMillis
    ) {
        Cancellation {
            Objects.requireNonNull(peerName);
            Objects.requireNonNull(transferId);
            Objects.requireNonNull(playerId);
        }
    }

    record Decision(Status status, Reservation reservation, String reason, long retryAfterMillis) {
        boolean accepted() {
            return status == Status.ACCEPTED || status == Status.REPLAY_ACCEPTED;
        }

        boolean fresh() {
            return status == Status.ACCEPTED;
        }
    }

    private record Entry(Request request, Reservation reservation, String reason, long expiresAtMillis, ArrivalState arrivalState) {
        boolean accepted() {
            return reservation != null;
        }

        boolean consumed() {
            return arrivalState == ArrivalState.CONSUMED;
        }

        Entry placing(Reservation claimedReservation) {
            return new Entry(request, claimedReservation, reason, expiresAtMillis, ArrivalState.PLACING);
        }

        Entry reserved() {
            return new Entry(request, reservation, reason, expiresAtMillis, ArrivalState.RESERVED);
        }

        Entry consume() {
            return new Entry(request, reservation, reason, expiresAtMillis, ArrivalState.CONSUMED);
        }
    }

    private record CancelledAttempt(String peerName, UUID playerId, long expiresAtMillis) {
    }

    private static final String RATE_LIMIT_REASON = "handoff rate limited";
    private static final String PENDING_REASON = "another handoff is already pending";
    private static final String COLLISION_REASON = "transfer id belongs to another handoff";
    private static final String CANCELLED_REASON = "handoff cancelled";

    private final Map<UUID, Entry> entriesByTransfer = new HashMap<>();
    private final Map<UUID, CancelledAttempt> cancelledByTransfer = new HashMap<>();
    private final Map<UUID, UUID> transferByPlayer = new HashMap<>();
    private final PlayerHandoffRateLimiter rateLimiter = new PlayerHandoffRateLimiter();
    private long claimSequence;

    synchronized Decision decide(Attempt attempt) {
        Request request = attempt.request();
        long nowMillis = attempt.nowMillis();
        long rateLimitMillis = Math.max(1L, attempt.rateLimitMillis());
        prune(nowMillis);

        CancelledAttempt cancelled = cancelledByTransfer.get(request.transferId());
        if (cancelled != null) {
            if (!cancelled.peerName().equals(request.peerName()) || !cancelled.playerId().equals(request.playerId())) {
                return denied(Status.DENIED, COLLISION_REASON, rateLimitMillis);
            }
            return denied(Status.REPLAY_DENIED, CANCELLED_REASON, cancelled.expiresAtMillis() - nowMillis);
        }

        Entry existing = entriesByTransfer.get(request.transferId());
        if (existing != null) {
            if (!existing.request().equals(request)) {
                return denied(Status.DENIED, COLLISION_REASON, rateLimitMillis);
            }
            if (existing.accepted()) {
                return new Decision(Status.REPLAY_ACCEPTED, existing.reservation(), "", 0L);
            }
            return denied(Status.REPLAY_DENIED, existing.reason(), Math.max(0L, existing.expiresAtMillis() - nowMillis));
        }

        UUID activeTransferId = transferByPlayer.get(request.playerId());
        if (activeTransferId != null) {
            Entry active = entriesByTransfer.get(activeTransferId);
            if (active != null && active.accepted() && !active.consumed()) {
                long retryAfterMillis = Math.max(1L, active.expiresAtMillis() - nowMillis);
                cacheDenied(request, PENDING_REASON, nowMillis, rateLimitMillis);
                return denied(Status.DENIED, PENDING_REASON, retryAfterMillis);
            }
            transferByPlayer.remove(request.playerId(), activeTransferId);
        }

        PlayerHandoffRateLimiter.Decision rateDecision = rateLimiter.acquire(request.playerId(), nowMillis, rateLimitMillis);
        if (!rateDecision.allowed()) {
            cacheDenied(request, RATE_LIMIT_REASON, nowMillis, rateLimitMillis);
            return denied(Status.DENIED, RATE_LIMIT_REASON, rateDecision.retryAfterMillis());
        }

        String denialReason = normalizeReason(attempt.denialReason());
        if (denialReason != null) {
            rateLimiter.penalize(request.playerId(), nowMillis, rateLimitMillis);
            cacheDenied(request, denialReason, nowMillis, rateLimitMillis);
            return denied(Status.DENIED, denialReason, rateLimitMillis);
        }

        long expiresAtMillis = nowMillis + Math.max(1L, attempt.arrivalTtlMillis());
        Reservation reservation = new Reservation(request, expiresAtMillis, 0L);
        entriesByTransfer.put(request.transferId(), new Entry(request, reservation, "", expiresAtMillis, ArrivalState.RESERVED));
        transferByPlayer.put(request.playerId(), request.transferId());
        return new Decision(Status.ACCEPTED, reservation, "", 0L);
    }

    synchronized Reservation claimArrival(UUID playerId, long nowMillis) {
        Objects.requireNonNull(playerId);
        prune(nowMillis);
        UUID transferId = transferByPlayer.get(playerId);
        if (transferId == null) {
            return null;
        }
        Entry entry = entriesByTransfer.get(transferId);
        if (entry == null || !entry.accepted() || entry.arrivalState() != ArrivalState.RESERVED) {
            return null;
        }
        Reservation claimed = new Reservation(entry.request(), entry.expiresAtMillis(), nextClaimToken());
        entriesByTransfer.put(transferId, entry.placing(claimed));
        return claimed;
    }

    synchronized boolean completeArrival(Reservation reservation, long nowMillis) {
        Objects.requireNonNull(reservation);
        prune(nowMillis);
        Request request = reservation.request();
        Entry entry = entriesByTransfer.get(request.transferId());
        if (entry == null || !entry.accepted() || !entry.reservation().equals(reservation)
            || entry.arrivalState() != ArrivalState.PLACING) {
            return false;
        }
        entriesByTransfer.put(request.transferId(), entry.consume());
        transferByPlayer.remove(request.playerId(), request.transferId());
        return true;
    }

    synchronized boolean releaseArrival(Reservation reservation, long nowMillis) {
        Objects.requireNonNull(reservation);
        prune(nowMillis);
        Request request = reservation.request();
        Entry entry = entriesByTransfer.get(request.transferId());
        if (entry == null || !entry.accepted() || !entry.reservation().equals(reservation)
            || entry.arrivalState() != ArrivalState.PLACING) {
            return false;
        }
        entriesByTransfer.put(request.transferId(), entry.reserved());
        return true;
    }

    synchronized boolean cancel(Cancellation cancellation) {
        String peerName = cancellation.peerName();
        UUID transferId = cancellation.transferId();
        UUID playerId = cancellation.playerId();
        long nowMillis = cancellation.nowMillis();
        prune(nowMillis);
        Entry entry = entriesByTransfer.get(transferId);
        long interval = Math.max(1L, cancellation.rateLimitMillis());
        if (entry == null) {
            long tombstoneTtlMillis = Math.max(interval, cancellation.tombstoneTtlMillis());
            cancelledByTransfer.putIfAbsent(transferId, new CancelledAttempt(peerName, playerId, nowMillis + tombstoneTtlMillis));
            rateLimiter.penalize(playerId, nowMillis, interval);
            return false;
        }
        if (!entry.accepted() || entry.arrivalState() != ArrivalState.RESERVED
            || !entry.request().peerName().equals(peerName) || !entry.request().playerId().equals(playerId)) {
            return false;
        }
        transferByPlayer.remove(playerId, transferId);
        long tombstoneTtlMillis = Math.max(interval, cancellation.tombstoneTtlMillis());
        entriesByTransfer.put(transferId, new Entry(entry.request(), null, CANCELLED_REASON, nowMillis + tombstoneTtlMillis, ArrivalState.CONSUMED));
        rateLimiter.penalize(playerId, nowMillis, interval);
        return true;
    }

    synchronized boolean queueAcknowledgement(Request request, long nowMillis, BooleanSupplier sender) {
        Objects.requireNonNull(request);
        Objects.requireNonNull(sender);
        prune(nowMillis);
        Entry entry = entriesByTransfer.get(request.transferId());
        if (entry == null || !entry.request().equals(request) || !entry.accepted()) {
            return false;
        }
        return sender.getAsBoolean();
    }

    synchronized boolean release(Request request, long nowMillis) {
        Objects.requireNonNull(request);
        prune(nowMillis);
        Entry entry = entriesByTransfer.get(request.transferId());
        if (entry == null || !entry.request().equals(request) || !entry.accepted() || entry.arrivalState() != ArrivalState.RESERVED) {
            return false;
        }
        entriesByTransfer.remove(request.transferId(), entry);
        transferByPlayer.remove(request.playerId(), request.transferId());
        return true;
    }

    synchronized int activeReservations(long nowMillis) {
        prune(nowMillis);
        return transferByPlayer.size();
    }

    synchronized void prune(long nowMillis) {
        cancelledByTransfer.values().removeIf(cancelled -> cancelled.expiresAtMillis() <= nowMillis);
        entriesByTransfer.entrySet().removeIf(entry -> {
            Entry value = entry.getValue();
            if (value.expiresAtMillis() > nowMillis) {
                return false;
            }
            transferByPlayer.remove(value.request().playerId(), entry.getKey());
            return true;
        });
    }

    private void cacheDenied(Request request, String reason, long nowMillis, long rateLimitMillis) {
        long expiresAtMillis = nowMillis + Math.max(1L, rateLimitMillis);
        entriesByTransfer.put(request.transferId(), new Entry(request, null, reason, expiresAtMillis, ArrivalState.CONSUMED));
    }

    private static Decision denied(Status status, String reason, long retryAfterMillis) {
        return new Decision(status, null, reason, Math.max(0L, retryAfterMillis));
    }

    private static String normalizeReason(String reason) {
        if (reason == null || reason.isBlank()) {
            return null;
        }
        return reason;
    }

    private long nextClaimToken() {
        claimSequence++;
        if (claimSequence == 0L) {
            claimSequence++;
        }
        return claimSequence;
    }
}
