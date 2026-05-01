package art.arcane.wormholes.util;

import java.util.Random;

public final class M {
    private static final Random RANDOM = new Random();

    private M() {
    }

    public static long ms() {
        return System.currentTimeMillis();
    }

    public static long ns() {
        return System.nanoTime();
    }

    public static double r() {
        return RANDOM.nextDouble();
    }

    public static boolean r(Double d) {
        if (d == null) {
            return RANDOM.nextDouble() < 0.5;
        }
        return RANDOM.nextDouble() < d;
    }

    public static int rand(int min, int max) {
        if (max <= min) {
            return min;
        }
        return min + RANDOM.nextInt((max - min) + 1);
    }

    public static double rand(double min, double max) {
        if (max <= min) {
            return min;
        }
        return min + RANDOM.nextDouble() * (max - min);
    }

    public static int max(int... values) {
        int m = Integer.MIN_VALUE;
        for (int v : values) {
            if (v > m) {
                m = v;
            }
        }
        return m;
    }

    public static double max(double... values) {
        double m = Double.NEGATIVE_INFINITY;
        for (double v : values) {
            if (v > m) {
                m = v;
            }
        }
        return m;
    }

    public static int min(int... values) {
        int m = Integer.MAX_VALUE;
        for (int v : values) {
            if (v < m) {
                m = v;
            }
        }
        return m;
    }

    public static double min(double... values) {
        double m = Double.POSITIVE_INFINITY;
        for (double v : values) {
            if (v < m) {
                m = v;
            }
        }
        return m;
    }

    public static double clip(double value, double min, double max) {
        return Math.min(max, Math.max(min, value));
    }

    public static int iclip(double value, double min, double max) {
        return (int) clip(value, min, max);
    }

    public static boolean within(int from, int to, int is) {
        return is >= from && is <= to;
    }
}
