package art.arcane.wormholes.util;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

public final class VIO {
    private VIO() {
    }

    public static String readAll(File f) throws IOException {
        return Files.readString(f.toPath(), StandardCharsets.UTF_8);
    }

    public static void writeAll(File f, Object content) throws IOException {
        Path target = f.toPath();
        Path directory = target.toAbsolutePath().getParent();
        if (directory != null) {
            Files.createDirectories(directory);
        }
        Path temporary = Files.createTempFile(directory, f.getName() + ".", ".tmp");
        try {
            Files.writeString(temporary, String.valueOf(content), StandardCharsets.UTF_8);
            try {
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }
}
