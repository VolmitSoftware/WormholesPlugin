package art.arcane.wormholes.util.project.config;

import com.moandjiezana.toml.Toml;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.logging.Level;
import java.util.logging.Logger;

public final class TomlCodec {
    private static final Logger LOGGER = Logger.getLogger("Wormholes-TomlCodec");
    private static final int PARSE_RETRY_ATTEMPTS = 4;
    private static final long PARSE_RETRY_BACKOFF_MS = 60L;

    private TomlCodec() {
    }

    public static <T> T loadOrCreate(File tomlFile, Class<T> type) {
        T defaults = newInstance(type);

        if (!tomlFile.exists()) {
            writeCanonical(tomlFile, defaults);
            return defaults;
        }

        LoadResult<T> result = readExisting(tomlFile, type);
        if (!result.isSuccess()) {
            LOGGER.log(Level.WARNING, "Failed to parse " + tomlFile.getName() + " after " + PARSE_RETRY_ATTEMPTS + " attempts; using defaults for this standalone load (file left untouched).", result.error());
            return defaults;
        }

        writeCanonical(tomlFile, result.value());
        return result.value();
    }

    public static <T> LoadResult<T> readExisting(File tomlFile, Class<T> type) {
        if (tomlFile == null || !tomlFile.isFile()) {
            return new LoadResult<>(null, new IOException("Configuration file does not exist: " + tomlFile));
        }
        T defaults = newInstance(type);
        ReadAttempt<T> attempt = readWithRetries(tomlFile, type, defaults);
        return new LoadResult<>(attempt.value(), attempt.error());
    }

    public static void writeCanonical(File tomlFile, Object instance) {
        File parent = tomlFile.getParentFile();
        if (parent != null) {
            parent.mkdirs();
        }

        StringBuilder out = new StringBuilder(2048);
        Class<?> type = instance.getClass();
        ConfigDoc classDoc = type.getAnnotation(ConfigDoc.class);
        if (classDoc != null) {
            for (String line : classDoc.value()) {
                out.append("# ").append(line).append('\n');
            }
            out.append('\n');
        }

        try {
            Object defaults = newInstance(type);
            writeObjectContents(out, instance, defaults, "");
        } catch (IllegalAccessException e) {
            throw new IllegalStateException("Cannot read config field", e);
        }

        String next = out.toString();
        try {
            if (tomlFile.isFile()) {
                String existing = Files.readString(tomlFile.toPath(), StandardCharsets.UTF_8);
                if (existing.equals(next)) {
                    return;
                }
            }
            atomicWrite(tomlFile.toPath(), next);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to write " + tomlFile, e);
        }
    }

    private static void writeObjectContents(StringBuilder out, Object instance, Object defaults, String sectionPath) throws IllegalAccessException {
        for (Field field : instance.getClass().getDeclaredFields()) {
            if (!isSerializableField(field) || isSection(field.getType()) || isSectionList(field)) {
                continue;
            }
            field.setAccessible(true);
            Object value = field.get(instance);
            Object defaultValue = defaults == null ? null : field.get(defaults);
            if (shouldWriteField(field, value, defaultValue)) {
                writeField(out, field, instance, "");
            }
        }

        for (Field field : instance.getClass().getDeclaredFields()) {
            if (!isSerializableField(field) || !isSection(field.getType())) {
                continue;
            }
            field.setAccessible(true);
            Object section = field.get(instance);
            Object defaultSection = defaults == null ? null : field.get(defaults);
            if (!hasWritableContent(section, defaultSection)) {
                continue;
            }
            String name = toTomlKey(field.getName());
            String nestedPath = sectionPath.isEmpty() ? name : sectionPath + "." + name;
            out.append('\n');
            appendDoc(out, section.getClass().getAnnotation(ConfigDoc.class));
            out.append('[').append(nestedPath).append("]\n");
            writeObjectContents(out, section, defaultSection, nestedPath);
        }

        for (Field field : instance.getClass().getDeclaredFields()) {
            if (!isSerializableField(field) || !isSectionList(field)) {
                continue;
            }
            field.setAccessible(true);
            List<?> entries = (List<?>) field.get(instance);
            if (entries == null || entries.isEmpty()) {
                continue;
            }
            String name = toTomlKey(field.getName());
            String nestedPath = sectionPath.isEmpty() ? name : sectionPath + "." + name;
            for (Object entry : entries) {
                if (entry == null) {
                    continue;
                }
                out.append('\n');
                appendDescription(out, field.getAnnotation(ConfigDescription.class));
                out.append("[[").append(nestedPath).append("]]\n");
                writeObjectContents(out, entry, newInstance(entry.getClass()), nestedPath);
            }
        }
    }

