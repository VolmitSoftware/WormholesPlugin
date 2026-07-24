package art.arcane.wormholes.config;

import art.arcane.wormholes.Settings;
import art.arcane.wormholes.config.toml.NetworkConfig;
import art.arcane.wormholes.config.toml.WormholesConfigFile;
import art.arcane.wormholes.util.project.config.TomlCodec;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WormholesConfigFileTest {
    @TempDir
    Path tempDir;

    @Test
    void freshInstallEmitsEveryConfigKey() throws IOException {
        WormholesSettings settings = WormholesSettings.loadAll(tempDir);
        Path file = tempDir.resolve("config").resolve(WormholesSettings.CONFIG_FILE_NAME);

        assertEquals(VisualQualityProfile.AUTO, settings.getVisualQualityProfile());
        assertTrue(settings.getMain().dimensionalDoorsEnabled);
        List<String> emitted = emittedSettings(file);
        assertEquals("schema = 2", emitted.get(0));
        assertEquals("quality = \"auto\"", emitted.get(1));
        assertTrue(emitted.contains("[main]"));
        assertTrue(emitted.contains("[network]"));
        assertTrue(emitted.contains("[network.transport]"));
        assertTrue(emitted.contains("[network.view]"));
        assertTrue(emitted.contains("[network.stats]"));
        assertTrue(emitted.contains("[network.replication]"));
        assertTrue(emitted.contains("[projection]"));
        assertTrue(emitted.contains("[render]"));
        assertTrue(emitted.contains("aperture-padding-blocks = 0.75"));

        String content = String.join("\n", emitted);
        assertEveryKeyEmitted(content, WormholesConfigFile.class);
    }

    @Test
    void allValuesRoundTripThroughCanonicalWrite() throws IOException {
        File file = tempDir.resolve("wormholes.toml").toFile();
        WormholesConfigFile created = new WormholesConfigFile();
        created.network.enabled = true;
        created.network.listenPort = 9001;
        created.network.trustOnFirstUse = false;
        created.network.transport.compressionLevel = 7;
        created.main.enableParticles = false;
        created.projection.range = 72.0D;
        created.render.entitySpoofing = false;
        TomlCodec.writeCanonical(file, created);

        String written = Files.readString(file.toPath(), StandardCharsets.UTF_8);
        assertTrue(written.contains("enable-particles = false"));
        assertTrue(written.contains("[network.transport]"));
        assertTrue(written.contains("compression-level = 7"));
        assertTrue(written.contains("compression-enabled = true"));
        assertTrue(written.contains("[projection]"));

        TomlCodec.LoadResult<WormholesConfigFile> result = TomlCodec.readExisting(file, WormholesConfigFile.class);
        assertTrue(result.isSuccess());
        assertEquals(9001, result.value().network.listenPort);
        assertFalse(result.value().network.trustOnFirstUse);
        assertEquals(7, result.value().network.transport.compressionLevel);
        assertFalse(result.value().main.enableParticles);
        assertEquals(72.0D, result.value().projection.range);
        assertFalse(result.value().render.entitySpoofing);
    }

    @Test
    void existingConfigGainsNewlyVisibleKeysOnLoadWithoutLosingValues() throws IOException {
        Path config = tempDir.resolve("config").resolve(WormholesSettings.CONFIG_FILE_NAME);
        Files.createDirectories(config.getParent());
        Files.writeString(config, "schema = 2\n[main]\nlanguage = \"de_DE\"\n[projection]\nrange = 72.0\n", StandardCharsets.UTF_8);

        WormholesSettings settings = WormholesSettings.loadAll(tempDir);

        assertEquals("de_DE", settings.getMain().language);
        assertEquals(72.0D, settings.getProjection().range);
        List<String> emitted = emittedSettings(config);
        assertTrue(emitted.contains("language = \"de_DE\""));
        assertTrue(emitted.contains("range = 72.0"));
        assertTrue(emitted.contains("aperture-padding-blocks = 0.75"));
        assertTrue(emitted.contains("[network.transport]"));
        String content = String.join("\n", emitted);
        assertEveryKeyEmitted(content, WormholesConfigFile.class);
    }

    @Test
    void dimensionalDoorToggleRoundTripsAndRefreshesLiveSettings() throws IOException {
        Path config = tempDir.resolve("config").resolve(WormholesSettings.CONFIG_FILE_NAME);
        Files.createDirectories(config.getParent());
        Files.writeString(config, "schema = 2\n[main]\ndimensional-doors-enabled = false\n", StandardCharsets.UTF_8);

        WormholesSettings disabled = WormholesSettings.loadAll(tempDir);
        Settings.refresh(disabled);
        assertFalse(disabled.getMain().dimensionalDoorsEnabled);
        assertFalse(Settings.DIMENSIONAL_DOORS_ENABLED);

        Files.writeString(config, "schema = 2\n[main]\ndimensional-doors-enabled = true\n", StandardCharsets.UTF_8);
        WormholesSettings enabled = WormholesSettings.loadAll(tempDir);
        Settings.refresh(enabled);
        assertTrue(enabled.getMain().dimensionalDoorsEnabled);
        assertTrue(Settings.DIMENSIONAL_DOORS_ENABLED);
    }

    @Test
    void schemaLessConsolidatedFileIsRejectedWithoutBeingRewritten() throws IOException {
        Path configDir = tempDir.resolve("config");
        Files.createDirectories(configDir);
        Path file = configDir.resolve(WormholesSettings.CONFIG_FILE_NAME);
        Files.writeString(file, """
            [main]
            enable-particles = false
            portal-construct-speed = 0.8
            replace-nether-and-end-portals = false

            [network]
            enabled = true
            listen-port = 8950
            trust-on-first-use = false

            [network.transport]
            compression-level = 6

            [projection]
            range = 76.0

            [render]
            entity-spoofing = false
            """, StandardCharsets.UTF_8);

        String original = Files.readString(file, StandardCharsets.UTF_8);
        assertThrows(IllegalArgumentException.class, () -> WormholesSettings.loadAll(tempDir));
        assertEquals(original, Files.readString(file, StandardCharsets.UTF_8));
    }

    @Test
    void separateLegacyFilesAreIgnoredAndLeftUntouched() throws IOException {
        Path configDir = tempDir.resolve("config");
        Files.createDirectories(configDir);

        NetworkConfig network = new NetworkConfig();
        network.enabled = true;
        network.listenPort = 8950;
        network.transport.compressionLevel = 8;
        TomlCodec.writeCanonical(configDir.resolve("network.toml").toFile(), network);

        WormholesSettings settings = WormholesSettings.loadAll(tempDir);

        assertFalse(settings.getNetwork().enabled);
        assertEquals(8901, settings.getNetwork().listenPort);
        assertTrue(Files.isRegularFile(configDir.resolve("network.toml")));
        assertTrue(Files.isRegularFile(configDir.resolve(WormholesSettings.CONFIG_FILE_NAME)));
    }

    @Test
    void allQualityProfilesAreAcceptedAndAutoKeepsCurrentFidelity() throws IOException {
        Path config = tempDir.resolve("config").resolve(WormholesSettings.CONFIG_FILE_NAME);
        for (VisualQualityProfile profile : VisualQualityProfile.values()) {
            Files.createDirectories(config.getParent());
            Files.writeString(config, "schema = 2\nquality = \"" + profile.configValue() + "\"\n", StandardCharsets.UTF_8);
            WormholesSettings settings = WormholesSettings.loadAll(tempDir);
            assertEquals(profile, settings.getVisualQualityProfile());
        }

        Files.writeString(config, "schema = 2\nquality = \"performance\"\n", StandardCharsets.UTF_8);
        WormholesSettings performance = WormholesSettings.loadAll(tempDir);
        Settings.refresh(performance);
        assertFalse(Settings.LIGHTING_FIDELITY);
        assertFalse(Settings.ENTITY_SPOOFING);
        assertEquals(32.0D, Settings.PROJECTION_RANGE);

        Files.writeString(config, "schema = 2\nquality = \"balanced\"\n", StandardCharsets.UTF_8);
        WormholesSettings balanced = WormholesSettings.loadAll(tempDir);
        Settings.refresh(balanced);
        assertEquals(2, Settings.ENTITY_UPDATE_INTERVAL_TICKS);
        assertEquals(16, Settings.MAX_SPOOFED_ENTITIES);

        Files.writeString(config, "schema = 2\nquality = \"cinematic\"\n", StandardCharsets.UTF_8);
        WormholesSettings cinematic = WormholesSettings.loadAll(tempDir);
        Settings.refresh(cinematic);
        assertEquals(64.0D, Settings.PROJECTION_RANGE);
        assertEquals(96, Settings.PROJECTION_DEPTH_BLOCKS);
        assertEquals(48, Settings.MAX_SPOOFED_ENTITIES);
        assertEquals(2, Settings.LIGHTING_REFRESH_INTERVAL_TICKS);

        Files.writeString(config, "schema = 2\nquality = \"auto\"\n", StandardCharsets.UTF_8);
        WormholesSettings automatic = WormholesSettings.loadAll(tempDir);
        Settings.refresh(automatic);
        assertFalse(Settings.LIGHTING_FIDELITY);
        assertTrue(Settings.ADAPTIVE_LIGHTING);
        assertTrue(Settings.ENTITY_SPOOFING);
        assertEquals(1, Settings.PROJECTION_REFRESH_INTERVAL_TICKS);
    }

    @Test
    void malformedExistingConfigIsRejectedWithoutBeingRewritten() throws IOException {
        Path config = tempDir.resolve("config").resolve(WormholesSettings.CONFIG_FILE_NAME);
        Files.createDirectories(config.getParent());
        String malformed = "schema = 2\nquality = \"unterminated\n";
        Files.writeString(config, malformed, StandardCharsets.UTF_8);

        assertThrows(IllegalStateException.class, () -> WormholesSettings.loadAll(tempDir));
        assertEquals(malformed, Files.readString(config, StandardCharsets.UTF_8));
    }

    @Test
    void unknownQualityAndUnsupportedSchemasAreRejected() throws IOException {
        Path config = tempDir.resolve("config").resolve(WormholesSettings.CONFIG_FILE_NAME);
        Files.createDirectories(config.getParent());
        Files.writeString(config, "schema = 2\nquality = \"ultra\"\n", StandardCharsets.UTF_8);
        assertThrows(IllegalArgumentException.class, () -> WormholesSettings.loadAll(tempDir));

        Files.writeString(config, "schema = 99\nquality = \"auto\"\n", StandardCharsets.UTF_8);
        assertThrows(IllegalArgumentException.class, () -> WormholesSettings.loadAll(tempDir));

        Files.writeString(config, "schema = 1\nquality = \"auto\"\n", StandardCharsets.UTF_8);
        assertThrows(IllegalArgumentException.class, () -> WormholesSettings.loadAll(tempDir));
    }

    private static List<String> emittedSettings(Path file) throws IOException {
        return Files.readAllLines(file, StandardCharsets.UTF_8).stream()
            .map(String::trim)
            .filter(line -> !line.isEmpty() && !line.startsWith("#"))
            .toList();
    }

    private static void assertEveryKeyEmitted(String content, Class<?> type) {
        for (Field field : type.getDeclaredFields()) {
            int modifiers = field.getModifiers();
            if (Modifier.isStatic(modifiers) || Modifier.isTransient(modifiers)) {
                continue;
            }
            if (List.class.isAssignableFrom(field.getType())) {
                continue;
            }
            if (field.getType().getName().startsWith("art.arcane.wormholes.config.toml")) {
                assertEveryKeyEmitted(content, field.getType());
                continue;
            }
            String key = kebabKey(field.getName());
            assertTrue(content.contains(key + " = "), "missing config key: " + key);
        }
    }

    private static String kebabKey(String fieldName) {
        StringBuilder out = new StringBuilder(fieldName.length() + 4);
        for (int index = 0; index < fieldName.length(); index++) {
            char character = fieldName.charAt(index);
            if (Character.isUpperCase(character)) {
                if (index > 0) {
                    out.append('-');
                }
                out.append(Character.toLowerCase(character));
            } else {
                out.append(character);
            }
        }
        return out.toString();
    }
}
