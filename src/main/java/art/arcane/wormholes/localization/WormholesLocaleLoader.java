package art.arcane.wormholes.localization;

import art.arcane.volmlib.util.localization.LocaleOverlay;
import art.arcane.volmlib.util.localization.LocalizationCandidate;
import art.arcane.volmlib.util.localization.PluralSelector;
import art.arcane.volmlib.util.localization.VolmitLocales;
import com.moandjiezana.toml.Toml;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

final class WormholesLocaleLoader {
    static final int SCHEMA = 1;

    private static final Pattern LOCALE_PATTERN = Pattern.compile("[A-Za-z0-9][A-Za-z0-9_-]*");
    private static final Set<String> ROOT_KEYS = Set.of("schema", "locale", "text", "lines", "plural");

    private WormholesLocaleLoader() {
    }

    static LocalizationCandidate load(Path dataFolder, String locale, String fallbackLocales) throws IOException {
        Path languageFolder = dataFolder.resolve("languages");
        List<String> requestedLocales = requestedLocales(locale, fallbackLocales);
        List<LocaleOverlay> overlays = new ArrayList<>(requestedLocales.size() * 2);
        for (String requestedLocale : requestedLocales) {
            if (requestedLocale.equalsIgnoreCase(WormholesMessages.ENGLISH_LOCALE)) {
                continue;
            }

            boolean loaded = false;
            Path file = languageFile(languageFolder, requestedLocale);
            if (Files.isRegularFile(file)) {
                overlays.add(loadFileOverlay(file, requestedLocale));
                loaded = true;
            }
            LocaleOverlay bundled = loadBundledOverlay(requestedLocale);
            if (bundled != null) {
                overlays.add(bundled);
                loaded = true;
            }
            if (!loaded) {
                throw new IOException("Language file does not exist and no bundled language is available: " + requestedLocale);
            }
        }
        return new LocalizationCandidate(WormholesMessages.catalog(), overlays, PluralSelector.oneOther());
    }

    private static List<String> requestedLocales(String locale, String fallbackLocales) {
        LinkedHashSet<String> locales = new LinkedHashSet<>();
        locales.add(requireLocale(locale));
        if (fallbackLocales != null && !fallbackLocales.isBlank()) {
            for (String fallback : fallbackLocales.split(",")) {
                if (!fallback.isBlank()) {
                    locales.add(requireLocale(fallback));
                }
            }
        }
        return List.copyOf(locales);
    }

    private static Path languageFile(Path languageFolder, String locale) {
        Path file = languageFolder.resolve(locale + ".toml").normalize();
        if (!file.getParent().equals(languageFolder.normalize())) {
            throw new IllegalArgumentException("Language file must stay inside the languages directory: " + locale);
        }
        return file;
    }

    private static LocaleOverlay loadFileOverlay(Path file, String locale) throws IOException {
        Toml toml;
        try {
            toml = new Toml().read(file.toFile());
        } catch (RuntimeException exception) {
            throw new IOException("Could not parse language file: " + file, exception);
        }
        return loadOverlay(toml, file.toString(), locale);
    }

    private static LocaleOverlay loadBundledOverlay(String locale) throws IOException {
        String resourcePath = "/languages/" + locale + ".toml";
        InputStream input = WormholesLocaleLoader.class.getResourceAsStream(resourcePath);
        if (input == null) {
            if (VolmitLocales.isBundled(locale)) {
                throw new IOException("Missing bundled language resource: " + resourcePath);
            }
            return null;
        }

        try (InputStream stream = input) {
            Toml toml;
            try {
                toml = new Toml().read(stream);
            } catch (RuntimeException exception) {
                throw new IOException("Could not parse bundled language resource: " + resourcePath, exception);
            }
            return loadOverlay(toml, resourcePath, locale);
        }
    }

