package art.arcane.wormholes.portal;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.UUID;

import org.junit.jupiter.api.Test;

import art.arcane.wormholes.util.JSONObject;

public final class TunnelPersistenceTest {
    @Test
    public void unresolvedLocalTunnelSavesPendingDestinationWithoutNpe() {
        UUID destinationId = UUID.fromString("123e4567-e89b-12d3-a456-426614174000");
        JSONObject input = new JSONObject();
        input.put("type", TunnelType.LOCAL.name());
        input.put("destination", destinationId.toString());

        LocalTunnel tunnel = new LocalTunnel(null);
        tunnel.loadJSON(input);

        JSONObject output = new JSONObject();
        tunnel.saveJSON(output);

        assertEquals(TunnelType.LOCAL.name(), output.getString("type"));
        assertEquals(destinationId.toString(), output.getString("destination"));
    }

    @Test
    public void unresolvedDimensionalTunnelSavesPendingDestinationWithoutNpe() {
        UUID destinationId = UUID.fromString("123e4567-e89b-12d3-a456-426614174111");
        JSONObject input = new JSONObject();
        input.put("type", TunnelType.DIMENSIONAL.name());
        input.put("destination", destinationId.toString());

        DimensionalTunnel tunnel = new DimensionalTunnel(null);
        tunnel.loadJSON(input);

        JSONObject output = new JSONObject();
        tunnel.saveJSON(output);

        assertEquals(TunnelType.DIMENSIONAL.name(), output.getString("type"));
        assertEquals(destinationId.toString(), output.getString("destination"));
    }
}
