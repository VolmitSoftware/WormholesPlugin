package art.arcane.wormholes.util.project.config;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import art.arcane.wormholes.config.toml.MainConfig;

public final class TomlCodecTest {
    @TempDir
    private Path tempDir;

    @Test
    public void loadOrCreateDoesNotRewriteUnchangedCanonicalFile() throws Exception {
        File file = tempDir.resolve("main.toml").toFile();
        TomlCodec.writeCanonical(file, new MainConfig());
        FileTime marker = FileTime.fromMillis(1_700_000_000_000L);
        Files.setLastModifiedTime(file.toPath(), marker);

        TomlCodec.loadOrCreate(file, MainConfig.class);

        assertEquals(marker.toMillis(), Files.getLastModifiedTime(file.toPath()).toMillis());
    }

    @Test
    public void loadOrCreateStillCanonicalizesChangedFileContent() throws Exception {
        File file = tempDir.resolve("main.toml").toFile();
        TomlCodec.writeCanonical(file, new MainConfig());
        String canonical = Files.readString(file.toPath());
        Files.writeString(file.toPath(), canonical + "\n");

        TomlCodec.loadOrCreate(file, MainConfig.class);

        assertEquals(canonical, Files.readString(file.toPath()));
    }
}
