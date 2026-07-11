package art.arcane.wormholes.network;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

class ViewBulkCompleteWireTest {
    private static WireMessage roundTrip(WireMessage message) throws IOException {
        byte[] frame = WireCodec.encodeFrame(message);
        return WireCodec.readFrame(new DataInputStream(new ByteArrayInputStream(frame)));
    }

    @Test
    void viewBulkCompleteRoundTripsPortalId() throws IOException {
        UUID portalId = UUID.randomUUID();
        WireMessage.ViewBulkComplete decoded = assertInstanceOf(WireMessage.ViewBulkComplete.class,
            roundTrip(new WireMessage.ViewBulkComplete(portalId)));
        assertEquals(portalId, decoded.portalId());
    }

    @Test
    void portalSettingsUpdateRoundTripsKeyedMap() throws IOException {
        UUID portalId = UUID.randomUUID();
        Map<String, String> settings = new LinkedHashMap<>();
        settings.put(PortalSyncService.KEY_PROJECTION_MODE, "MIRROR");
        settings.put(PortalSyncService.KEY_MIRROR_ROTATION, "90");
        settings.put(PortalSyncService.KEY_VIEW_DEPTH, "24");
        settings.put(PortalSyncService.KEY_OUTGOING_TRAVERSALS, "false");
        WireMessage.PortalSettingsUpdate decoded = assertInstanceOf(WireMessage.PortalSettingsUpdate.class,
            roundTrip(new WireMessage.PortalSettingsUpdate(portalId, settings)));
        assertEquals(portalId, decoded.portalId());
        assertEquals(4, decoded.settings().size());
        assertEquals("MIRROR", decoded.settings().get(PortalSyncService.KEY_PROJECTION_MODE));
        assertEquals("90", decoded.settings().get(PortalSyncService.KEY_MIRROR_ROTATION));
        assertEquals("24", decoded.settings().get(PortalSyncService.KEY_VIEW_DEPTH));
        assertEquals("false", decoded.settings().get(PortalSyncService.KEY_OUTGOING_TRAVERSALS));
    }
}
