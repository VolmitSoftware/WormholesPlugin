package art.arcane.wormholes.util;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

public final class VIO {
    private VIO() {
    }

    public static String readAll(File f) throws IOException {
        return Files.readString(f.toPath(), StandardCharsets.UTF_8);
    }

    public static void writeAll(File f, Object content) throws IOException {
        File parent = f.getParentFile();
        if (parent != null && !parent.exists()) {
            parent.mkdirs();
        }
        Files.writeString(f.toPath(), String.valueOf(content), StandardCharsets.UTF_8);
    }
}
