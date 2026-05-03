package art.arcane.wormholes.render;

import org.bukkit.World;
import org.bukkit.block.data.BlockData;

final class ProjectedBlockClaim {
    static final long NO_REMOTE_KEY = Long.MIN_VALUE;

    private final BlockData data;
    private final World lightWorld;
    private final long lightRemoteKey;
    private final boolean maskAir;

    ProjectedBlockClaim(BlockData data, World lightWorld, long lightRemoteKey, boolean maskAir) {
        this.data = data;
        this.lightWorld = lightWorld;
        this.lightRemoteKey = lightRemoteKey;
        this.maskAir = maskAir;
    }

    BlockData getData() {
        return data;
    }

    World getLightWorld() {
        return lightWorld;
    }

    long getLightRemoteKey() {
        return lightRemoteKey;
    }

    boolean isMaskAir() {
        return maskAir;
    }

    boolean hasRemoteLight() {
        return lightWorld != null && lightRemoteKey != NO_REMOTE_KEY;
    }

    boolean sameLightSource(ProjectedBlockClaim other) {
        if (other == null) {
            return false;
        }
        if (lightRemoteKey != other.lightRemoteKey) {
            return false;
        }
        if (lightWorld == null) {
            return other.lightWorld == null;
        }
        return lightWorld.equals(other.lightWorld);
    }
}
