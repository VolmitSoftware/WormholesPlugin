package art.arcane.wormholes.config;

import java.util.Locale;

public enum VisualQualityProfile {
    AUTO,
    PERFORMANCE,
    BALANCED,
    CINEMATIC;

    public static VisualQualityProfile parse(String value) {
        if (value == null || value.isBlank()) {
            return AUTO;
        }
        try {
            return valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Unknown visual quality profile '" + value + "'. Use auto, performance, balanced, or cinematic.", e);
        }
    }

    public String configValue() {
        return name().toLowerCase(Locale.ROOT);
    }
}
