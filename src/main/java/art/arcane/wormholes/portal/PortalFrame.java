package art.arcane.wormholes.portal;

import org.bukkit.util.Vector;

import art.arcane.wormholes.util.AxisAlignedBB;
import art.arcane.wormholes.util.Direction;
import art.arcane.wormholes.util.JSONObject;

public final class PortalFrame {
	private final Direction normal;
	private final Direction right;
	private final Direction up;

	public PortalFrame(Direction normal, Direction right, Direction up) {
		this.normal = requireDirection(normal);
		this.right = requireDirection(right);
		this.up = requireDirection(up);
		if (!isPerpendicular(this.normal, this.right) || !isPerpendicular(this.normal, this.up) || !isPerpendicular(this.right, this.up)) {
			throw new IllegalArgumentException("Portal frame directions must be perpendicular");
		}
		Direction expectedRight = cross(this.normal, this.up);
		if (!expectedRight.equals(this.right)) {
			throw new IllegalArgumentException("Portal frame right must equal normal cross up");
		}
	}

	public static PortalFrame canonical(Direction normal) {
		Direction up = normal.isVertical() ? verticalFallbackUp(normal) : Direction.U;
		return fromNormalUp(normal, up);
	}

	public static PortalFrame derive(AxisAlignedBB area, Direction normal) {
		if (!normal.isVertical() || area == null) {
			return canonical(normal);
		}
		double xSpan = area.sizeX();
		double zSpan = area.sizeZ();
		if (xSpan > zSpan + 1e-6D) {
			return fromNormalUp(normal, Direction.E);
		}
		return canonical(normal);
	}

	public static PortalFrame fromDirectionAndLook(Direction normal, Vector look) {
		if (!normal.isVertical()) {
			return canonical(normal);
		}
		Direction up = verticalFallbackUp(normal);
		if (look != null) {
			double x = look.getX();
			double z = look.getZ();
			double horizontal = Math.sqrt(x * x + z * z);
			if (horizontal > 1e-6D) {
				up = Direction.closest(x, 0.0D, z);
				if (up.isVertical()) {
					up = verticalFallbackUp(normal);
				}
			}
		}
		return fromNormalUp(normal, up);
	}

	public static PortalFrame fromNormalUp(Direction normal, Direction up) {
		return new PortalFrame(normal, cross(normal, up), up);
	}

	public static PortalFrame fromJSON(Direction fallbackNormal, JSONObject json) {
		if (json == null) {
			return canonical(fallbackNormal);
		}
		Direction normal = json.has("normal") ? Direction.valueOf(json.getString("normal")) : fallbackNormal;
		Direction right = Direction.valueOf(json.getString("right"));
		Direction up = Direction.valueOf(json.getString("up"));
		return new PortalFrame(normal, right, up);
	}

	public JSONObject toJSON() {
		JSONObject json = new JSONObject();
		json.put("normal", normal.name());
		json.put("right", right.name());
		json.put("up", up.name());
		return json;
	}

	public Direction getNormal() {
		return normal;
	}

	public Direction getRight() {
		return right;
	}

	public Direction getUp() {
		return up;
	}

	public PortalFrame flipNormal() {
		return fromNormalUp(normal.reverse(), up);
	}

	public PortalFrame view(boolean frontSide) {
		return frontSide ? this : flipNormal();
	}

	public PortalFrame rotateClockwise() {
		return fromNormalUp(normal, right);
	}

	public PortalFrame rotateCounterClockwise() {
		return fromNormalUp(normal, right.reverse());
	}

	public PortalFrame withNormal(Direction newNormal) {
		Direction nextNormal = requireDirection(newNormal);
		if (isPerpendicular(nextNormal, up)) {
			return fromNormalUp(nextNormal, up);
		}
		if (isPerpendicular(nextNormal, normal)) {
			return fromNormalUp(nextNormal, normal);
		}
		return canonical(nextNormal);
	}

	public Vector transformPoint(Vector point, Vector fromOrigin, Vector toOrigin, PortalFrame to) {
		double[] out = new double[3];
		transformPointInto(point.getX(), point.getY(), point.getZ(),
			fromOrigin.getX(), fromOrigin.getY(), fromOrigin.getZ(),
			toOrigin.getX(), toOrigin.getY(), toOrigin.getZ(), to, out);
		return new Vector(out[0], out[1], out[2]);
	}

	public void transformPointInto(double x, double y, double z,
								   double fromOriginX, double fromOriginY, double fromOriginZ,
								   double toOriginX, double toOriginY, double toOriginZ,
								   PortalFrame to, double[] out3) {
		double offsetX = x - fromOriginX;
		double offsetY = y - fromOriginY;
		double offsetZ = z - fromOriginZ;
		transformVectorInto(offsetX, offsetY, offsetZ, to, out3);
		out3[0] = toOriginX + out3[0];
		out3[1] = toOriginY + out3[1];
		out3[2] = toOriginZ + out3[2];
	}

	public Vector transformVector(Vector vector, PortalFrame to) {
		double[] out = new double[3];
		transformVectorInto(vector.getX(), vector.getY(), vector.getZ(), to, out);
		return new Vector(out[0], out[1], out[2]);
	}

	public void transformVectorInto(double x, double y, double z, PortalFrame to, double[] out3) {
		double frameRight = dot(x, y, z, right);
		double frameUp = dot(x, y, z, up);
		double frameNormal = dot(x, y, z, normal);
		out3[0] = frameRight * to.right.x() + frameUp * to.up.x() + frameNormal * to.normal.x();
		out3[1] = frameRight * to.right.y() + frameUp * to.up.y() + frameNormal * to.normal.y();
		out3[2] = frameRight * to.right.z() + frameUp * to.up.z() + frameNormal * to.normal.z();
	}

	public Direction transformDirection(Direction direction, PortalFrame to, double[] scratch3) {
		transformVectorInto(direction.x(), direction.y(), direction.z(), to, scratch3);
		return Direction.closest(scratch3[0], scratch3[1], scratch3[2]);
	}

	private static Direction requireDirection(Direction direction) {
		if (direction == null) {
			throw new IllegalArgumentException("Portal frame direction cannot be null");
		}
		return direction;
	}

	private static Direction verticalFallbackUp(Direction normal) {
		return normal.equals(Direction.D) ? Direction.N : Direction.S;
	}

	private static boolean isPerpendicular(Direction a, Direction b) {
		return dot(a.x(), a.y(), a.z(), b) == 0.0D;
	}

	private static double dot(double x, double y, double z, Direction direction) {
		return x * direction.x() + y * direction.y() + z * direction.z();
	}

	private static Direction cross(Direction a, Direction b) {
		int x = a.y() * b.z() - a.z() * b.y();
		int y = a.z() * b.x() - a.x() * b.z();
		int z = a.x() * b.y() - a.y() * b.x();
		if (x == 0 && y == 0 && z == 0) {
			throw new IllegalArgumentException("Portal frame normal and up cannot be parallel");
		}
		return Direction.closest(x, y, z);
	}
}
