package art.arcane.wormholes.render;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.bukkit.util.Vector;
import org.junit.jupiter.api.Test;

import art.arcane.wormholes.portal.PortalFrame;
import art.arcane.wormholes.portal.MirrorRotation;
import art.arcane.wormholes.util.Direction;

public final class PortalMirrorTransformTest {
    private static final double EPSILON = 1.0E-12D;

    @Test
    public void sourceAndDisplayTransformsRoundTripForEveryFrameAndRotation() {
        Vector origin = new Vector(13.25D, -7.5D, 42.75D);
        Vector point = new Vector(18.5D, 4.25D, 31.125D);
        Vector vector = new Vector(2.5D, -3.75D, 7.125D);
        double[] displayed = new double[3];
        double[] restored = new double[3];

        for(Direction normal : Direction.values()) {
            PortalFrame frame = PortalFrame.canonical(normal);
            for(int roll = 0; roll < 4; roll++) {
                for(int rotation = 0; rotation < 4; rotation++) {
                    PortalCoordMap.mirrorSourceToDisplayPointInto(point.getX(), point.getY(), point.getZ(),
                        origin.getX(), origin.getY(), origin.getZ(), frame, rotation, displayed);
                    PortalCoordMap.mirrorDisplayToSourcePointInto(displayed[0], displayed[1], displayed[2],
                        origin.getX(), origin.getY(), origin.getZ(), frame, rotation, restored);
                    assertVector(point, restored);

                    PortalCoordMap.mirrorSourceToDisplayVectorInto(vector.getX(), vector.getY(), vector.getZ(),
                        frame, rotation, displayed);
                    PortalCoordMap.mirrorDisplayToSourceVectorInto(displayed[0], displayed[1], displayed[2],
                        frame, rotation, restored);
                    assertVector(vector, restored);
                }
                frame = frame.rotateClockwise();
            }
        }
    }

    @Test
    public void quarterTurnsRotateImageClockwiseAndReflectOnlyNormal() {
        PortalFrame frame = PortalFrame.canonical(Direction.N);
        Vector source = compose(frame, 2.0D, 3.0D, 4.0D);
        double[] out = new double[3];

        PortalCoordMap.mirrorSourceToDisplayVectorInto(source.getX(), source.getY(), source.getZ(), frame, 0, out);
        assertComponents(frame, out, 2.0D, 3.0D, -4.0D);
        PortalCoordMap.mirrorSourceToDisplayVectorInto(source.getX(), source.getY(), source.getZ(), frame, 1, out);
        assertComponents(frame, out, 3.0D, -2.0D, -4.0D);
        PortalCoordMap.mirrorSourceToDisplayVectorInto(source.getX(), source.getY(), source.getZ(), frame, 2, out);
        assertComponents(frame, out, -2.0D, -3.0D, -4.0D);
        PortalCoordMap.mirrorSourceToDisplayVectorInto(source.getX(), source.getY(), source.getZ(), frame, 3, out);
        assertComponents(frame, out, -3.0D, 2.0D, -4.0D);
    }

    @Test
    public void unrotatedReflectionIsIndependentOfFrameRoll() {
        PortalFrame frame = PortalFrame.canonical(Direction.N);
        double[] expected = new double[3];
        double[] actual = new double[3];
        PortalCoordMap.mirrorSourceToDisplayVectorInto(2.25D, -4.5D, 8.75D, frame, 0, expected);

        for(int roll = 0; roll < 4; roll++) {
            PortalCoordMap.mirrorSourceToDisplayVectorInto(2.25D, -4.5D, 8.75D, frame, 0, actual);
            assertVector(new Vector(expected[0], expected[1], expected[2]), actual);
            frame = frame.rotateClockwise();
        }
    }

    @Test
    public void mirroredBlockDirectionsFollowImageRotation() {
        PortalFrame frame = PortalFrame.canonical(Direction.N);
        double[] scratch = new double[3];

        assertEquals(Direction.E, ProjectedBlockDataTransformer.mirrorDirection(Direction.E, frame, 0, scratch));
        assertEquals(Direction.S, ProjectedBlockDataTransformer.mirrorDirection(Direction.N, frame, 0, scratch));
        assertEquals(Direction.N, ProjectedBlockDataTransformer.mirrorDirection(Direction.S, frame, 0, scratch));
        assertEquals(Direction.E, ProjectedBlockDataTransformer.mirrorDirection(Direction.U, frame, 1, scratch));
        assertEquals(Direction.D, ProjectedBlockDataTransformer.mirrorDirection(Direction.E, frame, 1, scratch));
    }

    @Test
    public void imageHalfTurnControlsUpsideDownEntityState() {
        PortalFrame wall = PortalFrame.canonical(Direction.N);
        assertFalse(PortalCoordMap.mirrorTransformFlipsWorldUp(wall, 0));
        assertTrue(PortalCoordMap.mirrorTransformFlipsWorldUp(wall, 2));
        assertTrue(PortalCoordMap.mirrorTransformFlipsWorldUp(PortalFrame.canonical(Direction.U), 0));
    }

    @Test
    public void coherentRotationPolicyOnlyOffersWorldUpRepresentableEntityStates() {
        PortalFrame wall = PortalFrame.canonical(Direction.N);
        PortalFrame floor = PortalFrame.canonical(Direction.U);
        double[] out = new double[3];
        for(MirrorRotation rotation : MirrorRotation.values()) {
            PortalCoordMap.mirrorSourceToDisplayVectorInto(0.0D, 1.0D, 0.0D, wall, rotation.getQuarterTurns(), out);
            boolean wallRepresentable = Math.abs(out[1]) > 0.5D;
            assertEquals(wallRepresentable, rotation == rotation.coherentFor(wall));

            PortalCoordMap.mirrorSourceToDisplayVectorInto(0.0D, 1.0D, 0.0D, floor, rotation.getQuarterTurns(), out);
            assertTrue(Math.abs(out[1]) > 0.5D);
            assertEquals(rotation, rotation.coherentFor(floor));
        }
    }

    private static Vector compose(PortalFrame frame, double right, double up, double normal) {
        return frame.getRight().toVector().multiply(right)
            .add(frame.getUp().toVector().multiply(up))
            .add(frame.getNormal().toVector().multiply(normal));
    }

    private static void assertComponents(PortalFrame frame, double[] actual, double right, double up, double normal) {
        assertEquals(right, dot(actual, frame.getRight()), EPSILON);
        assertEquals(up, dot(actual, frame.getUp()), EPSILON);
        assertEquals(normal, dot(actual, frame.getNormal()), EPSILON);
    }

    private static double dot(double[] vector, Direction direction) {
        return (vector[0] * direction.x()) + (vector[1] * direction.y()) + (vector[2] * direction.z());
    }

    private static void assertVector(Vector expected, double[] actual) {
        assertEquals(expected.getX(), actual[0], EPSILON);
        assertEquals(expected.getY(), actual[1], EPSILON);
        assertEquals(expected.getZ(), actual[2], EPSILON);
    }
}
