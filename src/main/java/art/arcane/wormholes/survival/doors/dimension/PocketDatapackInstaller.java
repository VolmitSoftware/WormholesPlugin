package art.arcane.wormholes.survival.doors.dimension;

import java.io.IOException;
import java.io.InputStream;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.Enumeration;
import java.util.List;
import java.util.Objects;
import java.util.Properties;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipException;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

public final class PocketDatapackInstaller {
    private static final String RESOURCE_ROOT = "/wormholes-pockets-pack/";
    private static final String PACK_FILE_NAME = "wormholes-pockets.zip";
    private static final List<String> PACK_FILES = List.of(
        "pack.mcmeta",
        "data/wormholes/dimension/pockets.json",
        "data/wormholes/dimension_type/fullbright_pockets.json"
    );
    private static final Set<String> PACK_FILE_SET = Set.copyOf(PACK_FILES);

    private PocketDatapackInstaller() {
    }

    public static StagedDatapack stageConfigured(Path serverRoot) throws IOException {
        String[] arguments = ProcessHandle.current().info().arguments().orElse(new String[0]);
        return stageConfigured(serverRoot, arguments);
    }

    static StagedDatapack stageConfigured(Path serverRoot, String[] arguments) throws IOException {
        Path levelRoot = resolveLevelRoot(serverRoot, arguments);
        Files.createDirectories(levelRoot);
        InstallResult status = install(levelRoot);
        return new StagedDatapack(status, levelRoot, packPath(levelRoot));
    }

    static Path resolveLevelRoot(Path serverRoot, String[] arguments) throws IOException {
        Path normalizedServerRoot = Objects.requireNonNull(serverRoot, "serverRoot").toAbsolutePath().normalize();
        String levelName = readConfiguredLevelName(normalizedServerRoot, Objects.requireNonNull(arguments, "arguments"));
        Path configured = Path.of(levelName);
        return configured.isAbsolute()
            ? configured.normalize()
            : normalizedServerRoot.resolve(configured).normalize();
    }

    public static InstallResult install(Path levelRoot) throws IOException {
        Path root = Objects.requireNonNull(levelRoot, "levelRoot");
        Path datapacks = root.resolve("datapacks");
        Path destination = datapacks.resolve(PACK_FILE_NAME);
        try {
            if (!Files.isDirectory(root)) {
                throw new IOException("Level root is not an existing directory: " + root.toAbsolutePath());
            }
            Files.createDirectories(datapacks);
            if (matchesBundledPack(destination)) {
                return InstallResult.UNCHANGED;
            }
            InstallResult result = Files.exists(destination, LinkOption.NOFOLLOW_LINKS) ? InstallResult.UPDATED : InstallResult.INSTALLED;
            replacePack(datapacks, destination);
            return result;
        } catch (IOException exception) {
            throw new IOException("Unable to install the bundled Wormholes pocket datapack into level root " + root.toAbsolutePath(), exception);
        }
    }

    public static Path packPath(Path levelRoot) {
        return Objects.requireNonNull(levelRoot, "levelRoot").resolve("datapacks").resolve(PACK_FILE_NAME);
    }

    private static boolean matchesBundledPack(Path pack) throws IOException {
        if (!Files.isRegularFile(pack, LinkOption.NOFOLLOW_LINKS)) {
            return false;
        }
        try (ZipFile zip = new ZipFile(pack.toFile())) {
            Enumeration<? extends ZipEntry> entries = zip.entries();
            int fileCount = 0;
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                if (entry.isDirectory() || !PACK_FILE_SET.contains(entry.getName())) {
                    return false;
                }
                fileCount++;
            }
            if (fileCount != PACK_FILES.size()) {
                return false;
            }
            for (String path : PACK_FILES) {
                ZipEntry entry = zip.getEntry(path);
                if (entry == null || !matchesResource(zip, entry, path)) {
                    return false;
                }
            }
            return true;
        } catch (ZipException exception) {
            return false;
        }
    }

    private static boolean matchesResource(ZipFile zip, ZipEntry entry, String path) throws IOException {
        try (InputStream installed = zip.getInputStream(entry); InputStream bundled = openResource(path)) {
            return Arrays.equals(installed.readAllBytes(), bundled.readAllBytes());
        }
    }

    private static void replacePack(Path datapacks, Path destination) throws IOException {
        Path temporary = Files.createTempFile(datapacks, ".wormholes-pockets-", ".zip.tmp");
        try {
            writePack(temporary);
            try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.WRITE)) {
                channel.force(true);
            }
            Files.move(temporary, destination, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static void writePack(Path output) throws IOException {
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(output, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING))) {
            for (String path : PACK_FILES) {
                ZipEntry entry = new ZipEntry(path);
                entry.setTime(0L);
                zip.putNextEntry(entry);
                try (InputStream resource = openResource(path)) {
                    resource.transferTo(zip);
                }
                zip.closeEntry();
            }
        }
    }

    private static InputStream openResource(String path) throws IOException {
        InputStream resource = PocketDatapackInstaller.class.getResourceAsStream(RESOURCE_ROOT + path);
        if (resource == null) {
            throw new IOException("Bundled Wormholes pocket datapack resource is missing: " + RESOURCE_ROOT + path);
        }
        return resource;
    }

    private static String readConfiguredLevelName(Path serverRoot, String[] arguments) throws IOException {
        String levelName = "world";
        Path propertiesFile = serverRoot.resolve("server.properties");
        if (Files.isRegularFile(propertiesFile)) {
            Properties properties = new Properties();
            try (InputStream input = Files.newInputStream(propertiesFile)) {
                properties.load(input);
            }
            levelName = properties.getProperty("level-name", levelName);
        }
        for (int index = 0; index < arguments.length; index++) {
            String following = index + 1 < arguments.length ? arguments[index + 1] : null;
            String parsed = parseLevelArgument(arguments[index], following);
            if (parsed != null) {
                levelName = parsed;
            }
        }
        if (levelName.isBlank()) {
            throw new IOException("Configured level name is empty");
        }
        return levelName;
    }

    private static String parseLevelArgument(String argument, String following) {
        for (String key : List.of("-w", "--level-name", "--world")) {
            if (argument.equals(key) && following != null && !following.isBlank()) {
                return following;
            }
            String prefix = key + "=";
            if (argument.startsWith(prefix) && argument.length() > prefix.length()) {
                return argument.substring(prefix.length());
            }
        }
        return null;
    }

    public record StagedDatapack(InstallResult status, Path levelRoot, Path packPath) {
        public StagedDatapack {
            Objects.requireNonNull(status, "status");
            Objects.requireNonNull(levelRoot, "levelRoot");
            Objects.requireNonNull(packPath, "packPath");
        }
    }

    public enum InstallResult {
        UNCHANGED,
        INSTALLED,
        UPDATED
    }
}
