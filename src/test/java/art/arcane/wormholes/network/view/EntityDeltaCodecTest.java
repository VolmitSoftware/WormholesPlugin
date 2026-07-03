package art.arcane.wormholes.network.view;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EntityDeltaCodecTest {
    private static EntityVisual baseSnapshot(int sequence) {
        return EntityVisual.full(
            new UUID(1L, 2L),
            "minecraft:zombie",
            10.0D, 64.0D, 20.0D,
            1.95D,
            0.0D, 0.0D, 1.0D,
            90.0F, 0.0F,
            0.0D, 0.0D, 0.0D,
            true,
            "",
            "",
            "",
            null,
            null,
            new byte[]{1, 2, 3},
            new byte[]{4, 5, 6},
            sequence
        );
    }

    private static EntityVisual movedSnapshot(EntityVisual base, double dx, double dy, double dz, int sequence) {
        return EntityVisual.full(
            base.id(),
            base.typeKey(),
            base.x() + dx, base.y() + dy, base.z() + dz,
            base.height(),
            base.lookX(), base.lookY(), base.lookZ(),
            base.yaw(), base.pitch(),
            base.velocityX(), base.velocityY(), base.velocityZ(),
            base.onGround(),
            base.playerName(),
            base.textureValue(),
            base.textureSignature(),
            base.passengerOf(),
            base.leashHolder(),
            base.metadata(),
            base.equipment(),
            sequence
        );
    }

    private static EntityVisual encodeAndDecode(EntityVisual original) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream(256);
        DataOutputStream out = new DataOutputStream(buffer);
        original.write(out);
        out.flush();
        DataInputStream in = new DataInputStream(new ByteArrayInputStream(buffer.toByteArray()));
        return EntityVisual.read(in);
    }

    @Test
    void fullSnapshotRoundTripsExactly() throws IOException {
        EntityVisual original = baseSnapshot(7);
        EntityVisual decoded = encodeAndDecode(original);
        assertEquals(EntityVisual.MODE_FULL, decoded.mode());
        assertEquals(7, decoded.sequence());
        assertEquals(EntityVisual.FIELD_ALL_FULL, decoded.presentMask());
        assertEquals(original.id(), decoded.id());
        assertEquals(original.typeKey(), decoded.typeKey());
        assertEquals(EntityVisual.dequantize(EntityVisual.quantize(original.x())), decoded.x());
    }

    @Test
    void deltaWithPositionOnlySetsPositionBit() {
        EntityVisual previous = baseSnapshot(1);
        EntityVisual current = movedSnapshot(previous, 0.5D, 0.0D, 0.0D, 2);
        EntityVisual delta = EntityDeltaCodec.buildDelta(current, previous, 2, EntityDeltaCodec.computeMask(current, previous));
        assertEquals(EntityVisual.MODE_DELTA, delta.mode());
        assertTrue((delta.presentMask() & EntityVisual.FIELD_POSITION) != 0);
        assertEquals(0, delta.presentMask() & EntityVisual.FIELD_YAW_PITCH);
        assertEquals(0, delta.presentMask() & EntityVisual.FIELD_EQUIPMENT);
        assertEquals(0, delta.presentMask() & EntityVisual.FIELD_METADATA);
        assertEquals(0, delta.presentMask() & EntityVisual.FIELD_HEIGHT);
        assertEquals(0, delta.presentMask() & EntityVisual.FIELD_ON_GROUND);
        assertEquals(0, delta.presentMask() & EntityVisual.FIELD_PROFILE);
        assertEquals(0, delta.presentMask() & EntityVisual.FIELD_LOOK_VEC);
    }

    @Test
    void firstDeltaIsForcedFullWhenPreviousIsNull() {
        EntityVisual current = baseSnapshot(0);
        EntityVisual delta = EntityDeltaCodec.buildDelta(current, null, 0, 0);
        assertEquals(EntityVisual.MODE_FULL, delta.mode());
        assertEquals(EntityVisual.FIELD_ALL_FULL, delta.presentMask());
    }

    @Test
    void applyDeltaMergesPositionOntoPrevious() {
        EntityVisual previous = baseSnapshot(1);
        EntityVisual current = movedSnapshot(previous, 0.5D, 0.0D, 0.0D, 2);
        EntityVisual delta = EntityDeltaCodec.buildDelta(current, previous, 2, EntityDeltaCodec.computeMask(current, previous));
        EntityVisual merged = EntityDeltaCodec.applyDelta(delta, previous);
        assertEquals(EntityVisual.MODE_FULL, merged.mode());
        assertEquals(2, merged.sequence());
        assertEquals(current.x(), merged.x(), 1.0D / 4096.0D);
        assertEquals(previous.height(), merged.height());
        assertEquals(previous.typeKey(), merged.typeKey());
    }

    @Test
    void sequentialDeltasConvergeToFinalState() {
        EntityVisual previous = baseSnapshot(0);
        EntityVisual after1 = movedSnapshot(previous, 1.0D, 0.0D, 0.0D, 1);
        EntityVisual after2 = movedSnapshot(after1, 0.0D, 0.0D, 1.0D, 2);
        EntityVisual after3 = movedSnapshot(after2, 0.0D, 1.0D, 0.0D, 3);
        EntityVisual d1 = EntityDeltaCodec.buildDelta(after1, previous, 1, EntityDeltaCodec.computeMask(after1, previous));
        EntityVisual merged1 = EntityDeltaCodec.applyDelta(d1, previous);
        EntityVisual d2 = EntityDeltaCodec.buildDelta(after2, after1, 2, EntityDeltaCodec.computeMask(after2, after1));
        EntityVisual merged2 = EntityDeltaCodec.applyDelta(d2, merged1);
        EntityVisual d3 = EntityDeltaCodec.buildDelta(after3, after2, 3, EntityDeltaCodec.computeMask(after3, after2));
        EntityVisual merged3 = EntityDeltaCodec.applyDelta(d3, merged2);
        assertEquals(after3.x(), merged3.x(), 1.0D / 4096.0D);
        assertEquals(after3.y(), merged3.y(), 1.0D / 4096.0D);
        assertEquals(after3.z(), merged3.z(), 1.0D / 4096.0D);
    }

    @Test
    void allocateSequenceProducesMonotonicIncreasingValues() {
        EntitySendState state = new EntitySendState(new UUID(1L, 1L));
        int first = state.allocateSequence();
        int second = state.allocateSequence();
        int third = state.allocateSequence();
        assertEquals(0, first);
        assertEquals(1, second);
        assertEquals(2, third);
    }

    @Test
    void deltaIncludesYawPitchWhenChanged() {
        EntityVisual previous = baseSnapshot(0);
        EntityVisual current = new EntityVisual(
            EntityVisual.MODE_FULL,
            1,
            EntityVisual.FIELD_ALL_FULL,
            previous.id(),
            previous.typeKey(),
            previous.x(), previous.y(), previous.z(),
            previous.height(),
            previous.lookX(), previous.lookY(), previous.lookZ(),
            45.0F, 30.0F,
            previous.velocityX(), previous.velocityY(), previous.velocityZ(),
            previous.onGround(),
            previous.playerName(), previous.textureValue(), previous.textureSignature(),
            previous.passengerOf(),
            previous.leashHolder(),
            previous.metadata(), previous.equipment()
        );
        EntityVisual delta = EntityDeltaCodec.buildDelta(current, previous, 1, EntityDeltaCodec.computeMask(current, previous));
        assertTrue((delta.presentMask() & EntityVisual.FIELD_YAW_PITCH) != 0);
    }

    @Test
    void deltaIncludesEquipmentWhenChanged() {
        EntityVisual previous = baseSnapshot(0);
        EntityVisual current = new EntityVisual(
            EntityVisual.MODE_FULL,
            1,
            EntityVisual.FIELD_ALL_FULL,
            previous.id(),
            previous.typeKey(),
            previous.x(), previous.y(), previous.z(),
            previous.height(),
            previous.lookX(), previous.lookY(), previous.lookZ(),
            previous.yaw(), previous.pitch(),
            previous.velocityX(), previous.velocityY(), previous.velocityZ(),
            previous.onGround(),
            previous.playerName(), previous.textureValue(), previous.textureSignature(),
            previous.passengerOf(),
            previous.leashHolder(),
            previous.metadata(), new byte[]{9, 9, 9, 9}
        );
        EntityVisual delta = EntityDeltaCodec.buildDelta(current, previous, 1, EntityDeltaCodec.computeMask(current, previous));
        assertTrue((delta.presentMask() & EntityVisual.FIELD_EQUIPMENT) != 0);
    }

    @Test
    void fullSnapshotRoundTripsLookVelocityHeightWithinQuantizationTolerance() throws IOException {
        EntityVisual original = EntityVisual.full(
            new UUID(3L, 4L),
            "minecraft:cow",
            128.5D, 70.0D, -64.25D,
            1.43D,
            0.4082D, -0.8164D, 0.4082D,
            12.0F, -7.5F,
            0.213D, -0.045D, 1.872D,
            false,
            "",
            "",
            "",
            null,
            null,
            new byte[]{1},
            new byte[]{2},
            42
        );
        EntityVisual decoded = encodeAndDecode(original);
        double unitTolerance = 1.0D / EntityVisual.UNIT_SCALE;
        double velocityTolerance = 1.0D / EntityVisual.VELOCITY_SCALE;
        double heightTolerance = 1.0D / EntityVisual.HEIGHT_SCALE;
        assertEquals(original.lookX(), decoded.lookX(), unitTolerance);
        assertEquals(original.lookY(), decoded.lookY(), unitTolerance);
        assertEquals(original.lookZ(), decoded.lookZ(), unitTolerance);
        assertEquals(original.velocityX(), decoded.velocityX(), velocityTolerance);
        assertEquals(original.velocityY(), decoded.velocityY(), velocityTolerance);
        assertEquals(original.velocityZ(), decoded.velocityZ(), velocityTolerance);
        assertEquals(original.height(), decoded.height(), heightTolerance);
    }

    @Test
    void velocityClampsToScaleRangeWithoutWrapping() throws IOException {
        EntityVisual original = EntityVisual.full(
            new UUID(5L, 6L),
            "minecraft:arrow",
            0.0D, 0.0D, 0.0D,
            0.5D,
            0.0D, 0.0D, 1.0D,
            0.0F, 0.0F,
            900.0D, -900.0D, 0.0D,
            false,
            "",
            "",
            "",
            null,
            null,
            new byte[]{0},
            new byte[]{0},
            1
        );
        EntityVisual decoded = encodeAndDecode(original);
        double maxVelocity = (double) Short.MAX_VALUE / EntityVisual.VELOCITY_SCALE;
        double minVelocity = (double) Short.MIN_VALUE / EntityVisual.VELOCITY_SCALE;
        assertEquals(maxVelocity, decoded.velocityX(), 1.0e-9D);
        assertEquals(minVelocity, decoded.velocityY(), 1.0e-9D);
        assertEquals(0.0D, decoded.velocityZ(), 1.0e-9D);
    }

    @Test
    void stationaryEntityWithSubEpsilonJitterDoesNotSetVelocityField() {
        EntityVisual previous = baseSnapshot(0);
        EntityVisual current = new EntityVisual(
            EntityVisual.MODE_FULL,
            1,
            EntityVisual.FIELD_ALL_FULL,
            previous.id(),
            previous.typeKey(),
            previous.x(), previous.y(), previous.z(),
            previous.height(),
            previous.lookX(), previous.lookY(), previous.lookZ(),
            previous.yaw(), previous.pitch(),
            0.0001D, -0.0002D, 0.00005D,
            previous.onGround(),
            previous.playerName(), previous.textureValue(), previous.textureSignature(),
            previous.passengerOf(),
            previous.leashHolder(),
            previous.metadata(), previous.equipment()
        );
        EntityVisual delta = EntityDeltaCodec.buildDelta(current, previous, 1, EntityDeltaCodec.computeMask(current, previous));
        assertEquals(0, delta.presentMask() & EntityVisual.FIELD_VELOCITY);
    }

    @Test
    void movingEntityWithSignificantVelocityChangeSetsVelocityField() {
        EntityVisual previous = baseSnapshot(0);
        EntityVisual current = new EntityVisual(
            EntityVisual.MODE_FULL,
            1,
            EntityVisual.FIELD_ALL_FULL,
            previous.id(),
            previous.typeKey(),
            previous.x(), previous.y(), previous.z(),
            previous.height(),
            previous.lookX(), previous.lookY(), previous.lookZ(),
            previous.yaw(), previous.pitch(),
            0.5D, 0.0D, 0.0D,
            previous.onGround(),
            previous.playerName(), previous.textureValue(), previous.textureSignature(),
            previous.passengerOf(),
            previous.leashHolder(),
            previous.metadata(), previous.equipment()
        );
        EntityVisual delta = EntityDeltaCodec.buildDelta(current, previous, 1, EntityDeltaCodec.computeMask(current, previous));
        assertTrue((delta.presentMask() & EntityVisual.FIELD_VELOCITY) != 0);
    }

    private static EntityVisual withPose(EntityVisual base, double lookX, double lookY, double lookZ,
                                         float yaw, float pitch, double vx, double vy, double vz) {
        return new EntityVisual(
            EntityVisual.MODE_FULL,
            1,
            EntityVisual.FIELD_ALL_FULL,
            base.id(),
            base.typeKey(),
            base.x(), base.y(), base.z(),
            base.height(),
            lookX, lookY, lookZ,
            yaw, pitch,
            vx, vy, vz,
            base.onGround(),
            base.playerName(), base.textureValue(), base.textureSignature(),
            base.passengerOf(),
            base.leashHolder(),
            base.metadata(), base.equipment()
        );
    }

    @Test
    void identicalSnapshotsProduceZeroMask() {
        EntityVisual base = baseSnapshot(0);
        EntityVisual copy = baseSnapshot(1);
        assertEquals(0, EntityDeltaCodec.computeMask(copy, base));
    }

    @Test
    void subQuantumYawJitterDoesNotSetYawPitchBit() {
        EntityVisual base = baseSnapshot(0);
        EntityVisual jittered = withPose(base, base.lookX(), base.lookY(), base.lookZ(), 90.5F, base.pitch(),
            base.velocityX(), base.velocityY(), base.velocityZ());
        assertEquals(0, EntityDeltaCodec.computeMask(jittered, base) & EntityVisual.FIELD_YAW_PITCH);
        EntityVisual turned = withPose(base, base.lookX(), base.lookY(), base.lookZ(), 92.0F, base.pitch(),
            base.velocityX(), base.velocityY(), base.velocityZ());
        assertTrue((EntityDeltaCodec.computeMask(turned, base) & EntityVisual.FIELD_YAW_PITCH) != 0);
    }

    @Test
    void yawWraparoundComparesEqualAcrossZero() {
        EntityVisual base = baseSnapshot(0);
        EntityVisual atHighYaw = withPose(base, base.lookX(), base.lookY(), base.lookZ(), 359.9F, base.pitch(),
            base.velocityX(), base.velocityY(), base.velocityZ());
        EntityVisual justPastZero = withPose(base, base.lookX(), base.lookY(), base.lookZ(), 0.05F, base.pitch(),
            base.velocityX(), base.velocityY(), base.velocityZ());
        assertEquals(0, EntityDeltaCodec.computeMask(justPastZero, atHighYaw) & EntityVisual.FIELD_YAW_PITCH);
        EntityVisual pastQuantum = withPose(base, base.lookX(), base.lookY(), base.lookZ(), 0.8F, base.pitch(),
            base.velocityX(), base.velocityY(), base.velocityZ());
        assertTrue((EntityDeltaCodec.computeMask(pastQuantum, atHighYaw) & EntityVisual.FIELD_YAW_PITCH) != 0);
    }

    @Test
    void subQuantumLookVecJitterDoesNotSetLookBit() {
        EntityVisual base = baseSnapshot(0);
        EntityVisual jittered = withPose(base, base.lookX() + 0.00001D, base.lookY(), base.lookZ(), base.yaw(), base.pitch(),
            base.velocityX(), base.velocityY(), base.velocityZ());
        assertEquals(0, EntityDeltaCodec.computeMask(jittered, base) & EntityVisual.FIELD_LOOK_VEC);
    }

    @Test
    void constantGravityVelocityDoesNotSetVelocityBit() {
        EntityVisual base = baseSnapshot(0);
        EntityVisual falling = withPose(base, base.lookX(), base.lookY(), base.lookZ(), base.yaw(), base.pitch(),
            0.0D, -0.0784D, 0.0D);
        EntityVisual stillFalling = withPose(base, base.lookX(), base.lookY(), base.lookZ(), base.yaw(), base.pitch(),
            0.0D, -0.0784D, 0.0D);
        assertEquals(0, EntityDeltaCodec.computeMask(stillFalling, falling) & EntityVisual.FIELD_VELOCITY);
    }

    @Test
    void velocityChangeAcrossQuantumSetsVelocityBit() {
        EntityVisual base = baseSnapshot(0);
        EntityVisual moving = withPose(base, base.lookX(), base.lookY(), base.lookZ(), base.yaw(), base.pitch(),
            0.5D, 0.0D, 0.0D);
        assertTrue((EntityDeltaCodec.computeMask(moving, base) & EntityVisual.FIELD_VELOCITY) != 0);
    }

    @Test
    void wireHeaderRoundTripsAtSequenceBoundaries() throws IOException {
        EntityVisual zero = baseSnapshot(0);
        EntityVisual zeroDecoded = encodeAndDecode(zero);
        assertEquals(0, zeroDecoded.sequence());
        assertEquals(EntityVisual.MODE_FULL, zeroDecoded.mode());

        EntityVisual max = baseSnapshot(65535);
        EntityVisual maxDecoded = encodeAndDecode(max);
        assertEquals(65535, maxDecoded.sequence());
        assertEquals(EntityVisual.MODE_FULL, maxDecoded.mode());
        assertEquals(EntityVisual.FIELD_ALL_FULL, maxDecoded.presentMask());
    }

    @Test
    void deltaModeWithAllFieldBitsRoundTripsHeader() throws IOException {
        EntityVisual base = baseSnapshot(1);
        EntityVisual delta = new EntityVisual(
            EntityVisual.MODE_DELTA,
            777,
            EntityVisual.FIELD_ALL_FULL,
            base.id(),
            base.typeKey(),
            base.x(), base.y(), base.z(),
            base.height(),
            base.lookX(), base.lookY(), base.lookZ(),
            base.yaw(), base.pitch(),
            base.velocityX(), base.velocityY(), base.velocityZ(),
            base.onGround(),
            base.playerName(), base.textureValue(), base.textureSignature(),
            base.passengerOf(),
            base.leashHolder(),
            base.metadata(), base.equipment()
        );
        EntityVisual decoded = encodeAndDecode(delta);
        assertEquals(EntityVisual.MODE_DELTA, decoded.mode());
        assertEquals(EntityVisual.FIELD_ALL_FULL, decoded.presentMask());
        assertEquals(777, decoded.sequence());
    }

    @Test
    void allocateSequenceWrapsAtSixteenBits() throws IOException {
        EntitySendState state = new EntitySendState(new UUID(2L, 2L));
        for (int i = 0; i < 65535; i++) {
            state.allocateSequence();
        }
        assertEquals(65535, state.allocateSequence());
        assertEquals(0, state.allocateSequence());
        assertEquals(1, state.allocateSequence());
    }

    @Test
    void deltaEncodingPayloadIsSmallerThanFullSnapshot() throws IOException {
        EntityVisual previous = baseSnapshot(0);
        EntityVisual current = movedSnapshot(previous, 0.5D, 0.0D, 0.0D, 1);
        ByteArrayOutputStream fullBuf = new ByteArrayOutputStream();
        current.write(new DataOutputStream(fullBuf));
        EntityVisual delta = EntityDeltaCodec.buildDelta(current, previous, 1, EntityDeltaCodec.computeMask(current, previous));
        ByteArrayOutputStream deltaBuf = new ByteArrayOutputStream();
        delta.write(new DataOutputStream(deltaBuf));
        assertNotEquals(0, fullBuf.size());
        assertTrue(deltaBuf.size() < fullBuf.size(),
            "delta " + deltaBuf.size() + " should be smaller than full " + fullBuf.size());
    }
}
