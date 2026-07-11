package art.arcane.wormholes.render;

import java.util.ArrayList;
import java.util.Set;

import org.bukkit.Axis;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Directional;
import org.bukkit.block.data.MultipleFacing;
import org.bukkit.block.data.Orientable;
import org.bukkit.block.data.Rail;
import org.bukkit.block.data.Rotatable;

import art.arcane.wormholes.portal.PortalFrame;
import art.arcane.wormholes.util.Direction;

public final class ProjectedBlockDataTransformer {
    private ProjectedBlockDataTransformer() {
    }

    public static BlockData transform(BlockData source, PortalFrame fromFrame, PortalFrame toFrame, double[] scratch3) {
        return transform(source, DirectionMapping.between(fromFrame, toFrame, scratch3));
    }

    public static BlockData mirror(BlockData source, PortalFrame frame, int quarterTurns, double[] scratch3) {
        return transform(source, DirectionMapping.mirror(frame, quarterTurns, scratch3));
    }

    private static BlockData transform(BlockData source, DirectionMapping mapping) {
        BlockData copy = source.clone();
        transformDirectional(copy, mapping);
        transformRotatable(copy, mapping);
        transformOrientable(copy, mapping);
        transformMultipleFacing(copy, mapping);
        transformRail(copy, mapping);
        return copy;
    }

    public static boolean requiresTransform(BlockData source) {
        return source instanceof Directional
            || source instanceof Rotatable
            || source instanceof Orientable
            || source instanceof MultipleFacing
            || source instanceof Rail;
    }

    private static void transformDirectional(BlockData data, DirectionMapping mapping) {
        if (!(data instanceof Directional)) {
            return;
        }
        Directional directional = (Directional) data;
        Direction source = fromBlockFace(directional.getFacing());
        if (source == null) {
            return;
        }
        Direction target = mapping.map(source);
        BlockFace targetFace = toBlockFace(target);
        Set<BlockFace> faces = directional.getFaces();
        if (targetFace != null && faces.contains(targetFace)) {
            directional.setFacing(targetFace);
        }
    }

    private static void transformRotatable(BlockData data, DirectionMapping mapping) {
        if (!(data instanceof Rotatable)) {
            return;
        }
        Rotatable rotatable = (Rotatable) data;
        Direction source = fromBlockFace(rotatable.getRotation());
        if (source == null) {
            return;
        }
        Direction target = mapping.map(source);
        BlockFace targetFace = toBlockFace(target);
        if (targetFace == null || targetFace == BlockFace.UP || targetFace == BlockFace.DOWN) {
            return;
        }
        rotatable.setRotation(targetFace);
    }

    private static void transformOrientable(BlockData data, DirectionMapping mapping) {
        if (!(data instanceof Orientable)) {
            return;
        }
        Orientable orientable = (Orientable) data;
        Direction source = directionForAxis(orientable.getAxis());
        Direction target = mapping.map(source);
        Axis axis = axisForDirection(target);
        if (orientable.getAxes().contains(axis)) {
            orientable.setAxis(axis);
        }
    }

    private static void transformMultipleFacing(BlockData data, DirectionMapping mapping) {
        if (!(data instanceof MultipleFacing)) {
            return;
        }
        MultipleFacing multiple = (MultipleFacing) data;
        ArrayList<BlockFace> enabled = new ArrayList<BlockFace>(multiple.getFaces());
        for (BlockFace face : multiple.getAllowedFaces()) {
            multiple.setFace(face, false);
        }
        for (BlockFace face : enabled) {
            Direction source = fromBlockFace(face);
            if (source == null) {
                continue;
            }
            Direction target = mapping.map(source);
            BlockFace targetFace = toBlockFace(target);
            if (targetFace != null && multiple.getAllowedFaces().contains(targetFace)) {
                multiple.setFace(targetFace, true);
            }
        }
    }

    private static void transformRail(BlockData data, DirectionMapping mapping) {
        if (!(data instanceof Rail)) {
            return;
        }
        Rail rail = (Rail) data;
        Rail.Shape transformed = rotateRailShape(rail.getShape(), mapping);
        if (transformed != null && rail.getShapes().contains(transformed)) {
            rail.setShape(transformed);
        }
    }

    private static Rail.Shape rotateRailShape(Rail.Shape shape, DirectionMapping mapping) {
        switch(shape) {
            case NORTH_SOUTH:
                return straightRailShape(rotateHorizontal(Direction.N, mapping));
            case EAST_WEST:
                return straightRailShape(rotateHorizontal(Direction.E, mapping));
            case ASCENDING_NORTH:
                return ascendingRailShape(rotateHorizontal(Direction.N, mapping), shape);
            case ASCENDING_SOUTH:
                return ascendingRailShape(rotateHorizontal(Direction.S, mapping), shape);
            case ASCENDING_EAST:
                return ascendingRailShape(rotateHorizontal(Direction.E, mapping), shape);
            case ASCENDING_WEST:
                return ascendingRailShape(rotateHorizontal(Direction.W, mapping), shape);
            case SOUTH_EAST:
                return curvedRailShape(rotateHorizontal(Direction.S, mapping), rotateHorizontal(Direction.E, mapping));
            case SOUTH_WEST:
                return curvedRailShape(rotateHorizontal(Direction.S, mapping), rotateHorizontal(Direction.W, mapping));
            case NORTH_WEST:
                return curvedRailShape(rotateHorizontal(Direction.N, mapping), rotateHorizontal(Direction.W, mapping));
            case NORTH_EAST:
                return curvedRailShape(rotateHorizontal(Direction.N, mapping), rotateHorizontal(Direction.E, mapping));
            default:
                return null;
        }
    }

