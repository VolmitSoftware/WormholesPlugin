package art.arcane.wormholes.localization;

import art.arcane.volmlib.util.director.help.DirectorHelpMessages;
import art.arcane.volmlib.util.director.runtime.DirectorRuntimeMessages;
import art.arcane.volmlib.util.localization.LinesKey;
import art.arcane.volmlib.util.localization.LocalizationReloadResult;
import art.arcane.volmlib.util.localization.LocalizationSnapshot;
import art.arcane.volmlib.util.localization.MessageArgs;
import art.arcane.volmlib.util.localization.MessageArgument;
import art.arcane.volmlib.util.localization.MessageKey;
import art.arcane.volmlib.util.localization.PluralKey;
import art.arcane.volmlib.util.localization.TextKey;
import art.arcane.volmlib.util.localization.VolmitLocales;
import net.kyori.adventure.text.Component;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WormholesLocalizationTest {
    @TempDir
    Path tempDir;

    @Test
    void everyBundledLocaleFullyCoversTheTypedCatalog() {
        WormholesLocalization localization = new WormholesLocalization();
        for (String locale : VolmitLocales.nonEnglish()) {
            LocalizationReloadResult result = localization.reload(tempDir, locale, "");

            assertTrue(result.applied(), locale + ": " + result.failure());
            for (MessageKey key : WormholesMessages.catalog().keys()) {
                assertEquals(locale, localization.snapshot().sourceLocale(key), locale + ":" + key.id());
            }
        }
    }

    @Test
    void bundledResourceSetExactlyMatchesSharedManifest() throws IOException {
        Set<String> expected = VolmitLocales.nonEnglish().stream()
                .map(locale -> locale + ".toml")
                .collect(Collectors.toUnmodifiableSet());
        try (Stream<Path> paths = Files.list(Path.of("src/main/resources/languages"))) {
            Set<String> actual = paths
                    .filter(Files::isRegularFile)
                    .map(path -> path.getFileName().toString())
                    .collect(Collectors.toUnmodifiableSet());
            assertEquals(expected, actual);
        }
        assertFalse(expected.contains(VolmitLocales.ENGLISH + ".toml"));
    }

    @Test
    void overlayFallbackAndDirectorTextRemainInMemoryAfterFilesDisappear() throws IOException {
        writeLocale("custom_ES", """
            schema = 1
            locale = "custom_ES"

            [text]
            "portal.deleted" = "<red>{portal} eliminado"
            "director.help.navigation.back" = "Atrás"

            [lines]
            "command.public_help" = ["Ayuda de portales", "Usa la varita"]

            [plural."command.admin.deleted_portals"]
            one = "{count} portal borrado"
            other = "{count} portales borrados"
            """);
        writeLocale("fr_FR", """
            schema = 1
            locale = "fr_FR"

            [text]
            "command.error.usage" = "<gray>Utilisation : <white>/wormholes help"
            """);

        WormholesLocalization localization = new WormholesLocalization();
        LocalizationReloadResult result = localization.reload(tempDir, "custom_ES", "fr_FR");

        assertTrue(result.applied(), String.valueOf(result.failure()));
        assertEquals("Entrada eliminado", localization.plain(
                WormholesMessages.PORTAL_DELETED,
                WormholesLocalization.args(MessageArgument.untrusted("portal", "Entrada"))));
        assertEquals("Utilisation : /wormholes help", localization.plain(WormholesMessages.COMMAND_USAGE_HELP));
        assertEquals("Ayuda de portales", localization.legacyLines(WormholesMessages.COMMAND_PUBLIC_HELP).getFirst());
        assertEquals("2 portales borrados", localization.plain(
                WormholesMessages.COMMAND_DELETED_PORTALS,
                WormholesLocalization.args(MessageArgument.untrusted("count", Long.valueOf(2L)))));
        assertEquals("Atrás", localization.directorResolver().resolve(DirectorHelpMessages.BACK));
        String runtimeText = localization.directorResolver().resolve(
                DirectorRuntimeMessages.UNKNOWN_PARAMETER,
                MessageArgs.builder().untrusted("key", "<red>Bad</red>\u00A7cName").build()
        );
        assertTrue(runtimeText.contains("<red>Bad</red>"));
        assertFalse(runtimeText.contains("\u00A7"));

        Files.delete(tempDir.resolve("languages").resolve("custom_ES.toml"));
        Files.delete(tempDir.resolve("languages").resolve("fr_FR.toml"));

        assertEquals("Entrada eliminado", localization.plain(
                WormholesMessages.PORTAL_DELETED,
                WormholesLocalization.args(MessageArgument.untrusted("portal", "Entrada"))));
        assertEquals("Utilisation : /wormholes help", localization.plain(WormholesMessages.COMMAND_USAGE_HELP));
    }

    @Test
    void untrustedMiniMessageInputCannotInstallClickEvents() {
        WormholesLocalization localization = WormholesLocalization.english();
        String portalName = "<click:run_command:'/op attacker'>Owned</click>";
        Component rendered = localization.component(
                WormholesMessages.PORTAL_DELETED,
                WormholesLocalization.args(MessageArgument.untrusted("portal", portalName)));

        assertFalse(hasClickEvent(rendered));
        assertTrue(localization.plain(
                WormholesMessages.PORTAL_DELETED,
                WormholesLocalization.args(MessageArgument.untrusted("portal", portalName))).contains(portalName));
    }

    @Test
    void everyEnglishCatalogEntryRendersWithItsDeclaredArguments() {
        WormholesLocalization localization = WormholesLocalization.english();

        for (MessageKey key : WormholesMessages.catalog().keys()) {
            MessageArgs arguments = renderArguments(key);
            assertDoesNotThrow(() -> render(localization, key, arguments), key.id());
        }
    }

    @Test
    void invalidPlaceholderRetainsExactLastGoodSnapshot() throws IOException {
        writeLocale("es_ES", """
            schema = 1
            locale = "es_ES"

            [text]
            "portal.deleted" = "<red>{portal} eliminado"
            """);
        WormholesLocalization localization = new WormholesLocalization();
        LocalizationReloadResult applied = localization.reload(tempDir, "es_ES", "");
        assertTrue(applied.applied(), String.valueOf(applied.failure()));
        LocalizationSnapshot lastGood = localization.snapshot();

        writeLocale("es_ES", """
            schema = 1
            locale = "es_ES"

            [text]
            "portal.deleted" = "<red>{name} eliminado"
            """);
        LocalizationReloadResult rejected = localization.reload(tempDir, "es_ES", "");

        assertFalse(rejected.applied());
        assertSame(lastGood, rejected.previous());
        assertSame(lastGood, rejected.current());
        assertSame(lastGood, localization.snapshot());
        assertTrue(rejected.failure() != null);
        assertEquals("Entrada eliminado", localization.plain(
                WormholesMessages.PORTAL_DELETED,
                WormholesLocalization.args(MessageArgument.untrusted("portal", "Entrada"))));
    }

    @Test
    void malformedSchemaAndUnknownKeysAreRejected() throws IOException {
        writeLocale("es_ES", """
            schema = 2
            locale = "es_ES"

            [text]
            "portal.deleted" = "<red>{portal} eliminado"
            """);
        WormholesLocalization localization = new WormholesLocalization();
        LocalizationSnapshot initial = localization.snapshot();

        LocalizationReloadResult schemaRejected = localization.reload(tempDir, "es_ES", "");
        assertFalse(schemaRejected.applied());
        assertSame(initial, localization.snapshot());

        writeLocale("es_ES", """
            schema = 1
            locale = "es_ES"

            [text]
            "portal.unknown" = "Desconocido"
            """);
        LocalizationReloadResult keyRejected = localization.reload(tempDir, "es_ES", "");
        assertFalse(keyRejected.applied());
        assertSame(initial, localization.snapshot());
        assertNull(keyRejected.current().catalog().key("portal.unknown"));
    }

    private void writeLocale(String locale, String contents) throws IOException {
        Path languages = tempDir.resolve("languages");
        Files.createDirectories(languages);
        Files.writeString(languages.resolve(locale + ".toml"), contents, StandardCharsets.UTF_8);
    }

    private MessageArgs renderArguments(MessageKey key) {
        MessageArgs.Builder arguments = MessageArgs.builder();
        for (String placeholder : key.placeholders()) {
            Object value = key instanceof PluralKey pluralKey && placeholder.equals(pluralKey.selectorArgument())
                    ? Long.valueOf(2L)
                    : "sample";
            arguments.add(MessageArgument.untrusted(placeholder, value));
        }
        return arguments.build();
    }

    private void render(WormholesLocalization localization, MessageKey key, MessageArgs arguments) {
        if (key instanceof TextKey textKey) {
            localization.component(textKey, arguments);
            return;
        }
        if (key instanceof LinesKey linesKey) {
            localization.components(linesKey, arguments);
            return;
        }
        localization.component((PluralKey) key, arguments);
    }

    private boolean hasClickEvent(Component component) {
        if (component.clickEvent() != null) {
            return true;
        }
        for (Component child : component.children()) {
            if (hasClickEvent(child)) {
                return true;
            }
        }
        return false;
    }
}
