package art.arcane.wormholes.network;

import art.arcane.wormholes.portal.RemotePortal;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PortalSyncWireTest {
    private static PortalInfo sampleInfo(UUID id, boolean open) {
        return new PortalInfo(id, "Gateway abcd", "minecraft:overworld", "GATEWAY", open, "N", "E", "U",
            100.5D, 64.0D, -200.5D,
            99.5D, 63.5D, -201.5D,
            101.5D, 66.5D, -199.5D);
    }

    private static WireMessage roundTrip(WireMessage message) throws IOException {
        byte[] frame = WireCodec.encodeFrame(message);
        return WireCodec.readFrame(new DataInputStream(new ByteArrayInputStream(frame)));
    }

    @Test
    void portalDirectoryRoundTripsAllFields() throws IOException {
        UUID id = UUID.randomUUID();
        WireMessage.PortalDirectory directory = new WireMessage.PortalDirectory(List.of(sampleInfo(id, true)));
        WireMessage.PortalDirectory decoded = assertInstanceOf(WireMessage.PortalDirectory.class, roundTrip(directory));
        assertEquals(1, decoded.portals().size());
        PortalInfo info = decoded.portals().get(0);
        assertEquals(id, info.id());
        assertEquals("Gateway abcd", info.name());
        assertEquals("minecraft:overworld", info.worldKey());
        assertEquals("GATEWAY", info.typeName());
        assertTrue(info.open());
        assertEquals("N", info.frameNormal());
        assertEquals("E", info.frameRight());
        assertEquals("U", info.frameUp());
        assertEquals(100.5D, info.originX());
        assertEquals(-199.5D, info.maxZ());
    }

    @Test
    void portalUpsertAndRemoveRoundTrip() throws IOException {
        UUID id = UUID.randomUUID();
        WireMessage.PortalUpsert upsert = assertInstanceOf(WireMessage.PortalUpsert.class, roundTrip(new WireMessage.PortalUpsert(sampleInfo(id, false))));
        assertEquals(id, upsert.portal().id());

        WireMessage.PortalRemove remove = assertInstanceOf(WireMessage.PortalRemove.class, roundTrip(new WireMessage.PortalRemove(id)));
        assertEquals(id, remove.portalId());
    }

    @Test
    void registryAppliesDirectoryUpsertAndRemove() {
        RemotePortalRegistry registry = new RemotePortalRegistry();
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();

        registry.applyDirectory("hub", List.of(sampleInfo(first, true)));
        RemotePortal portal = registry.get("hub", first);
        assertNotNull(portal);
        assertEquals("Gateway abcd", portal.getName());
        assertEquals("hub", portal.getServer().getName());
        assertEquals("minecraft:overworld", portal.getServer().getWorld());
        assertTrue(portal.isOpen());
        assertEquals("N", portal.getFrame().getNormal().name());

        registry.applyUpsert("hub", sampleInfo(second, false));
        assertEquals(2, registry.all().size());
        assertEquals(false, registry.get("hub", second).isOpen());

        registry.applyRemove("hub", first);
        assertNull(registry.get("hub", first));
        assertEquals(1, registry.all().size());

        registry.applyDirectory("hub", List.of());
        assertEquals(0, registry.all().size());
    }

    @Test
    void registryKeepsPeersIsolated() {
        RemotePortalRegistry registry = new RemotePortalRegistry();
        UUID id = UUID.randomUUID();
        registry.applyUpsert("hub", sampleInfo(id, true));
        assertNull(registry.get("creative", id));
        registry.applyRemove("creative", id);
        assertNotNull(registry.get("hub", id));
    }
}
