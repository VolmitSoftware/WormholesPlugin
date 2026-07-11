package art.arcane.wormholes.door;

import java.util.Objects;
import java.util.UUID;

/** Durable fallback for leaving a pocket after its source door moves or unloads. */
public record ReturnTicket(
    UUID playerId,
    UUID sourceEndpointId,
    UUID sourceWorldId,
    String sourceWorldName,
    double x,
    double y,
    double z,
    float yaw,
    float pitch
) {
    public ReturnTicket {
        Objects.requireNonNull(playerId, "playerId");
        Objects.requireNonNull(sourceEndpointId, "sourceEndpointId");
        Objects.requireNonNull(sourceWorldId, "sourceWorldId");
        sourceWorldName = Objects.requireNonNull(sourceWorldName, "sourceWorldName").trim();
        if (sourceWorldName.isEmpty()) {
            throw new IllegalArgumentException("sourceWorldName cannot be blank");
        }
        requireFinite(x, "x");
        requireFinite(y, "y");
        requireFinite(z, "z");
        requireFinite(yaw, "yaw");
        requireFinite(pitch, "pitch");
    }

    private static void requireFinite(double value, String name) {
        if (!Double.isFinite(value)) {
            throw new IllegalArgumentException(name + " must be finite");
        }
    }
}
