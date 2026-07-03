package art.arcane.wormholes.network.replication.capture;

import art.arcane.wormholes.network.replication.LightDiff;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

public final class ChunkLightShadow {
    private final Map<Integer, byte[]> blockLightBySection = new HashMap<>(4);
    private final Map<Integer, byte[]> skyLightBySection = new HashMap<>(4);
    private final Set<Integer> pendingSections = new HashSet<>(4);
    private final Map<Integer, int[]> emitCountersBySection = new HashMap<>(4);
    private long lastSampleMillis;
    private boolean sampledEver;

    public synchronized byte[] getBlockLight(int sectionY) {
        return blockLightBySection.get(sectionY);
    }

    public synchronized byte[] getSkyLight(int sectionY) {
        return skyLightBySection.get(sectionY);
    }

    public synchronized void putBlockLight(int sectionY, byte[] data) {
        blockLightBySection.put(sectionY, data);
    }

    public synchronized void putSkyLight(int sectionY, byte[] data) {
        skyLightBySection.put(sectionY, data);
    }

    public synchronized int nextEmitCounter(int sectionY, byte lightType) {
        int[] counters = emitCountersBySection.computeIfAbsent(sectionY, ignored -> new int[2]);
        int slot = lightType == LightDiff.TYPE_SKYLIGHT ? 1 : 0;
        int value = counters[slot];
        counters[slot] = (value + 1) & 0xF;
        return value;
    }

    public synchronized boolean tryBeginSample(long nowMillis, long cooldownMillis) {
        if (sampledEver && nowMillis - lastSampleMillis < cooldownMillis) {
            return false;
        }
        sampledEver = true;
        lastSampleMillis = nowMillis;
        return true;
    }

    public synchronized void deferSections(Set<Integer> sections) {
        pendingSections.addAll(sections);
    }

    public synchronized Set<Integer> drainPendingSections() {
        if (pendingSections.isEmpty()) {
            return Set.of();
        }
        Set<Integer> drained = new HashSet<>(pendingSections);
        pendingSections.clear();
        return drained;
    }

    public synchronized boolean hasPending() {
        return !pendingSections.isEmpty();
    }

    public synchronized void clear() {
        blockLightBySection.clear();
        skyLightBySection.clear();
        pendingSections.clear();
        emitCountersBySection.clear();
    }
}