    private static boolean hasWritableContent(Object instance, Object defaults) throws IllegalAccessException {
        if (instance == null) {
            return false;
        }
        for (Field field : instance.getClass().getDeclaredFields()) {
            if (!isSerializableField(field)) {
                continue;
            }
            field.setAccessible(true);
            Object value = field.get(instance);
            Object defaultValue = defaults == null ? null : field.get(defaults);
            if (isSectionList(field)) {
                if (value instanceof List<?> entries && !entries.isEmpty()) {
                    return true;
                }
                continue;
            }
            if (isSection(field.getType())) {
                if (hasWritableContent(value, defaultValue)) {
                    return true;
                }
                continue;
            }
            if (shouldWriteField(field, value, defaultValue)) {
                return true;
            }
        }
        return false;
    }

    private static boolean shouldWriteField(Field field, Object value, Object defaultValue) {
        if (value == null) {
            return false;
        }
        return !field.isAnnotationPresent(ConfigAdvanced.class) || !Objects.deepEquals(value, defaultValue);
    }

    private static boolean isSerializableField(Field field) {
        return !Modifier.isStatic(field.getModifiers()) && !Modifier.isTransient(field.getModifiers());
    }

    private static void appendDoc(StringBuilder out, ConfigDoc doc) {
        if (doc == null) {
            return;
        }
        for (String line : doc.value()) {
            out.append("# ").append(line).append('\n');
        }
    }

    private static void appendDescription(StringBuilder out, ConfigDescription description) {
        if (description == null) {
            return;
        }
        for (String line : description.value()) {
            out.append("# ").append(line).append('\n');
        }
    }

    private static <T> T newInstance(Class<T> type) {
        try {
            return type.getDeclaredConstructor().newInstance();
        } catch (Exception e) {
            throw new IllegalStateException("Config type " + type.getName() + " must have a public no-arg constructor", e);
        }
    }

    private static <T> ReadAttempt<T> readWithRetries(File tomlFile, Class<T> type, T defaults) {
        Throwable lastError = null;
        for (int attempt = 1; attempt <= PARSE_RETRY_ATTEMPTS; attempt++) {
            try {
                long sizeBefore = tomlFile.length();
                if (sizeBefore <= 0L) {
                    sleepBackoff(attempt);
                    continue;
                }
                Toml toml = new Toml().read(tomlFile);
                long sizeAfter = tomlFile.length();
                if (sizeBefore != sizeAfter) {
                    sleepBackoff(attempt);
                    continue;
                }
                T fresh = newInstance(type);
                T applied = applyToml(toml, fresh, type);
                copySectionRefs(applied, defaults);
                return new ReadAttempt<>(applied, null);
            } catch (Throwable e) {
                lastError = e;
                sleepBackoff(attempt);
            }
        }
        if (lastError == null) {
            lastError = new IOException("Configuration file stayed empty or changed during all parse attempts: " + tomlFile);
        }
        return new ReadAttempt<>(null, lastError);
    }

