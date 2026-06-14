package art.arcane.wormholes.service;

import art.arcane.wormholes.Wormholes;
import org.bstats.bukkit.Metrics;

import java.util.logging.Level;
import java.util.logging.Logger;

public final class MetricsRuntime {
    private final Logger logger;
    private Object metrics;

    private MetricsRuntime(Logger logger, Object metrics) {
        this.logger = logger;
        this.metrics = metrics;
    }

    public static MetricsRuntime start(Wormholes plugin, int pluginId) {
        Metrics metrics = new Metrics(plugin, pluginId);
        return new MetricsRuntime(plugin.getLogger(), metrics);
    }

    public void shutdown() {
        Object activeMetrics = metrics;
        metrics = null;
        if (activeMetrics instanceof Metrics) {
            try {
                Metrics bstatsMetrics = (Metrics) activeMetrics;
                bstatsMetrics.shutdown();
            } catch (Throwable ex) {
                logger.log(Level.WARNING, "Error during bStats shutdown", ex);
            }
        }
    }
}
