package art.arcane.wormholes.util.common;

import art.arcane.wormholes.Wormholes;
import net.md_5.bungee.api.ChatColor;
import org.bukkit.Bukkit;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

public final class SplashScreen {
    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ISO_LOCAL_DATE;
    private static final String SUPPORTED_MC_VERSION = "1.21.11";

    private SplashScreen() {
    }

    public static void print(Wormholes plugin, boolean success, String errorMessage) {
        ChatColor dark = ChatColor.of("#4f4f4f");
        ChatColor accent = ChatColor.of("#d9d9d9");
        ChatColor meta = ChatColor.of("#a8a8a8");
        ChatColor statusColor = success ? ChatColor.GREEN : ChatColor.RED;
        String status = success ? "READY" : "DEGRADED";
        String pluginVersion = plugin.getDescription().getVersion();
        String releaseTrain = getReleaseTrain(pluginVersion);
        String serverVersion = getServerVersion();
        String startupDate = getStartupDate();

        String splash =
            "\n"
                + dark + "██" + accent + "╗    " + dark + "██" + accent + "╗ " + dark + "██████" + accent + "╗ " + dark + "██████" + accent + "╗ " + dark + "███" + accent + "╗   " + dark + "███" + accent + "╗" + dark + "██" + accent + "╗  " + dark + "██" + accent + "╗ " + dark + "██████" + accent + "╗ " + dark + "██" + accent + "╗     " + dark + "███████" + accent + "╗" + dark + "███████" + accent + "╗\n"
                + dark + "██" + accent + "║    " + dark + "██" + accent + "║" + dark + "██" + accent + "╔═══" + dark + "██" + accent + "╗" + dark + "██" + accent + "╔══" + dark + "██" + accent + "╗" + dark + "████" + accent + "╗ " + dark + "████" + accent + "║" + dark + "██" + accent + "║  " + dark + "██" + accent + "║" + dark + "██" + accent + "╔═══" + dark + "██" + accent + "╗" + dark + "██" + accent + "║     " + dark + "██" + accent + "╔════╝" + dark + "██" + accent + "╔════╝" + accent + "   Wormholes, " + meta + "Through-Portal Projection " + ChatColor.RED + "[" + releaseTrain + " RELEASE]\n"
                + dark + "██" + accent + "║ " + dark + "█" + accent + "╗ " + dark + "██" + accent + "║" + dark + "██" + accent + "║   " + dark + "██" + accent + "║" + dark + "██████" + accent + "╔╝" + dark + "██" + accent + "╔" + dark + "████" + accent + "╔" + dark + "██" + accent + "║" + dark + "███████" + accent + "║" + dark + "██" + accent + "║   " + dark + "██" + accent + "║" + dark + "██" + accent + "║     " + dark + "█████" + accent + "╗  " + dark + "███████" + accent + "╗" + meta + "   Version: " + accent + pluginVersion + "\n"
                + dark + "██" + accent + "║" + dark + "███" + accent + "╗" + dark + "██" + accent + "║" + dark + "██" + accent + "║   " + dark + "██" + accent + "║" + dark + "██" + accent + "╔══" + dark + "██" + accent + "╗" + dark + "██" + accent + "║╚" + dark + "██" + accent + "╔╝" + dark + "██" + accent + "║" + dark + "██" + accent + "╔══" + dark + "██" + accent + "║" + dark + "██" + accent + "║   " + dark + "██" + accent + "║" + dark + "██" + accent + "║     " + dark + "██" + accent + "╔══╝  ╚════" + dark + "██" + accent + "║" + meta + "   By: " + accent + "Volmit Software (Arcane Arts)" + meta + " | Startup: " + statusColor + status + "\n"
                + accent + "╚" + dark + "███" + accent + "╔" + dark + "███" + accent + "╔╝╚" + dark + "██████" + accent + "╔╝" + dark + "██" + accent + "║  " + dark + "██" + accent + "║" + dark + "██" + accent + "║ ╚═╝ " + dark + "██" + accent + "║" + dark + "██" + accent + "║  " + dark + "██" + accent + "║╚" + dark + "██████" + accent + "╔╝" + dark + "███████" + accent + "╗" + dark + "███████" + accent + "╗" + dark + "███████" + accent + "║" + meta + "   Server: " + accent + serverVersion + meta + " | MC Support: " + accent + SUPPORTED_MC_VERSION + "\n"
                + accent + " ╚══╝╚══╝  ╚═════╝ ╚═╝  ╚═╝╚═╝     ╚═╝╚═╝  ╚═╝ ╚═════╝ ╚══════╝╚══════╝╚══════╝" + meta + "   Java: " + accent + getJavaVersion() + meta + " | Date: " + accent + startupDate + "\n";

        Bukkit.getConsoleSender().sendMessage(splash);
        if (!success && errorMessage != null && !errorMessage.isBlank()) {
            plugin.getLogger().warning("Startup error: " + errorMessage);
        }
    }

    private static int getJavaVersion() {
        String version = System.getProperty("java.version");
        if (version.startsWith("1.")) {
            version = version.substring(2, 3);
        } else {
            int dot = version.indexOf('.');
            if (dot != -1) {
                version = version.substring(0, dot);
            }
        }
        return Integer.parseInt(version);
    }

    private static String getServerVersion() {
        String version = Bukkit.getVersion();
        int mcMarkerIndex = version.indexOf(" (MC:");
        if (mcMarkerIndex != -1) {
            version = version.substring(0, mcMarkerIndex);
        }
        return version;
    }

    private static String getStartupDate() {
        return LocalDate.now().format(DATE_FORMATTER);
    }

    private static String getReleaseTrain(String version) {
        String value = version;
        int suffixIndex = value.indexOf('-');
        if (suffixIndex >= 0) {
            value = value.substring(0, suffixIndex);
        }
        String[] split = value.split("\\.");
        if (split.length >= 2) {
            return split[0] + "." + split[1];
        }
        return value;
    }
}
