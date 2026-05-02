package art.arcane.wormholes.commands;

import art.arcane.wormholes.Wormholes;
import art.arcane.volmlib.util.director.annotations.Director;
import art.arcane.volmlib.util.director.annotations.Param;
import org.bukkit.ChatColor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

@Director(name = "wormholes", description = "Wormholes command root")
public class CommandWormholes {
    private final Wormholes plugin;

    public CommandWormholes(Wormholes plugin) {
        this.plugin = plugin;
    }

    @Director(name = "wand", description = "Give yourself a portal wand and starter runes")
    public void wand(@Param(name = "sender", contextual = true) CommandSender sender) {
        if (!sender.hasPermission("wormholes.admin.items")) {
            sender.sendMessage(Wormholes.tag + ChatColor.RED + "You do not have permission.");
            return;
        }
        if (!(sender instanceof Player player)) {
            sender.sendMessage(Wormholes.tag + ChatColor.RED + "Only players can receive items.");
            return;
        }
        ItemStack wand = Wormholes.blockManager.getWand();
        ItemStack wormholeRunes = Wormholes.blockManager.getWormholeRune(32);
        player.getInventory().addItem(wand, wormholeRunes);
        player.sendMessage(Wormholes.tag + ChatColor.GREEN + "Portal Wand and 32 Wormhole Runes granted.");
        player.sendMessage(Wormholes.tag + ChatColor.GRAY + "Build TWO solid wormhole-rune rectangles, link them, and stand within 16 blocks to see the projection.");
        player.sendMessage(Wormholes.tag + ChatColor.GRAY + "Run " + ChatColor.YELLOW + "/wormholes info" + ChatColor.GRAY + " for the full step-by-step.");
    }

    @Director(name = "rune", description = "Give yourself portal runes")
    public void rune(@Param(name = "sender", contextual = true) CommandSender sender,
                     @Param(name = "type", description = "portal | wormhole | gateway") String type,
                     @Param(name = "count", description = "How many runes (default 16)", defaultValue = "16") int count) {
        if (!sender.hasPermission("wormholes.admin.items")) {
            sender.sendMessage(Wormholes.tag + ChatColor.RED + "You do not have permission.");
            return;
        }
        if (!(sender instanceof Player player)) {
            sender.sendMessage(Wormholes.tag + ChatColor.RED + "Only players can receive items.");
            return;
        }

        int safeCount = Math.max(1, Math.min(count, 64));
        ItemStack rune;
        String typeLower = type == null ? "portal" : type.toLowerCase();
        switch (typeLower) {
            case "wormhole":
                rune = Wormholes.blockManager.getWormholeRune(safeCount);
                break;
            case "gateway":
                rune = Wormholes.blockManager.getGatewayRune(safeCount);
                break;
            case "portal":
            default:
                rune = Wormholes.blockManager.getPortalRune(safeCount);
                break;
        }
        player.getInventory().addItem(rune);
        player.sendMessage(Wormholes.tag + ChatColor.GREEN + "Granted " + ChatColor.AQUA + safeCount + " " + typeLower + ChatColor.GREEN + " runes.");
    }

    @Director(name = "reload", description = "Reload Wormholes configuration")
    public void reload(@Param(name = "sender", contextual = true) CommandSender sender) {
        if (!sender.hasPermission("wormholes.admin.reload")) {
            sender.sendMessage(Wormholes.tag + ChatColor.RED + "You do not have permission.");
            return;
        }
        plugin.reloadAll();
        sender.sendMessage(Wormholes.tag + ChatColor.GREEN + "Wormholes configuration reloaded.");
    }

    @Director(name = "reset", aliases = {"deleteall", "delete-all", "clear"}, description = "Delete every saved portal and clear active projections")
    public void reset(@Param(name = "sender", contextual = true) CommandSender sender) {
        if (!sender.hasPermission("wormholes.admin.reset")) {
            sender.sendMessage(Wormholes.tag + ChatColor.RED + "You do not have permission.");
            return;
        }
        if (Wormholes.portalManager == null) {
            sender.sendMessage(Wormholes.tag + ChatColor.RED + "PortalManager is not ready.");
            return;
        }
        int removed = Wormholes.portalManager.deleteAllPortals();
        sender.sendMessage(Wormholes.tag + ChatColor.GREEN + "Deleted " + ChatColor.AQUA + removed + ChatColor.GREEN + " portals and cleared projection state.");
    }

    @Director(name = "debug", description = "Dump live projection diagnostics")
    public void debug(@Param(name = "sender", contextual = true) CommandSender sender) {
        if (!sender.hasPermission("wormholes.admin.reload")) {
            sender.sendMessage(Wormholes.tag + ChatColor.RED + "You do not have permission.");
            return;
        }
        if (Wormholes.projectionManager == null) {
            sender.sendMessage(Wormholes.tag + ChatColor.RED + "ProjectionManager is null.");
            return;
        }
        String dump = Wormholes.projectionManager.dumpDiagnostics();
        for (String line : dump.split("\n")) {
            sender.sendMessage(ChatColor.GRAY + line);
        }
    }

    @Director(name = "info", description = "Show portal building instructions")
    public void info(@Param(name = "sender", contextual = true) CommandSender sender) {
        sender.sendMessage(Wormholes.tag + ChatColor.GOLD + "How to build a Wormhole");
        sender.sendMessage(ChatColor.YELLOW + "1. " + ChatColor.GRAY + "Run " + ChatColor.WHITE + "/wormholes wand" + ChatColor.GRAY + " to receive the wand and 32 wormhole runes.");
        sender.sendMessage(ChatColor.YELLOW + "2. " + ChatColor.GRAY + "Place the runes as one connected flat plane.");
        sender.sendMessage(ChatColor.GRAY + "   Rectangles, L-shapes, crosses, and other connected non-rectangular planes work.");
        sender.sendMessage(ChatColor.GRAY + "   The frame must be flat on one axis-aligned wall, floor, or ceiling.");
        sender.sendMessage(ChatColor.YELLOW + "3. " + ChatColor.GRAY + "Hold the Portal Wand and " + ChatColor.WHITE + "left-click any rune block" + ChatColor.GRAY + " to form the portal.");
        sender.sendMessage(ChatColor.GRAY + "   Shapes spanning more than one plane are refunded automatically.");
        sender.sendMessage(ChatColor.YELLOW + "4. " + ChatColor.GRAY + "Build a SECOND portal somewhere else (any distance, any world).");
        sender.sendMessage(ChatColor.YELLOW + "5. " + ChatColor.GRAY + "Right-click the open portal with the wand to open the menu.");
        sender.sendMessage(ChatColor.GRAY + "   Choose " + ChatColor.WHITE + "Set Focus" + ChatColor.GRAY + " then click the other portal in the list. Repeat from the other side.");
        sender.sendMessage(ChatColor.YELLOW + "6. " + ChatColor.GRAY + "Stand within 16 blocks of either portal — the destination world will project through the frame and walking in teleports you.");
        sender.sendMessage(ChatColor.GRAY + "Need more runes? " + ChatColor.WHITE + "/wormholes rune <portal|wormhole|gateway> [count]");
        sender.sendMessage(ChatColor.GRAY + "Admin reset: " + ChatColor.WHITE + "/wormholes reset" + ChatColor.GRAY + " deletes all saved portals.");
    }
}
