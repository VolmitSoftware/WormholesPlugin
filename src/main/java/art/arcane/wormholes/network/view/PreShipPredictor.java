package art.arcane.wormholes.network.view;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

public final class PreShipPredictor {
    public record Stats(long opened, long promoted, long cancelled, int active) {
    }

    public record GatewayInfo(UUID portalId, double x, double y, double z,
                              double normalX, double normalY, double normalZ) {
    }

    public record PlayerPose(UUID playerId, String subscriberId,
                             double x, double y, double z,
                             double velocityX, double velocityY, double velocityZ) {
    }

    public record Settings(boolean enabled, double distance, double minSpeed, double rateFraction, double cancelGraceSeconds) {
        public static Settings disabled() {
            return new Settings(false, 24.0D, 0.1D, 0.25D, 2.0D);
        }
    }

    @FunctionalInterface
    public interface GatewayAccessor {
        List<GatewayInfo> nearbyGateways(double x, double z, double radius);
    }

    public static final class PreShipTicket {
        private final UUID playerId;
        private final String subscriberId;
        private final UUID portalId;
        private final long openedAtMillis;
        private volatile long lastApproachingMillis;
        private volatile long lastAwayMillis;
        private volatile boolean promoted;

        private PreShipTicket(UUID playerId, String subscriberId, UUID portalId, long nowMillis) {
            this.playerId = playerId;
            this.subscriberId = subscriberId;
            this.portalId = portalId;
            this.openedAtMillis = nowMillis;
            this.lastApproachingMillis = nowMillis;
            this.lastAwayMillis = 0L;
            this.promoted = false;
        }

        public UUID getPlayerId() {
            return playerId;
        }

        public String getSubscriberId() {
            return subscriberId;
        }

        public UUID getPortalId() {
            return portalId;
        }

        public long getOpenedAtMillis() {
            return openedAtMillis;
        }

        public boolean isPromoted() {
            return promoted;
        }
    }

    private final Map<Ticket, PreShipTicket> tickets = new ConcurrentHashMap<>();
    private final AtomicLong opened = new AtomicLong();
    private final AtomicLong promoted = new AtomicLong();
    private final AtomicLong cancelled = new AtomicLong();

    public List<PreShipTicket> tick(PlayerPose pose, GatewayAccessor accessor, Settings settings, long nowMillis) {
        if (settings == null || !settings.enabled() || pose == null || accessor == null) {
            return List.of();
        }
        double radius = Math.max(0.0D, settings.distance());
        List<GatewayInfo> nearby = accessor.nearbyGateways(pose.x(), pose.z(), radius);
        List<PreShipTicket> openedOrUpdated = new ArrayList<>(nearby.size());
        for (GatewayInfo gateway : nearby) {
            double dx = gateway.x() - pose.x();
            double dy = gateway.y() - pose.y();
            double dz = gateway.z() - pose.z();
            double distanceSquared = (dx * dx) + (dy * dy) + (dz * dz);
            if (distanceSquared > radius * radius) {
                continue;
            }
            double velocityDot = (pose.velocityX() * gateway.normalX())
                + (pose.velocityY() * gateway.normalY())
                + (pose.velocityZ() * gateway.normalZ());
            double speed = Math.sqrt((pose.velocityX() * pose.velocityX())
                + (pose.velocityY() * pose.velocityY())
                + (pose.velocityZ() * pose.velocityZ()));
            Ticket key = new Ticket(pose.subscriberId(), gateway.portalId());
            PreShipTicket existing = tickets.get(key);
            boolean approaching = velocityDot >= settings.minSpeed() && speed >= settings.minSpeed();
            if (approaching) {
                if (existing == null) {
                    PreShipTicket fresh = new PreShipTicket(pose.playerId(), pose.subscriberId(), gateway.portalId(), nowMillis);
                    tickets.put(key, fresh);
                    opened.incrementAndGet();
                    openedOrUpdated.add(fresh);
                } else {
                    existing.lastApproachingMillis = nowMillis;
                    existing.lastAwayMillis = 0L;
                    openedOrUpdated.add(existing);
                }
            } else {
                if (existing == null) {
                    continue;
                }
                if (existing.lastAwayMillis == 0L) {
                    existing.lastAwayMillis = nowMillis;
                }
            }
        }
        return openedOrUpdated;
    }

    public List<PreShipTicket> sweepCanceled(Settings settings, long nowMillis) {
        if (settings == null) {
            return List.of();
        }
        long graceMillis = Math.max(0L, (long) (settings.cancelGraceSeconds() * 1000.0D));
        List<PreShipTicket> canceled = new ArrayList<>();
        Iterator<Map.Entry<Ticket, PreShipTicket>> iterator = tickets.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<Ticket, PreShipTicket> entry = iterator.next();
            PreShipTicket ticket = entry.getValue();
            if (ticket.lastAwayMillis == 0L) {
                continue;
            }
            if (nowMillis - ticket.lastAwayMillis >= graceMillis) {
                iterator.remove();
                cancelled.incrementAndGet();
                canceled.add(ticket);
            }
        }
        return canceled;
    }

    public PreShipTicket promote(String subscriberId, UUID portalId) {
        PreShipTicket ticket = tickets.get(new Ticket(subscriberId, portalId));
        if (ticket != null && !ticket.promoted) {
            ticket.promoted = true;
            promoted.incrementAndGet();
        }
        return ticket;
    }

    public PreShipTicket cancel(String subscriberId, UUID portalId) {
        PreShipTicket removed = tickets.remove(new Ticket(subscriberId, portalId));
        if (removed != null) {
            cancelled.incrementAndGet();
        }
        return removed;
    }

    public Stats snapshot() {
        return new Stats(opened.get(), promoted.get(), cancelled.get(), tickets.size());
    }

    public void resetStats() {
        opened.set(0L);
        promoted.set(0L);
        cancelled.set(0L);
    }

    public boolean isPreShipping(String subscriberId, UUID portalId) {
        return tickets.containsKey(new Ticket(subscriberId, portalId));
    }

    public List<PreShipTicket> activeTickets() {
        return new ArrayList<>(tickets.values());
    }

    public int activeTicketCount() {
        return tickets.size();
    }

    public void clearSubscriber(String subscriberId) {
        tickets.keySet().removeIf(key -> key.subscriberId().equals(subscriberId));
    }

    public void clearAll() {
        tickets.clear();
    }

    private record Ticket(String subscriberId, UUID portalId) {
    }
}
