package art.arcane.wormholes.survival.doors.dimension;

import art.arcane.wormholes.util.JSONObject;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

public final class PocketDimensionResourcesTest {
    private static final String ROOT = "/wormholes-pockets-pack/";

    @Test
    void targetsThePaper262DatapackSchema() throws IOException {
        JSONObject pack = json("pack.mcmeta").getJSONObject("pack");
        assertEquals(107, pack.getInt("max_format"));
        assertEquals(107, pack.getJSONArray("min_format").getInt(0));
        assertEquals(1, pack.getJSONArray("min_format").getInt(1));
    }

    @Test
    void dimensionTypeIsRegistryLevelFullbrightAndFixedTime() throws IOException {
        JSONObject type = json("data/wormholes/dimension_type/fullbright_pockets.json");
        assertEquals(1.0D, type.getDouble("ambient_light"));
        assertTrue(type.getBoolean("has_fixed_time"));
        assertEquals("minecraft:the_end", type.getString("default_clock"));
        assertFalse(type.has("fixed_time"), "26.2 replaced the legacy fixed_time field");
        assertFalse(type.has("effects"), "26.2 replaced the legacy effects field");

        JSONObject attributes = type.getJSONObject("attributes");
        assertEquals("#ffffff", attributes.getString("minecraft:visual/ambient_light_color"));
        assertEquals(1.0D, attributes.getDouble("minecraft:visual/sky_light_factor"));
    }

    @Test
    void pocketDimensionUsesTheFullbrightTypeAndGeneratesOnlyAir() throws IOException {
        JSONObject dimension = json("data/wormholes/dimension/pockets.json");
        assertEquals("wormholes:fullbright_pockets", dimension.getString("type"));

        JSONObject generator = dimension.getJSONObject("generator");
        assertEquals("minecraft:flat", generator.getString("type"));
        JSONObject settings = generator.getJSONObject("settings");
        assertEquals("minecraft:the_void", settings.getString("biome"));
        assertFalse(settings.getBoolean("features"));
        assertEquals("minecraft:air", settings.getJSONArray("layers").getJSONObject(0).getString("block"));
        assertEquals(0, settings.getJSONArray("structure_overrides").length());
    }

    private static JSONObject json(String path) throws IOException {
        try (InputStream stream = PocketDimensionResourcesTest.class.getResourceAsStream(ROOT + path)) {
            assertNotNull(stream, "Missing resource " + ROOT + path);
            return new JSONObject(new String(stream.readAllBytes(), StandardCharsets.UTF_8));
        }
    }
}
