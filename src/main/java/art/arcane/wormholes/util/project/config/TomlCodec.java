package art.arcane.wormholes.util.project.config;

import com.moandjiezana.toml.Toml;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class TomlCodec {
    private TomlCodec() {
    }

    public static <T> T loadOrCreate(File tomlFile, Class<T> type) {
        T defaults;
        try {
            defaults = type.getDeclaredConstructor().newInstance();
        } catch (Exception e) {
            throw new IllegalStateException("Config type " + type.getName() + " must have a public no-arg constructor", e);
        }

        if (!tomlFile.exists()) {
            writeCanonical(tomlFile, defaults);
            return defaults;
        }

        T loaded;
        try {
            Toml toml = new Toml().read(tomlFile);
            loaded = applyToml(toml, defaults, type);
        } catch (Throwable e) {
            String backupName = tomlFile.getName() + ".invalid-" + System.currentTimeMillis();
            File backup = new File(tomlFile.getParentFile(), backupName);
            try {
                Files.move(tomlFile.toPath(), backup.toPath());
            } catch (IOException ignored) {
            }
            writeCanonical(tomlFile, defaults);
            return defaults;
        }

        writeCanonical(tomlFile, loaded);
        return loaded;
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

        List<Field> simple = new ArrayList<>();
        List<Field> sections = new ArrayList<>();
        for (Field f : type.getDeclaredFields()) {
            if (Modifier.isStatic(f.getModifiers()) || Modifier.isTransient(f.getModifiers())) {
                continue;
            }
            f.setAccessible(true);
            if (isSection(f.getType())) {
                sections.add(f);
            } else {
                simple.add(f);
            }
        }

        try {
            for (Field f : simple) {
                writeField(out, f, instance, "");
            }
            for (Field f : sections) {
                Object section = f.get(instance);
                if (section == null) {
                    continue;
                }
                String name = toTomlKey(f.getName());
                out.append('\n');
                ConfigDoc sectionDoc = section.getClass().getAnnotation(ConfigDoc.class);
                if (sectionDoc != null) {
                    for (String line : sectionDoc.value()) {
                        out.append("# ").append(line).append('\n');
                    }
                }
                out.append('[').append(name).append("]\n");
                for (Field sf : section.getClass().getDeclaredFields()) {
                    if (Modifier.isStatic(sf.getModifiers()) || Modifier.isTransient(sf.getModifiers())) {
                        continue;
                    }
                    sf.setAccessible(true);
                    writeField(out, sf, section, "");
                }
            }
        } catch (IllegalAccessException e) {
            throw new IllegalStateException("Cannot read config field", e);
        }

        try {
            Files.writeString(tomlFile.toPath(), out.toString(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to write " + tomlFile, e);
        }
    }

    private static <T> T applyToml(Toml toml, T target, Class<T> type) throws Exception {
        for (Field f : type.getDeclaredFields()) {
            if (Modifier.isStatic(f.getModifiers()) || Modifier.isTransient(f.getModifiers())) {
                continue;
            }
            f.setAccessible(true);
            String key = toTomlKey(f.getName());

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
                applyTomlSection(sub, existing);
                continue;
            }

            applyScalarField(toml, f, target, key);
        }
        return target;
    }

    private static void applyTomlSection(Toml toml, Object target) throws Exception {
        for (Field f : target.getClass().getDeclaredFields()) {
            if (Modifier.isStatic(f.getModifiers()) || Modifier.isTransient(f.getModifiers())) {
                continue;
            }
            f.setAccessible(true);
            String key = toTomlKey(f.getName());
            applyScalarField(toml, f, target, key);
        }
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
        return type.getPackageName().startsWith("art.arcane.wormholes");
    }
}