    private static void sleepBackoff(int attempt) {
        try {
            Thread.sleep(PARSE_RETRY_BACKOFF_MS * (long) attempt);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static <T> void copySectionRefs(T target, T defaults) {
        if (target == null || defaults == null) {
            return;
        }
        for (Field f : target.getClass().getDeclaredFields()) {
            if (Modifier.isStatic(f.getModifiers()) || Modifier.isTransient(f.getModifiers())) {
                continue;
            }
            if (!isSection(f.getType())) {
                continue;
            }
            f.setAccessible(true);
            try {
                Object existing = f.get(target);
                if (existing == null) {
                    f.set(target, f.get(defaults));
                }
            } catch (IllegalAccessException ignored) {
            }
        }
    }

    private static <T> T applyToml(Toml toml, T target, Class<T> type) throws Exception {
        applyTomlObject(toml, target, type);
        return target;
    }

    private static void applyTomlObject(Toml toml, Object target, Class<?> type) throws Exception {
        for (Field f : type.getDeclaredFields()) {
            if (Modifier.isStatic(f.getModifiers()) || Modifier.isTransient(f.getModifiers())) {
                continue;
            }
            f.setAccessible(true);
            String key = toTomlKey(f.getName());

            if (isSectionList(f)) {
                List<Toml> tables = toml.getTables(key);
                if (tables == null) {
                    continue;
                }
                Class<?> elementType = sectionListElementType(f);
                List<Object> entries = new ArrayList<>(tables.size());
                for (Toml table : tables) {
                    Object entry = elementType.getDeclaredConstructor().newInstance();
                    applyTomlSection(table, entry);
                    entries.add(entry);
                }
                f.set(target, entries);
                continue;
            }

            if (isSection(f.getType())) {
                Toml sub = toml.getTable(key);
                if (sub == null) {
                    continue;
                }
                Object existing = f.get(target);
                if (existing == null) {
                    existing = f.getType().getDeclaredConstructor().newInstance();
                    f.set(target, existing);
                }
                applyTomlObject(sub, existing, existing.getClass());
                continue;
            }

            applyScalarField(toml, f, target, key);
        }
    }

    private static void applyTomlSection(Toml toml, Object target) throws Exception {
        applyTomlObject(toml, target, target.getClass());
    }

    private static void applyScalarField(Toml toml, Field f, Object target, String key) throws IllegalAccessException {
        if (!toml.contains(key)) {
            return;
        }
        Class<?> t = f.getType();
        if (t == int.class || t == Integer.class) {
            Long value = toml.getLong(key);
            if (value != null) {
                f.set(target, value.intValue());
            }
        } else if (t == long.class || t == Long.class) {
            Long value = toml.getLong(key);
            if (value != null) {
                f.set(target, value);
            }
        } else if (t == double.class || t == Double.class) {
            Object raw = toml.contains(key) ? toml.getDouble(key) : null;
            if (raw == null) {
                Long asLong = toml.getLong(key);
                if (asLong != null) {
                    f.set(target, asLong.doubleValue());
                }
            } else {
                f.set(target, raw);
            }
        } else if (t == float.class || t == Float.class) {
            Double value = toml.getDouble(key);
            if (value != null) {
                f.set(target, value.floatValue());
            }
        } else if (t == boolean.class || t == Boolean.class) {
            Boolean value = toml.getBoolean(key);
            if (value != null) {
                f.set(target, value);
            }
        } else if (t == String.class) {
            String value = toml.getString(key);
            if (value != null) {
                f.set(target, value);
            }
        }
    }

    private static void writeField(StringBuilder out, Field f, Object owner, String indent) throws IllegalAccessException {
        Object value = f.get(owner);
        if (value == null) {
            return;
        }
        ConfigDescription desc = f.getAnnotation(ConfigDescription.class);
        if (desc != null) {
            for (String line : desc.value()) {
                out.append(indent).append("# ").append(line).append('\n');
            }
        }
        if (f.isAnnotationPresent(RestartRequired.class)) {
            out.append(indent).append("# (restart required to apply changes)").append('\n');
        }
        out.append(indent).append(toTomlKey(f.getName())).append(" = ").append(formatValue(value)).append('\n');
    }

    private static String formatValue(Object value) {
        if (value instanceof Boolean) {
            return value.toString();
        }
        if (value instanceof Number n) {
            if (value instanceof Double || value instanceof Float) {
                double d = n.doubleValue();
                if (d == Math.floor(d) && !Double.isInfinite(d)) {
                    return Double.toString(d);
                }
                return n.toString();
            }
            return n.toString();
        }
        if (value instanceof String s) {
            return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
        }
        return "\"" + value + "\"";
    }

    private static String toTomlKey(String fieldName) {
        StringBuilder sb = new StringBuilder(fieldName.length() + 4);
        for (int i = 0; i < fieldName.length(); i++) {
            char c = fieldName.charAt(i);
            if (Character.isUpperCase(c)) {
                if (i > 0) {
                    sb.append('-');
                }
                sb.append(Character.toLowerCase(c));
            } else {
                sb.append(c);
            }
        }
        return sb.toString().toLowerCase(Locale.ROOT);
    }

    private static boolean isSection(Class<?> type) {
        if (type.isPrimitive() || type == String.class) {
            return false;
        }
        if (Number.class.isAssignableFrom(type) || type == Boolean.class) {
            return false;
        }
        if (List.class.isAssignableFrom(type)) {
            return false;
        }
        return type.getPackageName().startsWith("art.arcane.wormholes");
    }

    private static boolean isSectionList(Field f) {
        if (!List.class.isAssignableFrom(f.getType())) {
            return false;
        }
        Class<?> elementType = sectionListElementType(f);
        return elementType != null && isSection(elementType);
    }

    private static Class<?> sectionListElementType(Field f) {
        if (!(f.getGenericType() instanceof java.lang.reflect.ParameterizedType parameterized)) {
            return null;
        }
        java.lang.reflect.Type[] arguments = parameterized.getActualTypeArguments();
        if (arguments.length != 1 || !(arguments[0] instanceof Class<?> element)) {
            return null;
        }
        return element;
    }

    private static void atomicWrite(Path target, String content) throws IOException {
        Path dir = target.getParent();
        if (dir == null) {
            Files.writeString(target, content, StandardCharsets.UTF_8);
            return;
        }
        Path tmp = Files.createTempFile(dir, target.getFileName().toString() + ".", ".tmp");
        try {
            Files.writeString(tmp, content, StandardCharsets.UTF_8);
            try {
                Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (UnsupportedOperationException e) {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            try {
                Files.deleteIfExists(tmp);
            } catch (IOException ignored) {
            }
        }
    }

    public record LoadResult<T>(T value, Throwable error) {
        public boolean isSuccess() {
            return error == null && value != null;
        }
    }

    private record ReadAttempt<T>(T value, Throwable error) {
    }
}
