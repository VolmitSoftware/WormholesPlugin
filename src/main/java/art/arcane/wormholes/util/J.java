package art.arcane.wormholes.util;

import art.arcane.volmlib.util.scheduling.AR;
import art.arcane.volmlib.util.scheduling.SR;
import art.arcane.volmlib.util.scheduling.SchedulerBridge;
import art.arcane.volmlib.util.scheduling.SchedulerRuntime;
import art.arcane.wormholes.Wormholes;

public final class J {
    private J() {
    }

    public static void s(Runnable r) {
        SchedulerBridge.scheduleSync(r);
    }

    public static void s(Runnable r, int delay) {
        SchedulerBridge.scheduleSync(r, delay);
    }

    public static void a(Runnable r) {
        SchedulerBridge.scheduleAsync(r);
    }

    public static void a(Runnable r, int delay) {
        SchedulerBridge.scheduleAsync(r, delay);
    }

    public static int sr(Runnable r, int interval) {
        return SchedulerBridge.scheduleSyncRepeating(r, interval);
    }

    public static int ar(Runnable r, int interval) {
        return SchedulerBridge.scheduleAsyncRepeating(r, interval);
    }

    public static void sr(Runnable r, int interval, int intervals) {
        int[] count = new int[]{0};
        new SR(interval) {
            @Override
            public void run() {
                count[0]++;
                r.run();
                if (count[0] >= intervals) {
                    cancel();
                }
            }
        };
    }

    public static void ar(Runnable r, int interval, int intervals) {
        int[] count = new int[]{0};
        new AR(interval) {
            @Override
            public void run() {
                count[0]++;
                r.run();
                if (count[0] >= intervals) {
                    cancel();
                }
            }
        };
    }

    public static void csr(int id) {
        SchedulerBridge.cancel(id);
    }

    public static void car(int id) {
        SchedulerBridge.cancel(id);
    }

    public static void ass(Runnable r) {
        SchedulerRuntime runtime = Wormholes.INSTANCE != null ? Wormholes.INSTANCE.getSchedulerRuntime() : null;
        if (runtime != null) {
            runtime.s(r);
        } else {
            s(r);
        }
    }

    public static void asa(Runnable r) {
        SchedulerRuntime runtime = Wormholes.INSTANCE != null ? Wormholes.INSTANCE.getSchedulerRuntime() : null;
        if (runtime != null) {
            runtime.a(r, 0);
        } else {
            a(r);
        }
    }
}
