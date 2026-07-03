package art.arcane.wormholes.portal;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.HashMap;
import java.util.Map;

import org.bukkit.Location;
import org.bukkit.util.Vector;
import org.junit.jupiter.api.Test;

import art.arcane.wormholes.util.Cuboid;
import art.arcane.wormholes.util.Direction;

public final class PortalStructureCenterTest {
    private static final double EPSILON = 1e-9D;

    @Test
    public void getCenterReturnsExpectedMidpointAfterSetArea() {
        PortalStructure structure = new PortalStructure();
        structure.setArea(cuboid(0, 64, 0, 3, 68, 1));

        Location center = structure.getCenter();

        assertEquals(1.9995D, center.getX(), EPSILON);
        assertEquals(66.4995D, center.getY(), EPSILON);
        assertEquals(0.9995D, center.getZ(), EPSILON);
    }

    @Test
    public void getCenterReturnsDefensiveClone() {
        PortalStructure structure = new PortalStructure();
        structure.setArea(cuboid(0, 64, 0, 3, 68, 1));

        Location first = structure.getCenter();
        first.add(100.0D, 100.0D, 100.0D);
        Location second = structure.getCenter();

        assertEquals(1.9995D, second.getX(), EPSILON);
        assertEquals(66.4995D, second.getY(), EPSILON);
        assertEquals(0.9995D, second.getZ(), EPSILON);
    }

    @Test
    public void setAreaInvalidatesCenterCache() {
        PortalStructure structure = new PortalStructure();
        structure.setArea(cuboid(0, 64, 0, 3, 68, 1));
        structure.getCenter();

        structure.setArea(cuboid(10, 10, 10, 10, 12, 10));
        Location center = structure.getCenter();

        assertEquals(10.4995D, center.getX(), EPSILON);
        assertEquals(11.4995D, center.getY(), EPSILON);
        assertEquals(10.4995D, center.getZ(), EPSILON);
    }

    private static Cuboid cuboid(int x1, int y1, int z1, int x2, int y2, int z2) {
        Map<String, Object> map = new HashMap<String, Object>();
        map.put("worldName", "world");
        map.put("x1", Integer.valueOf(x1));
        map.put("y1", Integer.valueOf(y1));
        map.put("z1", Integer.valueOf(z1));
        map.put("x2", Integer.valueOf(x2));
        map.put("y2", Integer.valueOf(y2));
        map.put("z2", Integer.valueOf(z2));
        return new AssertSafeCuboid(map);
    }

    private static final class AssertSafeCuboid extends Cuboid {
        private AssertSafeCuboid(Map<String, Object> map) {
            super(map);
        }

        @Override
        public Vector getCornerVector(Direction x, Direction y, Direction z) {
            double s = 0.999D;
            return new Vector(x.x() == 1 ? (x2 + s) : x1, y.y() == 1 ? (y2 + s) : y1, z.z() == 1 ? (z2 + s) : z1);
        }
    }
}
