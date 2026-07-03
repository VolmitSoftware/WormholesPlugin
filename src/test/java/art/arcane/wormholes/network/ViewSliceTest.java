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
        int gridLength = ViewSlice.biomeGridSpan(minX, sizeX) * ViewSlice.biomeGridSpan(60, sizeY) * ViewSlice.biomeGridSpan(minZ, sizeZ);
        short[] biomes = new short[gridLength];
        for (int i = 0; i < cells; i++) {
            indices[i] = (short) random.nextInt(palette.size());
            light[i] = (byte) random.nextInt(256);
        }
        for (int i = 0; i < gridLength; i++) {
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
        third.biomes()[50] = (short) ((third.biomes()[50] + 1) % 3);
        assertNotEquals(first.contentHash(), third.contentHash());
    }

    @Test
    void adaptiveIndexWidthRoundTrips() throws IOException {
        Random random = new Random(11L);
        int sizeX = 16;
        int sizeY = 24;
        int sizeZ = 16;
        int cells = sizeX * sizeY * sizeZ;
        List<String> widePalette = new java.util.ArrayList<>(300);
        for (int i = 0; i < 300; i++) {
            widePalette.add("minecraft:synthetic_state_" + i + "[variant=" + (i % 7) + "]");
        }
        int gridLength = ViewSlice.biomeGridSpan(0, sizeX) * ViewSlice.biomeGridSpan(60, sizeY) * ViewSlice.biomeGridSpan(0, sizeZ);
        short[] indices = new short[cells];
        byte[] light = new byte[cells];
        short[] biomes = new short[gridLength];
        for (int i = 0; i < cells; i++) {
            indices[i] = (short) random.nextInt(widePalette.size());
            light[i] = (byte) random.nextInt(256);
        }
        for (int i = 0; i < gridLength; i++) {
            biomes[i] = 0;
        }
        ViewSlice wide = new ViewSlice(0, 60, 0, sizeX, sizeY, sizeZ, widePalette, indices, light, List.of("minecraft:plains"), biomes);
        ViewSlice wideDecoded = encodeDecode(wide);
        assertEquals(wide.palette(), wideDecoded.palette());
        assertArrayEquals(wide.indices(), wideDecoded.indices());
        assertArrayEquals(wide.light(), wideDecoded.light());
        assertEquals(wide.biomePalette(), wideDecoded.biomePalette());
        assertArrayEquals(wide.biomes(), wideDecoded.biomes());
        assertEquals(wide.contentHash(), wideDecoded.contentHash());

        ViewSlice narrow = randomSlice(12L, 16, -32);
        ViewSlice narrowDecoded = encodeDecode(narrow);
        assertEquals(narrow.palette(), narrowDecoded.palette());
        assertArrayEquals(narrow.indices(), narrowDecoded.indices());
        assertArrayEquals(narrow.light(), narrowDecoded.light());
        assertEquals(narrow.biomePalette(), narrowDecoded.biomePalette());
        assertArrayEquals(narrow.biomes(), narrowDecoded.biomes());
        assertEquals(narrow.contentHash(), narrowDecoded.contentHash());
    }

    @Test
    void unalignedNegativeBoundsBiomeGridRoundTrips() throws IOException {
        Random random = new Random(23L);
        int minX = -7;
        int minY = -62;
        int minZ = 13;
        int sizeX = 16;
        int sizeY = 24;
        int sizeZ = 16;
        int cells = sizeX * sizeY * sizeZ;
        int gridLength = ViewSlice.biomeGridSpan(minX, sizeX) * ViewSlice.biomeGridSpan(minY, sizeY) * ViewSlice.biomeGridSpan(minZ, sizeZ);
        short[] indices = new short[cells];
        byte[] light = new byte[cells];
        short[] biomes = new short[gridLength];
        List<String> biomePalette = List.of("minecraft:plains", "minecraft:desert", "minecraft:swamp");
        for (int i = 0; i < gridLength; i++) {
            biomes[i] = (short) random.nextInt(biomePalette.size());
        }
        ViewSlice slice = new ViewSlice(minX, minY, minZ, sizeX, sizeY, sizeZ, List.of("minecraft:air"), indices, light, biomePalette, biomes);
        for (int y = minY; y < minY + sizeY; y++) {
            for (int z = minZ; z < minZ + sizeZ; z++) {
                for (int x = minX; x < minX + sizeX; x++) {
                    int gridIndex = slice.biomeGridIndex(x, y, z);
                    assertTrue(gridIndex >= 0 && gridIndex < slice.biomeGridLength(),
                        "grid index " + gridIndex + " out of range for cell " + x + "," + y + "," + z);
                }
            }
        }
        ViewSlice decoded = encodeDecode(slice);
        assertArrayEquals(slice.biomes(), decoded.biomes());
        assertEquals(slice.biomeGridLength(), decoded.biomes().length);
    }

    @Test
    void contentHashStableAcrossEncodeDecode() throws IOException {
        for (long seed = 1L; seed <= 5L; seed++) {
            ViewSlice slice = randomSlice(seed, (int) seed * 16, (int) -seed * 16);
            assertEquals(slice.contentHash(), encodeDecode(slice).contentHash());
        }
        Random random = new Random(99L);
        int cells = 16 * 8 * 16;
        List<String> palette = new java.util.ArrayList<>(300);
        for (int i = 0; i < 300; i++) {
            palette.add("minecraft:wide_state_" + i);
        }
        short[] indices = new short[cells];
        byte[] light = new byte[cells];
        for (int i = 0; i < cells; i++) {
            indices[i] = (short) random.nextInt(palette.size());
        }
        int gridLength = ViewSlice.biomeGridSpan(-16, 16) * ViewSlice.biomeGridSpan(-64, 8) * ViewSlice.biomeGridSpan(48, 16);
        short[] biomes = new short[gridLength];
        ViewSlice wide = new ViewSlice(-16, -64, 48, 16, 8, 16, palette, indices, light, List.of("minecraft:plains"), biomes);
        assertEquals(wide.contentHash(), encodeDecode(wide).contentHash());
    }

    private static ViewSlice encodeDecode(ViewSlice slice) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        slice.write(new DataOutputStream(buffer));
        return ViewSlice.read(new DataInputStream(new ByteArrayInputStream(buffer.toByteArray())));
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

        byte[] subscribeFrame = WireCodec.encodeFrame(new WireMessage.ViewSubscribe(portalId));
        WireMessage.ViewSubscribe subscribe = assertInstanceOf(WireMessage.ViewSubscribe.class, WireCodec.readFrame(new DataInputStream(new ByteArrayInputStream(subscribeFrame))));
        assertEquals(portalId, subscribe.portalId());

        byte[] unsubscribeFrame = WireCodec.encodeFrame(new WireMessage.ViewUnsubscribe(portalId));
        WireMessage.ViewUnsubscribe unsubscribe = assertInstanceOf(WireMessage.ViewUnsubscribe.class, WireCodec.readFrame(new DataInputStream(new ByteArrayInputStream(unsubscribeFrame))));
        assertEquals(portalId, unsubscribe.portalId());
    }

    @Test
    void viewEntitiesRoundTrip() throws IOException {
        UUID portalId = UUID.randomUUID();
        art.arcane.wormholes.network.view.EntityVisual visual = art.arcane.wormholes.network.view.EntityVisual.full(
            UUID.randomUUID(), "minecraft:player",
            10.5D, 64.0D, -3.25D, 1.95D,
            0.1D, -0.2D, 0.97D,
            12.5F, -3.25F,
            0.0D, -0.08D, 0.3D,
            true,
            "Psycho", "dGV4dHVyZS1ibG9i", "c2lnbmF0dXJl",
            null,
            null,
            new byte[]{1, 2, 3, 4}, new byte[]{9, 8},
            42);
        byte[] frame = WireCodec.encodeFrame(new WireMessage.ViewEntities(portalId, List.of(visual), List.of(visual.id())));
        WireMessage.ViewEntities decoded = assertInstanceOf(WireMessage.ViewEntities.class, WireCodec.readFrame(new DataInputStream(new ByteArrayInputStream(frame))));
        assertEquals(portalId, decoded.portalId());
        assertEquals(1, decoded.entities().size());
        assertEquals(List.of(visual.id()), decoded.presentIds());
        art.arcane.wormholes.network.view.EntityVisual roundTripped = decoded.entities().get(0);
        assertEquals(visual.id(), roundTripped.id());
        assertEquals(visual.typeKey(), roundTripped.typeKey());
        assertEquals(visual.x(), roundTripped.x(), 1.0D / 4096.0D);
        assertEquals(visual.y(), roundTripped.y(), 1.0D / 4096.0D);
        assertEquals(visual.z(), roundTripped.z(), 1.0D / 4096.0D);
        assertEquals(visual.sequence(), roundTripped.sequence());
        assertEquals(visual.mode(), roundTripped.mode());
        assertEquals(visual.presentMask(), roundTripped.presentMask());
        assertEquals(visual.playerName(), roundTripped.playerName());
        assertEquals(visual.textureValue(), roundTripped.textureValue());
        assertEquals(visual.textureSignature(), roundTripped.textureSignature());
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