    private static BlockFace rotateHorizontal(Direction source, DirectionMapping mapping) {
        BlockFace face = toBlockFace(mapping.map(source));
        if (face == BlockFace.NORTH || face == BlockFace.SOUTH || face == BlockFace.EAST || face == BlockFace.WEST) {
            return face;
        }
        return null;
    }

    private static Rail.Shape straightRailShape(BlockFace face) {
        if (face == BlockFace.NORTH || face == BlockFace.SOUTH) {
            return Rail.Shape.NORTH_SOUTH;
        }
        if (face == BlockFace.EAST || face == BlockFace.WEST) {
            return Rail.Shape.EAST_WEST;
        }
        return null;
    }

    private static Rail.Shape ascendingRailShape(BlockFace face, Rail.Shape fallback) {
        if (face == null) {
            return fallback;
        }
        switch(face) {
            case NORTH:
                return Rail.Shape.ASCENDING_NORTH;
            case SOUTH:
                return Rail.Shape.ASCENDING_SOUTH;
            case EAST:
                return Rail.Shape.ASCENDING_EAST;
            case WEST:
                return Rail.Shape.ASCENDING_WEST;
            default:
                return fallback;
        }
    }

    private static Rail.Shape curvedRailShape(BlockFace a, BlockFace b) {
        if (a == null || b == null) {
            return null;
        }
        boolean north = a == BlockFace.NORTH || b == BlockFace.NORTH;
        boolean south = a == BlockFace.SOUTH || b == BlockFace.SOUTH;
        boolean east = a == BlockFace.EAST || b == BlockFace.EAST;
        boolean west = a == BlockFace.WEST || b == BlockFace.WEST;
        if (south && east) {
            return Rail.Shape.SOUTH_EAST;
        }
        if (south && west) {
            return Rail.Shape.SOUTH_WEST;
        }
        if (north && west) {
            return Rail.Shape.NORTH_WEST;
        }
        if (north && east) {
            return Rail.Shape.NORTH_EAST;
        }
        return null;
    }

    static Direction mirrorDirection(Direction source, PortalFrame frame, int quarterTurns, double[] scratch3) {
        PortalCoordMap.mirrorSourceToDisplayVectorInto(source.x(), source.y(), source.z(), frame, quarterTurns, scratch3);
        return Direction.closest(scratch3[0], scratch3[1], scratch3[2]);
    }

    private static final class DirectionMapping {
        private final PortalFrame fromFrame;
        private final PortalFrame toFrame;
        private final PortalFrame mirrorFrame;
        private final int quarterTurns;
        private final double[] scratch3;

        private DirectionMapping(PortalFrame fromFrame, PortalFrame toFrame, PortalFrame mirrorFrame, int quarterTurns, double[] scratch3) {
            this.fromFrame = fromFrame;
            this.toFrame = toFrame;
            this.mirrorFrame = mirrorFrame;
            this.quarterTurns = quarterTurns;
            this.scratch3 = scratch3;
        }

        private static DirectionMapping between(PortalFrame fromFrame, PortalFrame toFrame, double[] scratch3) {
            return new DirectionMapping(fromFrame, toFrame, null, 0, scratch3);
        }

        private static DirectionMapping mirror(PortalFrame frame, int quarterTurns, double[] scratch3) {
            return new DirectionMapping(null, null, frame, quarterTurns, scratch3);
        }

        private Direction map(Direction source) {
            if(mirrorFrame != null) {
                return mirrorDirection(source, mirrorFrame, quarterTurns, scratch3);
            }
            return fromFrame.transformDirection(source, toFrame, scratch3);
        }
    }

    private static Direction directionForAxis(Axis axis) {
        switch(axis) {
            case X:
                return Direction.E;
            case Y:
                return Direction.U;
            case Z:
            default:
                return Direction.S;
        }
    }

    private static Axis axisForDirection(Direction direction) {
        switch(direction.getAxis()) {
            case X:
                return Axis.X;
            case Y:
                return Axis.Y;
            case Z:
            default:
                return Axis.Z;
        }
    }

    private static Direction fromBlockFace(BlockFace face) {
        switch(face) {
            case NORTH:
                return Direction.N;
            case SOUTH:
                return Direction.S;
            case EAST:
                return Direction.E;
            case WEST:
                return Direction.W;
            case UP:
                return Direction.U;
            case DOWN:
                return Direction.D;
            default:
                return null;
        }
    }

    private static BlockFace toBlockFace(Direction direction) {
        switch(direction) {
            case N:
                return BlockFace.NORTH;
            case S:
                return BlockFace.SOUTH;
            case E:
                return BlockFace.EAST;
            case W:
                return BlockFace.WEST;
            case U:
                return BlockFace.UP;
            case D:
                return BlockFace.DOWN;
            default:
                return null;
        }
    }
}
