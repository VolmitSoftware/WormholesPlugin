package art.arcane.wormholes.network;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

final class PlayerHandoffRateLimiter {
    record Decision(boolean allowed, long retryAfterMillis) {
    }

    private final Map<UUID, Long> limitedUntil = new HashMap<>();

    synchronized Decision acquire(UUID playerId, long nowMillis, long intervalMillis) {
        Objects.requireNonNull(playerId);
        long interval = Math.max(1L, intervalMillis);
        Long current = limitedUntil.get(playerId);
        if (current != null && current.longValue() > nowMillis) {
            return new Decision(false, current.longValue() - nowMillis);
        }
        limitedUntil.put(playerId, Long.valueOf(nowMillis + interval));
        prune(nowMillis);
        return new Decision(true, 0L);
    }

    synchronized void penalize(UUID playerId, long nowMillis, long intervalMillis) {
        Objects.requireNonNull(playerId);
        long until = nowMillis + Math.max(1L, intervalMillis);
        Long current = limitedUntil.get(playerId);
        if (current == null || current.longValue() < until) {
            limitedUntil.put(playerId, Long.valueOf(until));
        }
        prune(nowMillis);
    }

    synchronized long remaining(UUID playerId, long nowMillis) {
        Objects.requireNonNull(playerId);
        Long current = limitedUntil.get(playerId);
        if (current == null) {
            return 0L;
        }
        if (current.longValue() <= nowMillis) {
            limitedUntil.remove(playerId, current);
            return 0L;
        }
        return current.longValue() - nowMillis;
    }

    private void prune(long nowMillis) {
        if (limitedUntil.size() < 512) {
            return;
        }
        limitedUntil.values().removeIf(until -> until.longValue() <= nowMillis);
    }
}
