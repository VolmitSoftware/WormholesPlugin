package art.arcane.wormholes.network.replication.capture;

import java.util.HashMap;
import java.util.Map;

public final class ChunkLightShadow {
    private final Map<Integer, byte[]> blockLightBySection = new HashMap<>(4);
    private final Map<Integer, byte[]> skyLightBySection = new HashMap<>(4);

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

    public synchronized void clear() {
        blockLightBySection.clear();
        skyLightBySection.clear();
    }
}
