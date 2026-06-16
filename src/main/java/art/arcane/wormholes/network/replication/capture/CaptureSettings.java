package art.arcane.wormholes.network.replication.capture;

import art.arcane.wormholes.config.toml.NetworkConfig;

public record CaptureSettings(int snapshotIntervalTicks, int maxQueuedDiffsPerChunk, boolean lightCaptureEnabled, boolean blockEntityCaptureEnabled) {
    public static CaptureSettings defaults() {
        return new CaptureSettings(100, 256, true, true);
    }

    public static CaptureSettings from(NetworkConfig config) {
        if (config == null || config.replication == null) {
            return defaults();
        }
        NetworkConfig.ReplicationConfig source = config.replication;
        int interval = Math.max(20, source.captureSnapshotIntervalTicks);
        int cap = Math.max(16, source.captureMaxQueuedDiffsPerChunk);
        return new CaptureSettings(interval, cap, source.captureLightEnabled, source.captureBlockEntityEnabled);
    }
}
