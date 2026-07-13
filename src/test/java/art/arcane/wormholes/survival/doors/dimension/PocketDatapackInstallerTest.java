package art.arcane.wormholes.survival.doors.dimension;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.HashSet;
import java.util.Set;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

public final class PocketDatapackInstallerTest {
    private static final Set<String> EXPECTED_FILES = Set.of(
        "pack.mcmeta",
        "data/wormholes/dimension/pockets.json",
        "data/wormholes/dimension_type/fullbright_pockets.json"
    );

    @TempDir
    Path temporaryDirectory;

    @Test
    void installsCompletePackIntoLevelDatapacksDirectory() throws IOException {
        PocketDatapackInstaller.InstallResult result = PocketDatapackInstaller.install(temporaryDirectory);

        assertEquals(PocketDatapackInstaller.InstallResult.INSTALLED, result);
        assertPackMatchesResources(PocketDatapackInstaller.packPath(temporaryDirectory));
        assertEquals(Set.of("wormholes-pockets.zip"), directoryNames(temporaryDirectory.resolve("datapacks")));
    }

    @Test
    void leavesMatchingPackUntouched() throws IOException {
        PocketDatapackInstaller.install(temporaryDirectory);
        Path pack = PocketDatapackInstaller.packPath(temporaryDirectory);
        FileTime preservedTime = FileTime.fromMillis(1_700_000_000_000L);
        Files.setLastModifiedTime(pack, preservedTime);

        PocketDatapackInstaller.InstallResult result = PocketDatapackInstaller.install(temporaryDirectory);

        assertEquals(PocketDatapackInstaller.InstallResult.UNCHANGED, result);
        assertEquals(preservedTime, Files.getLastModifiedTime(pack));
    }

    @Test
    void atomicallyReplacesStalePack() throws IOException {
        Path datapacks = temporaryDirectory.resolve("datapacks");
        Files.createDirectories(datapacks);
        Path pack = PocketDatapackInstaller.packPath(temporaryDirectory);
        Files.writeString(pack, "stale", StandardCharsets.UTF_8);

        PocketDatapackInstaller.InstallResult result = PocketDatapackInstaller.install(temporaryDirectory);

        assertEquals(PocketDatapackInstaller.InstallResult.UPDATED, result);
        assertPackMatchesResources(pack);
        assertEquals(Set.of("wormholes-pockets.zip"), directoryNames(datapacks));
    }

    @Test
    void reportsLevelRootWhenInstallationFails() throws IOException {
        Path invalidLevelRoot = temporaryDirectory.resolve("level.dat");
        Files.writeString(invalidLevelRoot, "not a directory", StandardCharsets.UTF_8);

        IOException exception = assertThrows(IOException.class, () -> PocketDatapackInstaller.install(invalidLevelRoot));

        assertTrue(exception.getMessage().contains("Wormholes pocket datapack"));
        assertTrue(exception.getMessage().contains(invalidLevelRoot.toAbsolutePath().toString()));
        assertTrue(Files.isRegularFile(invalidLevelRoot));
    }

    private static void assertPackMatchesResources(Path pack) throws IOException {
        try (ZipFile zip = new ZipFile(pack.toFile())) {
            Set<String> actualFiles = new HashSet<>();
            zip.stream().filter(entry -> !entry.isDirectory()).forEach(entry -> actualFiles.add(entry.getName()));
            assertEquals(EXPECTED_FILES, actualFiles);
            for (String path : EXPECTED_FILES) {
                ZipEntry entry = zip.getEntry(path);
                try (InputStream actual = zip.getInputStream(entry);
                     InputStream expected = PocketDatapackInstallerTest.class.getResourceAsStream("/wormholes-pockets-pack/" + path)) {
                    assertTrue(expected != null, "Missing test resource " + path);
                    assertEquals(new String(expected.readAllBytes(), StandardCharsets.UTF_8), new String(actual.readAllBytes(), StandardCharsets.UTF_8));
                }
            }
        }
    }

    private static Set<String> directoryNames(Path directory) throws IOException {
        try (Stream<Path> files = Files.list(directory)) {
            Set<String> names = new HashSet<>();
            files.forEach(path -> names.add(path.getFileName().toString()));
            assertFalse(names.isEmpty());
            return names;
        }
    }
}
