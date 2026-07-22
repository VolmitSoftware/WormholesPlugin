package art.arcane.wormholes.commands;

import art.arcane.volmlib.util.director.annotations.Director;
import art.arcane.volmlib.util.director.annotations.Param;
import art.arcane.volmlib.util.localization.MessageArgs;
import art.arcane.volmlib.util.localization.MessageArgument;
import art.arcane.volmlib.util.localization.TextKey;
import art.arcane.wormholes.Wormholes;
import art.arcane.wormholes.localization.WormholesLocalization;
import art.arcane.wormholes.localization.WormholesMessages;
import art.arcane.wormholes.service.WormholesAudience;
import art.arcane.volmlib.util.scheduling.FoliaScheduler;
import org.bukkit.command.CommandSender;

import java.util.logging.Level;

@Director(name = "admin", descriptionKey = "command.help.admin", description = "Destructive Wormholes maintenance commands")
public class CommandAdmin {
    @Director(name = "deleteallportals", sync = true, descriptionKey = "command.help.admin.delete_portals", description = "Delete every local portal and saved portal link")
    public void deleteAllPortals(@Param(name = "sender", contextual = true) CommandSender sender) {
        if (!sender.hasPermission("wormholes.admin.reset")) {
            send(sender, WormholesMessages.COMMAND_NO_PERMISSION);
            return;
        }
        if (!FoliaScheduler.runGlobal(Wormholes.instance, () -> {
            try {
                int deleted = Wormholes.instance.deleteAllPortalsNow();
                WormholesAudience.sendMessage(sender, Wormholes.text().component(
                        WormholesMessages.COMMAND_DELETED_PORTALS,
                        countArgs(deleted)
                ));
            } catch (Throwable exception) {
                Wormholes.instance.getLogger().log(Level.SEVERE, "Failed to delete all Wormholes portals", exception);
                send(sender, WormholesMessages.COMMAND_DELETE_FAILED);
            }
        })) {
            send(sender, WormholesMessages.COMMAND_DELETE_SCHEDULE_FAILED);
        }
    }

    @Director(name = "deleteeverything", sync = true, descriptionKey = "command.help.admin.delete_everything", description = "Reset Wormholes data, config, trust, identity, and network state")
    public void deleteEverything(@Param(name = "sender", contextual = true) CommandSender sender) {
        if (!sender.hasPermission("wormholes.admin.reset")) {
            send(sender, WormholesMessages.COMMAND_NO_PERMISSION);
            return;
        }
        if (!FoliaScheduler.runGlobal(Wormholes.instance, () -> {
            try {
                Wormholes.ResetResult result = Wormholes.instance.resetEverythingNow();
                WormholesAudience.sendMessage(sender, Wormholes.text().component(
                        WormholesMessages.COMMAND_RESET_EVERYTHING,
                        countArgs(result.deletedPortals())
                ));
            } catch (Throwable exception) {
                Wormholes.instance.getLogger().log(Level.SEVERE, "Failed to reset Wormholes", exception);
                send(sender, WormholesMessages.COMMAND_RESET_FAILED);
            }
        })) {
            send(sender, WormholesMessages.COMMAND_RESET_SCHEDULE_FAILED);
        }
    }

    private static MessageArgs countArgs(int count) {
        return WormholesLocalization.args(MessageArgument.untrusted("count", Integer.valueOf(count)));
    }

    private static void send(CommandSender sender, TextKey key) {
        WormholesAudience.sendMessage(sender, Wormholes.text().component(key));
    }
}
