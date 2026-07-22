package art.arcane.wormholes.localization;

import art.arcane.volmlib.util.director.DirectorTextResolver;
import art.arcane.volmlib.util.inventorygui.Element;
import art.arcane.volmlib.util.localization.LinesKey;
import art.arcane.volmlib.util.localization.LocalizationCandidate;
import art.arcane.volmlib.util.localization.LocalizationManager;
import art.arcane.volmlib.util.localization.LocalizationReloadResult;
import art.arcane.volmlib.util.localization.LocalizationSnapshot;
import art.arcane.volmlib.util.localization.MessageArgs;
import art.arcane.volmlib.util.localization.MessageArgument;
import art.arcane.volmlib.util.localization.MessageArgumentKind;
import art.arcane.volmlib.util.localization.MessageKey;
import art.arcane.volmlib.util.localization.PluralKey;
import art.arcane.volmlib.util.localization.PluralSelector;
import art.arcane.volmlib.util.localization.ResolvedLines;
import art.arcane.volmlib.util.localization.ResolvedText;
import art.arcane.volmlib.util.localization.TextKey;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public final class WormholesLocalization {
    private static final MiniMessage MINI_MESSAGE = MiniMessage.miniMessage();
    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacySection();
    private static final PlainTextComponentSerializer PLAIN = PlainTextComponentSerializer.plainText();
    private static final WormholesLocalization ENGLISH = new WormholesLocalization();

    private final LocalizationManager manager;

    public WormholesLocalization() {
        manager = new LocalizationManager(LocalizationCandidate.english(
                WormholesMessages.catalog(),
                PluralSelector.oneOther()
        ));
    }

    public static WormholesLocalization english() {
        return ENGLISH;
    }

    public static MessageArgs args(MessageArgument... arguments) {
        MessageArgs.Builder builder = MessageArgs.builder();
        for (MessageArgument argument : arguments) {
            builder.add(argument);
        }
        return builder.build();
    }

    public LocalizationReloadResult reload(Path dataFolder, String locale, String fallbackLocales) {
        return manager.reload(() -> WormholesLocaleLoader.load(dataFolder, locale, fallbackLocales));
    }

    public LocalizationSnapshot snapshot() {
        return manager.snapshot();
    }

    public Component component(TextKey key) {
        return component(key, MessageArgs.empty());
    }

    public Component component(TextKey key, MessageArgs arguments) {
        return deserialize(manager.snapshot().resolve(key, arguments));
    }

    public Component component(PluralKey key, MessageArgs arguments) {
        return deserialize(manager.snapshot().resolve(key, arguments));
    }

    public String legacy(TextKey key) {
        return legacy(key, MessageArgs.empty());
    }

    public String legacy(TextKey key, MessageArgs arguments) {
        return LEGACY.serialize(component(key, arguments));
    }

    public String legacy(PluralKey key, MessageArgs arguments) {
        return LEGACY.serialize(component(key, arguments));
    }

    public String plain(TextKey key) {
        return PLAIN.serialize(component(key));
    }

    public String plain(TextKey key, MessageArgs arguments) {
        return PLAIN.serialize(component(key, arguments));
    }

    public String plain(PluralKey key, MessageArgs arguments) {
        return PLAIN.serialize(component(key, arguments));
    }

    public List<Component> components(LinesKey key) {
        return components(key, MessageArgs.empty());
    }

    public List<Component> components(LinesKey key, MessageArgs arguments) {
        ResolvedLines resolved = manager.snapshot().resolve(key, arguments);
        List<Component> components = new ArrayList<>(resolved.lines().size());
        for (String line : resolved.lines()) {
            components.add(MINI_MESSAGE.deserialize(substitute(line, resolved.arguments())));
        }
        return List.copyOf(components);
    }

    public List<String> legacyLines(LinesKey key) {
        return legacyLines(key, MessageArgs.empty());
    }

    public List<String> legacyLines(LinesKey key, MessageArgs arguments) {
        List<Component> components = components(key, arguments);
        List<String> lines = new ArrayList<>(components.size());
        for (Component component : components) {
            lines.add(LEGACY.serialize(component));
        }
        return List.copyOf(lines);
    }

    public void apply(Element element, LinesKey key) {
        apply(element, key, MessageArgs.empty());
    }

    public void apply(Element element, LinesKey key, MessageArgs arguments) {
        List<String> lines = legacyLines(key, arguments);
        element.setName(lines.getFirst());
        element.getLore().clear();
        for (int index = 1; index < lines.size(); index++) {
            element.addLore(lines.get(index));
        }
    }

    public DirectorTextResolver directorResolver() {
        return this::directorText;
    }

    public String directorText(TextKey key, MessageArgs arguments) {
        MessageKey definition = manager.snapshot().catalog().key(key.id());
        if (!(definition instanceof TextKey textKey)) {
            return DirectorTextResolver.ENGLISH.resolve(key, arguments);
        }
        return plain(textKey, arguments);
    }

    private Component deserialize(ResolvedText resolved) {
        return MINI_MESSAGE.deserialize(substitute(resolved.template(), resolved.arguments()));
    }

    private String substitute(String template, MessageArgs arguments) {
        StringBuilder rendered = new StringBuilder(template.length() + arguments.size() * 8);
        for (int index = 0; index < template.length(); index++) {
            char current = template.charAt(index);
            if (current == '{' && index + 1 < template.length() && template.charAt(index + 1) == '{') {
                rendered.append('{');
                index++;
                continue;
            }
            if (current == '}' && index + 1 < template.length() && template.charAt(index + 1) == '}') {
                rendered.append('}');
                index++;
                continue;
            }
            if (current != '{') {
                rendered.append(current);
                continue;
            }
            int end = template.indexOf('}', index + 1);
            String name = template.substring(index + 1, end);
            MessageArgument argument = arguments.require(name);
            String value = String.valueOf(argument.value());
            rendered.append(argument.kind() == MessageArgumentKind.UNTRUSTED ? escapeUntrusted(value) : value);
            index = end;
        }
        return rendered.toString();
    }

    private String escapeUntrusted(String value) {
        return MINI_MESSAGE.escapeTags(PLAIN.serialize(LEGACY.deserialize(value)));
    }
}
