package art.arcane.wormholes.config;

import art.arcane.wormholes.config.toml.NetworkConfig;
import art.arcane.wormholes.config.toml.WormholesConfigFile;
import art.arcane.wormholes.util.project.config.TomlCodec;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WormholesConfigFileTest {
    @TempDir
    Path tempDir;

    @Test
    void survivingSettingsRoundTripThroughSingleFile() throws IOException {
        File file = tempDir.resolve("wormholes.toml").toFile();

        WormholesConfigFile created = TomlCodec.loadOrCreate(file, WormholesConfigFile.class);
        assertTrue(file.exists());

        created.network.listenPort = 9001;
        created.network.enabled = true;
        created.main.enableParticles = false;
        created.render.entitySpoofing = false;
        TomlCodec.writeCanonical(file, created);

        WormholesConfigFile reloaded = TomlCodec.loadOrCreate(file, WormholesConfigFile.class);
        assertEquals(9001, reloaded.network.listenPort);
        assertTrue(reloaded.network.enabled);
        assertFalse(reloaded.main.enableParticles);
        assertFalse(reloaded.render.entitySpoofing);
    }

    @Test
    void hardCodedSettingsAreAbsentFromFileButRetainValues() throws IOException {
        File file = tempDir.resolve("wormholes.toml").toFile();
        WormholesConfigFile config = TomlCodec.loadOrCreate(file, WormholesConfigFile.class);

        String written = Files.readString(file.toPath(), StandardCharsets.UTF_8);
        assertFalse(written.contains("portal-construct-speed"), "hard-coded mechanic must not be written to the config");
        assertFalse(written.contains("range ="), "hard-coded projection range must not be written to the config");
        assertFalse(written.contains("compression-enabled"), "hard-coded transport section must not be written to the config");
        assertFalse(written.contains("entity-rate-near-hz"), "hard-coded view tuning must not be written to the config");

        assertEquals(0.975, config.main.portalConstructSpeed);
        assertEquals(48.0, config.render.entitySpoofRange);
        assertTrue(config.network.transport.compressionEnabled);
        assertEquals(20.0, config.network.view.entityRateNearHz);
    }

    @Test
    void migratesLegacyConfigSurvivorsIntoConsolidatedFile() throws IOException {
        File configDir = tempDir.resolve("config").toFile();
        assertTrue(configDir.mkdirs());
        NetworkConfig legacy = new NetworkConfig();
        legacy.enabled = true;
        legacy.listenPort = 8950;
        TomlCodec.writeCanonical(new File(configDir, "network.toml"), legacy);

        WormholesSettings settings = WormholesSettings.loadAll(tempDir);
        assertTrue(settings.getNetwork().enabled, "upgrade must preserve enabled networking");
        assertEquals(8950, settings.getNetwork().listenPort);
        assertTrue(new File(configDir, "wormholes.toml").isFile());
    }

    @Test
    void onlyOperatorSettingsAppearInFile() throws IOException {
        File file = tempDir.resolve("wormholes.toml").toFile();
        TomlCodec.loadOrCreate(file, WormholesConfigFile.class);
        String written = Files.readString(file.toPath(), StandardCharsets.UTF_8);

        assertTrue(written.contains("listen-port"));
        assertTrue(written.contains("enable-particles"));
        assertTrue(written.contains("lighting-fidelity"));
        assertTrue(written.contains("trust-on-first-use"));
        assertTrue(written.contains("advertise-host-override"), "the host-override escape hatch is kept and must be written");
        assertFalse(written.contains("transfer-mode"), "auto-determined transfer mode must not be written");
    }
}
