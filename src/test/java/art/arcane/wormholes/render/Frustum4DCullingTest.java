package art.arcane.wormholes.render;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.bukkit.Location;
import org.junit.jupiter.api.Test;

import art.arcane.volmlib.util.collection.KList;
import art.arcane.wormholes.Settings;
import art.arcane.wormholes.portal.PortalStructure;
import art.arcane.wormholes.util.AxisAlignedBB;
import art.arcane.wormholes.util.Direction;

public final class Frustum4DCullingTest {
    @Test
    public void axialViewBuildsSingleFaceCone() {
        double previousRatio = Settings.FRUSTUM_CULLING_RATIO;
        Settings.FRUSTUM_CULLING_RATIO = 0.2D;
        try {
            Frustum4D frustum = new Frustum4D(new Location(null, 1.5D, 1.5D, 0.0D), new TestStructure(), 16.0D);

            assertEquals(1, frustum.getFaceCount());
        } finally {
            Settings.FRUSTUM_CULLING_RATIO = previousRatio;
        }
    }

    @Test
    public void diagonalViewBuildsOnlyFacesTowardObserver() {
        double previousRatio = Settings.FRUSTUM_CULLING_RATIO;
        Settings.FRUSTUM_CULLING_RATIO = 0.2D;
        try {
            Frustum4D frustum = new Frustum4D(new Location(null, 8.0D, 1.5D, 0.0D), new TestStructure(), 16.0D);

            assertEquals(2, frustum.getFaceCount());
        } finally {
            Settings.FRUSTUM_CULLING_RATIO = previousRatio;
        }
    }

    private static final class TestStructure extends PortalStructure {
        @Override
        public Location getCenter() {
            return new Location(null, 1.5D, 1.5D, 5.0D);
        }

        @Override
        public KList<AxisAlignedBB> getApertureFaces(Direction face) {
            KList<AxisAlignedBB> faces = new KList<AxisAlignedBB>();
            switch (face) {
                case E:
                    faces.add(new AxisAlignedBB(3.0D, 3.0D, 0.0D, 3.0D, 5.0D, 5.0D));
                    break;
                case W:
                    faces.add(new AxisAlignedBB(0.0D, 0.0D, 0.0D, 3.0D, 5.0D, 5.0D));
                    break;
                case U:
                    faces.add(new AxisAlignedBB(0.0D, 3.0D, 3.0D, 3.0D, 5.0D, 5.0D));
                    break;
                case D:
                    faces.add(new AxisAlignedBB(0.0D, 3.0D, 0.0D, 0.0D, 5.0D, 5.0D));
                    break;
                case S:
                    faces.add(new AxisAlignedBB(0.0D, 3.0D, 0.0D, 3.0D, 5.0D, 5.0D));
                    break;
                case N:
                    faces.add(new AxisAlignedBB(0.0D, 3.0D, 0.0D, 3.0D, 5.0D, 5.0D));
                    break;
                default:
                    break;
            }
            return faces;
        }
    }
}
