package art.arcane.wormholes.commands;

import art.arcane.volmlib.util.director.annotations.Director;
import art.arcane.volmlib.util.director.annotations.Param;
import art.arcane.wormholes.Wormholes;
import org.bukkit.ChatColor;
import org.bukkit.command.CommandSender;

import java.util.logging.Level;

@Director(name = "admin", description = "Destructive Wormholes maintenance commands")
public class CommandAdmin {
    @Director(name = "deleteallportals", sync = true, description = "Delete every local portal and saved portal link")
    public void deleteAllPortals(@Param(name = "sender", contextual = true) CommandSender sender) {
        if (!sender.hasPermission("wormholes.admin.reset")) {
            sender.sendMessage(Wormholes.tag + ChatColor.RED + "You do not have permission.");
            return;
        }
        try {
            int deleted = Wormholes.instance.deleteAllPortalsNow();
            sender.sendMessage(Wormholes.tag + ChatColor.GREEN + "Deleted " + ChatColor.WHITE + deleted + ChatColor.GREEN + " portal" + (deleted == 1 ? "" : "s") + " and cleared local portal links.");
        } catch (Throwable e) {
            Wormholes.instance.getLogger().log(Level.SEVERE, "Failed to delete all Wormholes portals", e);
            sender.sendMessage(Wormholes.tag + ChatColor.RED + "Failed to delete all portals. Check console for the full stacktrace.");
        }
    }

    @Director(name = "deleteeverything", sync = true, description = "Reset Wormholes data, config, trust, identity, and network state")
    public void deleteEverything(@Param(name = "sender", contextual = true) CommandSender sender) {
        if (!sender.hasPermission("wormholes.admin.reset")) {
            sender.sendMessage(Wormholes.tag + ChatColor.RED + "You do not have permission.");
            return;
        }
        try {
            Wormholes.ResetResult result = Wormholes.instance.resetEverythingNow();
            sender.sendMessage(Wormholes.tag + ChatColor.GREEN + "Wormholes reset to default state. Deleted " + ChatColor.WHITE + result.deletedPortals() + ChatColor.GREEN + " portal" + (result.deletedPortals() == 1 ? "" : "s") + ", closed network connections, and regenerated default config files.");
        } catch (Throwable e) {
            Wormholes.instance.getLogger().log(Level.SEVERE, "Failed to reset Wormholes", e);
            sender.sendMessage(Wormholes.tag + ChatColor.RED + "Failed to reset Wormholes. Check console for the full stacktrace.");
        }
    }
}
