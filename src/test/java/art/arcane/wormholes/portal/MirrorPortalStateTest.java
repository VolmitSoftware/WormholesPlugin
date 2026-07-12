package art.arcane.wormholes.portal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import art.arcane.wormholes.util.Cuboid;
import art.arcane.wormholes.util.Direction;
import art.arcane.wormholes.util.JSONObject;

public final class MirrorPortalStateTest {
    @Test
    public void mirrorCategoricallyRejectsDepartureAndArrival() {
        LocalPortal portal = portal(PortalType.WORMHOLE);
        portal.setOutgoingTraversalsEnabled(true);
        portal.setIncomingTraversalsEnabled(true);

        portal.setProjectionMode(ProjectionMode.MIRROR);

        assertFalse(portal.canDepart(null));
        assertFalse(portal.canArrive(null));

        portal.setProjectionMode(ProjectionMode.ON);
        assertTrue(portal.canDepart(null));
        assertTrue(portal.canArrive(null));
    }

    @Test
    public void mirrorRotationUsesOnlyEntityCoherentStepsForPortalPlane() {
        LocalPortal portal = portal(PortalType.PORTAL);
        PortalFrame wall = PortalFrame.canonical(Direction.N);
        PortalFrame floor = PortalFrame.canonical(Direction.U);
        assertFalse(MirrorRotation.supportsQuarterTurns(wall));
        assertTrue(MirrorRotation.supportsQuarterTurns(floor));

        portal.setMirrorRotation(MirrorRotation.DEGREES_90);
        assertEquals(MirrorRotation.DEGREES_0, portal.getMirrorRotation());
        portal.setMirrorRotation(portal.getMirrorRotation().clockwiseFor(portal.getFrame()));
        assertEquals(MirrorRotation.DEGREES_180, portal.getMirrorRotation());
        portal.setMirrorRotation(portal.getMirrorRotation().counterClockwiseFor(portal.getFrame()));
        assertEquals(MirrorRotation.DEGREES_0, portal.getMirrorRotation());

        portal.setFrame(floor);
        portal.setMirrorRotation(MirrorRotation.DEGREES_90);
        assertEquals(MirrorRotation.DEGREES_90, portal.getMirrorRotation());
        portal.setFrame(wall);
        assertEquals(MirrorRotation.DEGREES_0, portal.getMirrorRotation());
        portal.setFrame(floor);
        portal.setMirrorRotation(MirrorRotation.DEGREES_270);
        portal.setFrame(wall);
        assertEquals(MirrorRotation.DEGREES_180, portal.getMirrorRotation());

        JSONObject stored = new JSONObject();
        stored.put("mirrorRotationDegrees", 270);
        assertEquals(MirrorRotation.DEGREES_270, LocalPortal.resolveMirrorRotation(stored));
        assertEquals(MirrorRotation.DEGREES_0, LocalPortal.resolveMirrorRotation(new JSONObject()));
        assertEquals(MirrorRotation.DEGREES_270, MirrorRotation.fromDegrees(-90));
        assertEquals(MirrorRotation.DEGREES_90, MirrorRotation.fromDegrees(450));
        assertEquals(MirrorRotation.DEGREES_0, MirrorRotation.DEGREES_270.clockwise());
        assertEquals(MirrorRotation.DEGREES_270, MirrorRotation.DEGREES_0.counterClockwise());
    }

    @Test
    public void everyPortalTypeAcceptsMirrorMode() {
        for(PortalType type : PortalType.values()) {
            LocalPortal portal = portal(type);
            portal.setProjectionMode(ProjectionMode.MIRROR);
            assertEquals(ProjectionMode.MIRROR, portal.getProjectionMode());
        }
    }

    private static LocalPortal portal(PortalType type) {
        PortalStructure structure = new PortalStructure();
        structure.setArea(cuboid());
        return new LocalPortal(UUID.randomUUID(), type, structure);
    }

    private static Cuboid cuboid() {
        Map<String, Object> map = new HashMap<String, Object>();
        map.put("worldKey", "minecraft:overworld");
        map.put("x1", Integer.valueOf(0));
        map.put("y1", Integer.valueOf(64));
        map.put("z1", Integer.valueOf(0));
        map.put("x2", Integer.valueOf(0));
        map.put("y2", Integer.valueOf(66));
        map.put("z2", Integer.valueOf(2));
        return new Cuboid(map);
    }
}
