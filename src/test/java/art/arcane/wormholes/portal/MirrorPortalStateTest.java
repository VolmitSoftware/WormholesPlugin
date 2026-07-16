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
    public void mirrorAndProjectionToggleRemainIndependent() {
        LocalPortal portal = portal(PortalType.WORMHOLE);
        portal.setOutgoingTraversalsEnabled(true);
        portal.setIncomingTraversalsEnabled(true);

        portal.setMirrorMode(true);

        assertTrue(portal.isMirrorMode());
        assertEquals(ProjectionMode.ON, portal.getProjectionMode());
        assertTrue(portal.isProjecting());
        assertFalse(portal.canDepart(null));
        assertFalse(portal.canArrive(null));

        portal.setProjectionMode(ProjectionMode.OFF);
        assertTrue(portal.isMirrorMode());
        assertFalse(portal.isProjecting());
        assertFalse(portal.canDepart(null));
        assertFalse(portal.canArrive(null));

        portal.setMirrorMode(false);
        assertFalse(portal.isMirrorMode());
        assertEquals(ProjectionMode.OFF, portal.getProjectionMode());
        assertTrue(portal.canDepart(null));
        assertTrue(portal.canArrive(null));
    }

    @Test
    public void mirrorOnlyPortalClosesWhenProjectionOrMirrorIsDisabled() {
        LocalPortal portal = portal(PortalType.WORMHOLE);
        portal.setAmbientAttended(false);
        portal.setMirrorMode(true);

        portal.update();
        assertTrue(portal.isOpen());

        portal.setProjectionMode(ProjectionMode.OFF);
        portal.update();
        assertFalse(portal.isOpen());

        portal.setProjectionMode(ProjectionMode.ON);
        portal.update();
        assertTrue(portal.isOpen());

        portal.setMirrorMode(false);
        portal.update();
        assertFalse(portal.isOpen());
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
    public void everyPortalTypeAcceptsIndependentMirrorMode() {
        for(PortalType type : PortalType.values()) {
            LocalPortal portal = portal(type);
            portal.setMirrorMode(true);
            assertTrue(portal.isMirrorMode());
            assertEquals(type, portal.getType());
            assertEquals(ProjectionMode.ON, portal.getProjectionMode());
        }
    }

    @Test
    public void legacyProjectionStatesMigrateToIndependentFields() {
        JSONObject legacyMirror = new JSONObject();
        legacyMirror.put("projectionMode", "MIRROR");
        assertEquals(ProjectionMode.ON, LocalPortal.resolveProjectionMode(legacyMirror));
        assertTrue(LocalPortal.resolveMirrorMode(legacyMirror));

        JSONObject legacyOneWay = new JSONObject();
        legacyOneWay.put("projectionMode", "ONE_WAY");
        assertEquals(ProjectionMode.ON, LocalPortal.resolveProjectionMode(legacyOneWay));
        assertFalse(LocalPortal.resolveMirrorMode(legacyOneWay));

        JSONObject legacyOff = new JSONObject();
        legacyOff.put("projectionMode", "OFF");
        assertEquals(ProjectionMode.OFF, LocalPortal.resolveProjectionMode(legacyOff));
        assertFalse(LocalPortal.resolveMirrorMode(legacyOff));

        JSONObject explicitMirror = new JSONObject();
        explicitMirror.put("projectionMode", "OFF");
        explicitMirror.put("mirrorMode", true);
        assertEquals(ProjectionMode.OFF, LocalPortal.resolveProjectionMode(explicitMirror));
        assertTrue(LocalPortal.resolveMirrorMode(explicitMirror));

        JSONObject explicitNormal = new JSONObject();
        explicitNormal.put("projectionMode", "MIRROR");
        explicitNormal.put("mirrorMode", false);
        assertEquals(ProjectionMode.ON, LocalPortal.resolveProjectionMode(explicitNormal));
        assertFalse(LocalPortal.resolveMirrorMode(explicitNormal));
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
