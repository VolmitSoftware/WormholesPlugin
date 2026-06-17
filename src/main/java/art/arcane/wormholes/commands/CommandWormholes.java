package art.arcane.wormholes.commands;

import art.arcane.wormholes.Settings;
import art.arcane.wormholes.Wormholes;
import art.arcane.wormholes.service.StatsSnapshotWriter;
import art.arcane.volmlib.util.director.annotations.Director;
import art.arcane.volmlib.util.director.annotations.Param;
import org.bukkit.ChatColor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.nio.file.Path;

@Director(name = "wormholes", aliases = {"wh", "wormhole"}, description = "Wormholes command root")
public class CommandWormholes {
    private final Wormholes plugin;
    private CommandAdmin admin = new CommandAdmin();
    private CommandNetwork network = new CommandNetwork();

    public CommandWormholes(Wormholes plugin) {
        this.plugin = plugin;
    }

    @Director(name = "wand", sync = true, description = "Give yourself the portal wand, or runes with rune=<type>")
    public void wand(@Param(name = "sender", contextual = true) CommandSender sender,
                     @Param(name = "rune", description = "portal | wormhole | gateway", defaultValue = "none") String rune,
                     @Param(name = "count", description = "How many runes (default 1)", defaultValue = "1") int count) {
        if (!sender.hasPermission("wormholes.admin.items")) {
            sender.sendMessage(Wormholes.tag + ChatColor.RED + "You do not have permission.");
            return;
        }
        if (!(sender instanceof Player player)) {
            sender.sendMessage(Wormholes.tag + ChatColor.RED + "Only players can receive items.");
            return;
        }

        String runeType = rune == null ? "none" : rune.toLowerCase();
        if (!runeType.equals("none")) {
            int safeCount = Math.max(1, Math.min(count, 64));
            ItemStack runes = switch (runeType) {
                case "portal" -> Wormholes.blockManager.getPortalRune(safeCount);
                case "wormhole" -> Wormholes.blockManager.getWormholeRune(safeCount);
                case "gateway" -> Wormholes.blockManager.getGatewayRune(safeCount);
                default -> null;
            };
            if (runes == null) {
                sender.sendMessage(Wormholes.tag + ChatColor.RED + "Unknown rune type '" + runeType + "'. Use portal, wormhole, or gateway.");
                return;
            }
            player.getInventory().addItem(runes);
            player.sendMessage(Wormholes.tag + ChatColor.GREEN + "Granted " + ChatColor.WHITE + safeCount + " " + runeType + ChatColor.GREEN + " rune" + (safeCount == 1 ? "." : "s."));
            return;
        }

        ItemStack wand = Wormholes.blockManager.getWand();
        ItemStack wormholeRune = Wormholes.blockManager.getWormholeRune(1);
        player.getInventory().addItem(wand, wormholeRune);
        player.sendMessage(Wormholes.tag + ChatColor.GREEN + "Portal Wand and 1 Wormhole Rune granted.");
        player.sendMessage(Wormholes.tag + ChatColor.GRAY + "Build TWO solid wormhole-rune rectangles, link them, and stand within 16 blocks to see the projection.");
        player.sendMessage(Wormholes.tag + ChatColor.GRAY + "Run " + ChatColor.WHITE + "/wormholes info" + ChatColor.GRAY + " for the full step-by-step.");
    }

    @Director(name = "reload", sync = true, description = "Reload Wormholes configuration")
    public void reload(@Param(name = "sender", contextual = true) CommandSender sender) {
        if (!sender.hasPermission("wormholes.admin.reload")) {
            sender.sendMessage(Wormholes.tag + ChatColor.RED + "You do not have permission.");
            return;
        }
        plugin.reloadAll();
        sender.sendMessage(Wormholes.tag + ChatColor.GREEN + "Wormholes configuration reloaded.");
    }

