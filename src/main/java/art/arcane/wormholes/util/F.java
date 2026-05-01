package art.arcane.wormholes.util;

import java.text.DecimalFormat;
import java.text.NumberFormat;
import java.util.Locale;

public final class F {
    private static final NumberFormat NF = NumberFormat.getInstance(Locale.US);

    private F() {
    }

    public static String capitalize(String input) {
        if (input == null || input.isEmpty()) {
            return input;
        }
        return Character.toUpperCase(input.charAt(0)) + input.substring(1);
    }

    public static String f(int value) {
        return NF.format(value);
    }

    public static String f(long value) {
        return NF.format(value);
    }

    public static String f(double value) {
        return f(value, 1);
    }

    public static String f(double value, int decimals) {
        StringBuilder pattern = new StringBuilder("#");
        if (decimals > 0) {
            pattern.append('.');
            for (int i = 0; i < decimals; i++) {
                pattern.append('#');
            }
        }
        DecimalFormat formatter = new DecimalFormat(pattern.toString());
        return formatter.format(value);
    }

    public static String time(long ms) {
        if (ms < 1000L) {
            return ms + "ms";
        }
        long seconds = ms / 1000L;
        if (seconds < 60L) {
            return seconds + "s";
        }
        long minutes = seconds / 60L;
        if (minutes < 60L) {
            return minutes + "m" + (seconds % 60L) + "s";
        }
        long hours = minutes / 60L;
        if (hours < 24L) {
            return hours + "h" + (minutes % 60L) + "m";
        }
        long days = hours / 24L;
        return days + "d" + (hours % 24L) + "h";
    }

    public static String time(double ms, int decimals) {
        if (ms < 1000.0D) {
            return f(ms, decimals) + "ms";
        }
        if (ms / 1000.0D < 60.0D) {
            return f(ms / 1000.0D, decimals) + "s";
        }
        if (ms / 60000.0D < 60.0D) {
            return f(ms / 60000.0D, decimals) + "m";
        }
        return f(ms, decimals) + "ms";
    }
}
