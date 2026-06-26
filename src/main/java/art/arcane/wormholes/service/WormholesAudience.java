package art.arcane.wormholes.service;

import art.arcane.wormholes.Wormholes;
import net.kyori.adventure.platform.bukkit.BukkitAudiences;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.kyori.adventure.title.Title;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.logging.Level;
import java.util.logging.Logger;

public final class WormholesAudience {
    private static volatile BukkitAudiences audiences;

    private WormholesAudience() {
    }

    public static void start(Wormholes plugin) {
        audiences = BukkitAudiences.create(plugin);
    }

    public static BukkitAudiences audiences() {
        return audiences;
    }

    public static void sendActionBar(Player player, Component component) {
        BukkitAudiences activeAudiences = audiences;
        if (activeAudiences != null && player != null && component != null) {
            activeAudiences.player(player).sendActionBar(component);
        }
    }

    public static void showTitle(Player player, Title title) {
        BukkitAudiences activeAudiences = audiences;
        if (activeAudiences != null && player != null && title != null) {
            activeAudiences.player(player).showTitle(title);
        }
    }

    public static void sendMessage(CommandSender sender, Component component) {
        if (sender == null || component == null) {
            return;
        }
        if (sendDirectMessage(sender, component)) {
            return;
        }

        BukkitAudiences activeAudiences = audiences;
        if (activeAudiences != null) {
            try {
                activeAudiences.sender(sender).sendMessage(component);
                return;
            } catch (Throwable ignored) {
            }
        }

        sender.sendMessage(LegacyComponentSerializer.legacySection().serialize(component));
    }

    private static boolean sendDirectMessage(CommandSender sender, Component component) {
        try {
            sender.getClass().getMethod("sendMessage", Component.class).invoke(sender, component);
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    public static void stop(Logger logger) {
        BukkitAudiences activeAudiences = audiences;
        audiences = null;
        if (activeAudiences != null) {
            try {
                activeAudiences.close();
            } catch (Throwable ex) {
                logger.log(Level.WARNING, "Error closing Adventure audiences", ex);
            }
        }
    }
}