    @Director(name = "debug", sync = true, description = "Toggle verbose debug logging at runtime (like /iris debug)")
    public void debug(@Param(name = "sender", contextual = true) CommandSender sender) {
        if (!sender.hasPermission("wormholes.admin")) {
            sender.sendMessage(Wormholes.tag + ChatColor.RED + "You do not have permission.");
            return;
        }
        boolean enabled = !Settings.DEBUG;
        Settings.DEBUG = enabled;
        if (enabled) {
            sender.sendMessage(Wormholes.tag + ChatColor.GREEN + "Debug logging ENABLED" + ChatColor.GRAY + " — do a portal jump now and share the console output.");
        } else {
            sender.sendMessage(Wormholes.tag + ChatColor.YELLOW + "Debug logging DISABLED.");
        }
        Wormholes.instance.getLogger().info("[debug] verbose debug logging " + (enabled ? "ENABLED" : "DISABLED") + " by " + sender.getName());
    }

    @Director(name = "stats", sync = true, description = "Print the live stats-snapshot file path, optionally force a refresh with now=true")
    public void stats(@Param(name = "sender", contextual = true) CommandSender sender,
                      @Param(name = "now", description = "Force-rebuild the snapshot synchronously", defaultValue = "false") boolean now) {
        if (!sender.hasPermission("wormholes.admin")) {
            sender.sendMessage(Wormholes.tag + ChatColor.RED + "You do not have permission.");
            return;
        }
        StatsSnapshotWriter writer = plugin.getStatsSnapshotWriter();
        if (writer == null) {
            sender.sendMessage(Wormholes.tag + ChatColor.RED + "Stats snapshot writer is disabled (network.toml stats.enabled).");
            return;
        }
        if (now) {
            writer.writeNow();
            sender.sendMessage(Wormholes.tag + ChatColor.GREEN + "Snapshot refreshed.");
        }
        Path output = writer.getOutputFile();
        sender.sendMessage(Wormholes.tag + ChatColor.GRAY + "Snapshot file: " + ChatColor.WHITE + output.toAbsolutePath());
        sender.sendMessage(Wormholes.tag + ChatColor.DARK_GRAY + "Tail this file to share live network/view state. The file is overwritten in place each interval.");
    }

    @Director(name = "info", sync = true, description = "Show portal building instructions")
    public void info(@Param(name = "sender", contextual = true) CommandSender sender) {
        sender.sendMessage(Wormholes.tag + ChatColor.GRAY + "" + ChatColor.BOLD + "How to build a Wormhole");
        sender.sendMessage(ChatColor.DARK_GRAY + "1. " + ChatColor.GRAY + "Run " + ChatColor.WHITE + "/wormholes wand" + ChatColor.GRAY + " to receive the wand and 1 wormhole rune.");
        sender.sendMessage(ChatColor.DARK_GRAY + "2. " + ChatColor.GRAY + "Place the runes as one connected flat plane.");
        sender.sendMessage(ChatColor.GRAY + "   Rectangles, L-shapes, crosses, and other connected non-rectangular planes work.");
        sender.sendMessage(ChatColor.GRAY + "   The frame must be flat on one axis-aligned wall, floor, or ceiling.");
        sender.sendMessage(ChatColor.DARK_GRAY + "3. " + ChatColor.GRAY + "Hold the Portal Wand and " + ChatColor.WHITE + "left-click any rune block" + ChatColor.GRAY + " to form the portal.");
        sender.sendMessage(ChatColor.GRAY + "   Shapes spanning more than one plane are refunded automatically.");
        sender.sendMessage(ChatColor.DARK_GRAY + "4. " + ChatColor.GRAY + "Build a SECOND portal somewhere else (any distance, any world).");
        sender.sendMessage(ChatColor.DARK_GRAY + "5. " + ChatColor.GRAY + "Click the open portal with the wand to open the main menu.");
        sender.sendMessage(ChatColor.GRAY + "   Choose " + ChatColor.WHITE + "Link" + ChatColor.GRAY + " then click the other portal in the list. Repeat from the other side.");
        sender.sendMessage(ChatColor.GRAY + "   Sneak + click the portal opens the configs menu (Mode, Direction, Flip Face, rotation).");
        sender.sendMessage(ChatColor.DARK_GRAY + "6. " + ChatColor.GRAY + "Stand within 16 blocks of either portal — the destination world will project through the frame and walking in teleports you.");
        sender.sendMessage(ChatColor.GRAY + "Need more runes? " + ChatColor.WHITE + "/wormholes wand rune=<portal|wormhole|gateway> count=<n>");
    }
}
