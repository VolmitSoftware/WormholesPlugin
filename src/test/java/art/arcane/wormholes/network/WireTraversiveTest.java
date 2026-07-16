package art.arcane.wormholes.network;

import art.arcane.wormholes.portal.PortalFrame;
import art.arcane.wormholes.portal.TraversableType;
import art.arcane.wormholes.portal.Traversive;
import art.arcane.wormholes.util.Direction;
import org.bukkit.util.Vector;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WireTraversiveTest {
    private static Traversive sampleTraversive(boolean frontSide) {
        PortalFrame inFrame = PortalFrame.canonical(Direction.N).view(frontSide);
        Vector origin = new Vector(100.5D, 64.0D, -200.5D);
        Vector point = new Vector(100.9D, 64.7D, -200.2D);
        Vector velocity = new Vector(0.3D, -0.1D, -0.6D);
        Vector look = new Vector(0.1D, 0.2D, -0.9D);
        return new Traversive(new Object(), TraversableType.ENTITY, inFrame, origin, point, velocity, look, frontSide);
    }

    private static void assertVectorEquals(Vector expected, Vector actual) {
        assertEquals(expected.getX(), actual.getX(), 1e-12D);
        assertEquals(expected.getY(), actual.getY(), 1e-12D);
        assertEquals(expected.getZ(), actual.getZ(), 1e-12D);
    }

    @Test
    void wireRoundTripPreservesAllOutputMath() throws IOException {
        for (boolean frontSide : new boolean[]{true, false}) {
            Traversive original = sampleTraversive(frontSide);
            WireTraversive wire = WireTraversive.fromTraversive(original);

            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            wire.write(new DataOutputStream(buffer));
            WireTraversive decoded = WireTraversive.read(new DataInputStream(new ByteArrayInputStream(buffer.toByteArray())));

            Traversive reconstructed = decoded.toTraversive(new Object());
            for (Direction outDirection : new Direction[]{Direction.N, Direction.S, Direction.E, Direction.U}) {
                PortalFrame outFrame = PortalFrame.canonical(outDirection);
                Vector outOrigin = new Vector(-10.5D, 70.0D, 33.5D);
                assertVectorEquals(original.getOutVelocity(outFrame), reconstructed.getOutVelocity(outFrame));
                assertVectorEquals(original.getOutLook(outFrame), reconstructed.getOutLook(outFrame));
                assertVectorEquals(original.getOutOffset(outFrame), reconstructed.getOutOffset(outFrame));
                assertVectorEquals(original.getOutPoint(outFrame, outOrigin), reconstructed.getOutPoint(outFrame, outOrigin));
            }
        }
    }

    @Test
    void handoffMessagesRoundTrip() throws IOException {
        WireTraversive wire = WireTraversive.fromTraversive(sampleTraversive(true));
        UUID transferId = UUID.randomUUID();
        UUID playerId = UUID.randomUUID();
        UUID portalId = UUID.randomUUID();

        WireMessage.HandoffRequest request = roundTrip(new WireMessage.HandoffRequest(transferId, playerId, "Psycho", portalId, true, wire));
        assertEquals(transferId, request.transferId());
        assertEquals(playerId, request.playerId());
        assertEquals("Psycho", request.playerName());
        assertEquals(portalId, request.destPortalId());
        assertTrue(request.directTransfer());
        assertEquals(wire, request.traversive());

        assertEquals(transferId, roundTrip(new WireMessage.HandoffAck(transferId)).transferId());
        WireMessage.HandoffDeny deny = roundTrip(new WireMessage.HandoffDeny(transferId, "unknown portal", 1250L));
        assertEquals("unknown portal", deny.reason());
        assertEquals(1250L, deny.retryAfterMillis());
        WireMessage.HandoffCancel cancel = roundTrip(new WireMessage.HandoffCancel(transferId, playerId));
        assertEquals(transferId, cancel.transferId());
        assertEquals(playerId, cancel.playerId());
    }

    @Test
    void entityTransferMessagesRoundTrip() throws IOException {
        WireTraversive wire = WireTraversive.fromTraversive(sampleTraversive(false));
        UUID transferId = UUID.randomUUID();
        UUID portalId = UUID.randomUUID();
        byte[] nbt = "{id:\"minecraft:item\",Item:{id:\"minecraft:diamond\",count:3}}".getBytes(java.nio.charset.StandardCharsets.UTF_8);

        WireMessage.EntityTransfer transfer = roundTrip(new WireMessage.EntityTransfer(transferId, portalId, nbt, wire));
        assertEquals(transferId, transfer.transferId());
        assertEquals(portalId, transfer.destPortalId());
        assertEquals(new String(nbt, java.nio.charset.StandardCharsets.UTF_8), new String(transfer.entitySnapshot(), java.nio.charset.StandardCharsets.UTF_8));
        assertEquals(wire, transfer.traversive());

        WireMessage.EntityTransferAck ack = roundTrip(new WireMessage.EntityTransferAck(transferId, true));
        assertEquals(transferId, ack.transferId());
        assertEquals(true, ack.accepted());
    }

    @SuppressWarnings("unchecked")
    private static <T extends WireMessage> T roundTrip(T message) throws IOException {
        byte[] frame;
        try {
            frame = WireCodec.encodeFrame(message);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        WireMessage decoded = WireCodec.readFrame(new DataInputStream(new ByteArrayInputStream(frame)));
        return (T) assertInstanceOf(message.getClass(), decoded);
    }
}
