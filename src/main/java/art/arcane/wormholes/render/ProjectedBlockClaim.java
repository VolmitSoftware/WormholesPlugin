package art.arcane.wormholes.render;

import org.bukkit.block.data.BlockData;

import art.arcane.wormholes.render.view.ProjectionWorldView;

final class ProjectedBlockClaim {
    static final long NO_REMOTE_KEY = Long.MIN_VALUE;

    private final BlockData data;
    private final ProjectionWorldView lightView;
    private final long lightRemoteKey;
    private final boolean maskAir;

    ProjectedBlockClaim(BlockData data, ProjectionWorldView lightView, long lightRemoteKey, boolean maskAir) {
        this.data = data;
        this.lightView = lightView;
        this.lightRemoteKey = lightRemoteKey;
        this.maskAir = maskAir;
    }

    BlockData getData() {
        return data;
    }

    ProjectionWorldView getLightView() {
        return lightView;
    }

    long getLightRemoteKey() {
        return lightRemoteKey;
    }

    boolean isMaskAir() {
        return maskAir;
    }

    boolean hasRemoteLight() {
        return lightView != null && lightRemoteKey != NO_REMOTE_KEY;
    }

    boolean sameLightSource(ProjectedBlockClaim other) {
        if (other == null) {
            return false;
        }
        if (lightRemoteKey != other.lightRemoteKey) {
            return false;
        }
        if (lightView == null) {
            return other.lightView == null;
        }
        return lightView.equals(other.lightView);
    }
}
