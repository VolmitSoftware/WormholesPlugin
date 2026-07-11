package art.arcane.wormholes.door;

/** Safe player-feet position and view direction inside a pocket. */
public record PocketEntryCoordinates(double x, double y, double z, float yaw, float pitch) {
    public PocketEntryCoordinates {
        if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)
            || !Float.isFinite(yaw) || !Float.isFinite(pitch)) {
            throw new IllegalArgumentException("entry coordinates must be finite");
        }
    }
}
