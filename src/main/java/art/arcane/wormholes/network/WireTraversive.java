package art.arcane.wormholes.network;

import art.arcane.wormholes.portal.PortalFrame;
import art.arcane.wormholes.portal.TraversableType;
import art.arcane.wormholes.portal.Traversive;
import art.arcane.wormholes.util.Direction;
import org.bukkit.util.Vector;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;

public record WireTraversive(
    String frameNormal,
    String frameRight,
    String frameUp,
    double originX,
    double originY,
    double originZ,
    double pointX,
    double pointY,
    double pointZ,
    double velocityX,
    double velocityY,
    double velocityZ,
    double lookX,
    double lookY,
    double lookZ,
    boolean frontSide
) {
    public static WireTraversive fromTraversive(Traversive t) {
        PortalFrame frame = t.getInFrame();
        Vector origin = t.getInOrigin();
        Vector point = t.getInPoint();
        Vector velocity = t.getInVelocity();
        Vector look = t.getInLook();
        return new WireTraversive(
            frame.getNormal().name(),
            frame.getRight().name(),
            frame.getUp().name(),
            origin.getX(), origin.getY(), origin.getZ(),
            point.getX(), point.getY(), point.getZ(),
            velocity.getX(), velocity.getY(), velocity.getZ(),
            look.getX(), look.getY(), look.getZ(),
            t.isFrontSide()
        );
    }

    public Traversive toTraversive(Object object) {
        PortalFrame frame = new PortalFrame(Direction.valueOf(frameNormal), Direction.valueOf(frameRight), Direction.valueOf(frameUp));
        return new Traversive(
            object,
            TraversableType.ENTITY,
            frame,
            new Vector(originX, originY, originZ),
            new Vector(pointX, pointY, pointZ),
            new Vector(velocityX, velocityY, velocityZ),
            new Vector(lookX, lookY, lookZ),
            frontSide
        );
    }

    public void write(DataOutputStream out) throws IOException {
        WireCodec.writeDirection(out, frameNormal);
        WireCodec.writeDirection(out, frameRight);
        WireCodec.writeDirection(out, frameUp);
        out.writeDouble(originX);
        out.writeDouble(originY);
        out.writeDouble(originZ);
        out.writeDouble(pointX);
        out.writeDouble(pointY);
        out.writeDouble(pointZ);
        out.writeDouble(velocityX);
        out.writeDouble(velocityY);
        out.writeDouble(velocityZ);
        out.writeDouble(lookX);
        out.writeDouble(lookY);
        out.writeDouble(lookZ);
        out.writeBoolean(frontSide);
    }

    public static WireTraversive read(DataInputStream in) throws IOException {
        String frameNormal = WireCodec.readDirection(in);
        String frameRight = WireCodec.readDirection(in);
        String frameUp = WireCodec.readDirection(in);
        double originX = in.readDouble();
        double originY = in.readDouble();
        double originZ = in.readDouble();
        double pointX = in.readDouble();
        double pointY = in.readDouble();
        double pointZ = in.readDouble();
        double velocityX = in.readDouble();
        double velocityY = in.readDouble();
        double velocityZ = in.readDouble();
        double lookX = in.readDouble();
        double lookY = in.readDouble();
        double lookZ = in.readDouble();
        boolean frontSide = in.readBoolean();
        return new WireTraversive(frameNormal, frameRight, frameUp, originX, originY, originZ, pointX, pointY, pointZ, velocityX, velocityY, velocityZ, lookX, lookY, lookZ, frontSide);
    }
}
