package art.arcane.wormholes.network;

import art.arcane.wormholes.network.view.ViewBox;
import art.arcane.wormholes.network.view.ViewSlice;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.List;
import java.util.Random;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ViewSliceTest {
    private static ViewSlice randomSlice(long seed, int minX, int minZ) {
        Random random = new Random(seed);
        int sizeX = 16;
        int sizeY = 24;
        int sizeZ = 16;
        int cells = sizeX * sizeY * sizeZ;
        List<String> palette = List.of("minecraft:air", "minecraft:stone", "minecraft:oak_log[axis=y]", "minecraft:water[level=0]");
        List<String> biomePalette = List.of("minecraft:plains", "minecraft:desert", "minecraft:swamp");
        short[] indices = new short[cells];
        byte[] light = new byte[cells];
        short[] biomes = new short[cells];
        for (int i = 0; i < cells; i++) {
            indices[i] = (short) random.nextInt(palette.size());
            light[i] = (byte) random.nextInt(256);
            biomes[i] = (short) random.nextInt(biomePalette.size());
        }
        return new ViewSlice(minX, 60, minZ, sizeX, sizeY, sizeZ, palette, indices, light, biomePalette, biomes);
    }

    @Test
    void sliceRoundTripsThroughWire() throws IOException {
        ViewSlice original = randomSlice(42L, 32, -48);
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        original.write(new DataOutputStream(buffer));
        ViewSlice decoded = ViewSlice.read(new DataInputStream(new ByteArrayInputStream(buffer.toByteArray())));

        assertEquals(original.minX(), decoded.minX());
        assertEquals(original.minY(), decoded.minY());
        assertEquals(original.minZ(), decoded.minZ());
        assertEquals(original.palette(), decoded.palette());
        assertArrayEquals(original.indices(), decoded.indices());
        assertArrayEquals(original.light(), decoded.light());
        assertEquals(original.biomePalette(), decoded.biomePalette());
        assertArrayEquals(original.biomes(), decoded.biomes());
        assertEquals(original.contentHash(), decoded.contentHash());
    }

    @Test
    void contentHashDetectsChanges() {
        ViewSlice first = randomSlice(7L, 0, 0);
        ViewSlice second = randomSlice(7L, 0, 0);
        assertEquals(first.contentHash(), second.contentHash());

        second.indices()[100] = (short) ((second.indices()[100] + 1) % 4);
        assertNotEquals(first.contentHash(), second.contentHash());

        ViewSlice third = randomSlice(7L, 0, 0);
        third.biomes()[100] = (short) ((third.biomes()[100] + 1) % 3);
        assertNotEquals(first.contentHash(), third.contentHash());
    }

    @Test
    void cellIndexingMatchesEncodeOrder() {
        ViewSlice slice = randomSlice(3L, 16, 32);
        assertEquals(0, slice.cellIndex(16, 60, 32));
        assertEquals(1, slice.cellIndex(17, 60, 32));
        assertEquals(slice.sizeX(), slice.cellIndex(16, 60, 33));
        assertEquals(slice.sizeX() * slice.sizeZ(), slice.cellIndex(16, 61, 32));
        assertTrue(slice.contains(16, 60, 32));
        assertTrue(slice.contains(31, 83, 47));
        assertFalse(slice.contains(32, 60, 32));
        assertFalse(slice.contains(16, 84, 32));
    }

    @Test
    void viewMessagesRoundTrip() throws IOException {
        UUID portalId = UUID.randomUUID();
        ViewBox box = new ViewBox(-16, 60, -16, 47, 83, 47);
        ViewSlice slice = randomSlice(9L, -16, -16);

        byte[] subscribeFrame = WireCodec.encodeFrame(new WireMessage.ViewSubscribe(portalId));
        WireMessage.ViewSubscribe subscribe = assertInstanceOf(WireMessage.ViewSubscribe.class, WireCodec.readFrame(new DataInputStream(new ByteArrayInputStream(subscribeFrame))));
        assertEquals(portalId, subscribe.portalId());

        byte[] snapshotFrame = WireCodec.encodeFrame(new WireMessage.ViewSnapshot(portalId, box, List.of(slice)));
        WireMessage.ViewSnapshot snapshot = assertInstanceOf(WireMessage.ViewSnapshot.class, WireCodec.readFrame(new DataInputStream(new ByteArrayInputStream(snapshotFrame))));
        assertEquals(box, snapshot.box());
        assertEquals(1, snapshot.slices().size());
        assertEquals(slice.contentHash(), snapshot.slices().get(0).contentHash());

        byte[] deltaFrame = WireCodec.encodeFrame(new WireMessage.ViewDelta(portalId, List.of(slice, randomSlice(10L, 0, -16))));
        WireMessage.ViewDelta delta = assertInstanceOf(WireMessage.ViewDelta.class, WireCodec.readFrame(new DataInputStream(new ByteArrayInputStream(deltaFrame))));
        assertEquals(2, delta.slices().size());

        byte[] unsubscribeFrame = WireCodec.encodeFrame(new WireMessage.ViewUnsubscribe(portalId));
        WireMessage.ViewUnsubscribe unsubscribe = assertInstanceOf(WireMessage.ViewUnsubscribe.class, WireCodec.readFrame(new DataInputStream(new ByteArrayInputStream(unsubscribeFrame))));
        assertEquals(portalId, unsubscribe.portalId());
    }

    @Test
    void viewEntitiesRoundTrip() throws IOException {
        UUID portalId = UUID.randomUUID();
        art.arcane.wormholes.network.view.EntityVisual visual = new art.arcane.wormholes.network.view.EntityVisual(
            UUID.randomUUID(), "minecraft:player",
            10.5D, 64.0D, -3.25D, 1.95D,
            0.1D, -0.2D, 0.97D,
            0.0D, -0.08D, 0.3D,
            true,
            "Psycho", "dGV4dHVyZS1ibG9i", "c2lnbmF0dXJl",
            new byte[]{1, 2, 3, 4}, new byte[]{9, 8});
        byte[] frame = WireCodec.encodeFrame(new WireMessage.ViewEntities(portalId, List.of(visual)));
        WireMessage.ViewEntities decoded = assertInstanceOf(WireMessage.ViewEntities.class, WireCodec.readFrame(new DataInputStream(new ByteArrayInputStream(frame))));
        assertEquals(portalId, decoded.portalId());
        assertEquals(1, decoded.entities().size());
        assertEquals(visual, decoded.entities().get(0));
    }

    @Test
    void boxContainsAndRoundTrip() throws IOException {
        ViewBox box = new ViewBox(-10, 5, -10, 10, 25, 10);
        assertTrue(box.contains(0, 10, 0));
        assertTrue(box.contains(-10, 5, -10));
        assertTrue(box.contains(10, 25, 10));
        assertFalse(box.contains(11, 10, 0));
        assertFalse(box.contains(0, 26, 0));

        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        box.write(new DataOutputStream(buffer));
        assertEquals(box, ViewBox.read(new DataInputStream(new ByteArrayInputStream(buffer.toByteArray()))));
    }
}