    private static LocaleOverlay loadOverlay(Toml toml, String source, String locale) {
        validateRoot(toml, source, locale);
        LocaleOverlay.Builder overlay = LocaleOverlay.builder(source, locale);
        readText(toml.getTable("text"), overlay, source);
        readLines(toml.getTable("lines"), overlay, source);
        readPlurals(toml.getTable("plural"), overlay, source);
        return overlay.build();
    }

    private static void validateRoot(Toml toml, String source, String locale) {
        Long schema = toml.getLong("schema");
        if (schema == null || schema.longValue() != SCHEMA) {
            throw new IllegalArgumentException("Unsupported language schema in " + source + "; expected " + SCHEMA);
        }
        String declaredLocale = toml.getString("locale");
        if (declaredLocale == null || !declaredLocale.equalsIgnoreCase(locale)) {
            throw new IllegalArgumentException("Language source " + source + " must declare locale = \"" + locale + "\"");
        }
        for (Map.Entry<String, Object> entry : toml.entrySet()) {
            if (!ROOT_KEYS.contains(entry.getKey())) {
                throw new IllegalArgumentException("Unknown language root key in " + source + ": " + entry.getKey());
            }
        }
    }

    private static void readText(Toml table, LocaleOverlay.Builder overlay, String source) {
        if (table == null) {
            return;
        }
        for (Map.Entry<String, Object> entry : table.entrySet()) {
            if (!(entry.getValue() instanceof String template)) {
                throw invalidValue(source, "text", entry.getKey(), "a string");
            }
            overlay.text(messageId(entry.getKey()), template);
        }
    }

    private static void readLines(Toml table, LocaleOverlay.Builder overlay, String source) {
        if (table == null) {
            return;
        }
        for (Map.Entry<String, Object> entry : table.entrySet()) {
            if (!(entry.getValue() instanceof List<?> rawLines)) {
                throw invalidValue(source, "lines", entry.getKey(), "an array of strings");
            }
            List<String> lines = new ArrayList<>(rawLines.size());
            for (Object rawLine : rawLines) {
                if (!(rawLine instanceof String line)) {
                    throw invalidValue(source, "lines", entry.getKey(), "an array of strings");
                }
                lines.add(line);
            }
            overlay.lines(messageId(entry.getKey()), lines);
        }
    }

    private static void readPlurals(Toml table, LocaleOverlay.Builder overlay, String source) {
        if (table == null) {
            return;
        }
        for (Map.Entry<String, Object> entry : table.entrySet()) {
            Map<?, ?> rawForms;
            if (entry.getValue() instanceof Toml formTable) {
                rawForms = formTable.toMap();
            } else if (entry.getValue() instanceof Map<?, ?> formMap) {
                rawForms = formMap;
            } else {
                throw invalidValue(source, "plural", entry.getKey(), "a table of plural forms");
            }
            LinkedHashMap<String, String> forms = new LinkedHashMap<>();
            for (Map.Entry<?, ?> rawForm : rawForms.entrySet()) {
                if (!(rawForm.getKey() instanceof String category) || !(rawForm.getValue() instanceof String template)) {
                    throw invalidValue(source, "plural", entry.getKey(), "a table of string plural forms");
                }
                forms.put(category, template);
            }
            overlay.plural(messageId(entry.getKey()), forms);
        }
    }

    private static String messageId(String rawKey) {
        if (rawKey.length() >= 2) {
            char first = rawKey.charAt(0);
            char last = rawKey.charAt(rawKey.length() - 1);
            if ((first == '"' && last == '"') || (first == '\'' && last == '\'')) {
                return rawKey.substring(1, rawKey.length() - 1);
            }
        }
        return rawKey;
    }

    private static IllegalArgumentException invalidValue(String source, String section, String key, String expected) {
        return new IllegalArgumentException("Language value " + section + ".\"" + key + "\" in " + source + " must be " + expected);
    }

    private static String requireLocale(String locale) {
        if (locale == null || !LOCALE_PATTERN.matcher(locale.trim()).matches()) {
            throw new IllegalArgumentException("Invalid language locale: " + locale);
        }
        return locale.trim();
    }
}
