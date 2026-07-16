package art.arcane.wormholes.commands;

import art.arcane.volmlib.util.collection.KList;
import art.arcane.volmlib.util.director.DirectorParameterHandler;
import art.arcane.volmlib.util.director.annotations.Director;
import art.arcane.volmlib.util.director.annotations.Param;
import art.arcane.volmlib.util.director.exceptions.DirectorParsingException;
import art.arcane.wormholes.Settings;
import art.arcane.wormholes.Wormholes;
import art.arcane.wormholes.door.DimensionalDoorManager;
import art.arcane.wormholes.service.StatsSnapshotWriter;
import org.bukkit.ChatColor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

@Director(name = "wormholes", aliases = {"wh", "wormhole"}, description = "Wormholes command root")
public class CommandWormholes {
    private static final List<String> DOOR_TYPE_COMPLETIONS = List.of("pair", "personal", "public");

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
        player.sendMessage(Wormholes.tag + ChatColor.GRAY + "Build TWO wormhole-rune shapes (any connected shape on one flat surface), link them, and stand within 16 blocks to see the projection.");
        player.sendMessage(Wormholes.tag + ChatColor.GRAY + "Run " + ChatColor.WHITE + "/wormholes info" + ChatColor.GRAY + " for the full step-by-step.");
    }

    @Director(name = "door", sync = true, description = "Give a survival Dimensional Door item")
    public void door(@Param(name = "sender", contextual = true) CommandSender sender,
                     @Param(name = "type", description = "pair | personal | public", defaultValue = "pair", customHandler = DoorTypeHandler.class) String type) {
        if (!sender.hasPermission("wormholes.admin.items")) {
            sender.sendMessage(Wormholes.tag + ChatColor.RED + "You do not have permission.");
            return;
        }
        if (!(sender instanceof Player player)) {
            sender.sendMessage(Wormholes.tag + ChatColor.RED + "Only players can receive items.");
            return;
        }
        DimensionalDoorManager manager = plugin.getDimensionalDoorManager();
        if (!Settings.DIMENSIONAL_DOORS_ENABLED || manager == null) {
            sender.sendMessage(Wormholes.tag + ChatColor.RED + "Dimensional Doors are unavailable.");
            return;
        }

        String normalized = type == null ? "pair" : type.toLowerCase(Locale.ROOT);
        ItemStack item = switch (normalized) {
            case "pair" -> manager.items().createPairKit();
            case "personal" -> manager.items().createPersonalDoor();
            case "public" -> manager.items().createPublicDoor();
            default -> null;
        };
        if (item == null) {
            sender.sendMessage(Wormholes.tag + ChatColor.RED + "Unknown door type. Use pair, personal, or public.");
            return;
        }
        player.getInventory().addItem(item).values().forEach(overflow ->
            player.getWorld().dropItemNaturally(player.getLocation(), overflow));
        sender.sendMessage(Wormholes.tag + ChatColor.GREEN + "Granted a " + ChatColor.WHITE + normalized + ChatColor.GREEN + " dimensional door item.");
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

    @Director(name = "debug", sync = true, description = "Toggle verbose console logs and one-second telemetry")
    public void debug(@Param(name = "sender", contextual = true) CommandSender sender) {
        if (!sender.hasPermission("wormholes.admin")) {
            sender.sendMessage(Wormholes.tag + ChatColor.RED + "You do not have permission.");
            return;
        }
        plugin.toggleDebugTelemetry(sender.getName());
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
            sender.sendMessage(Wormholes.tag + ChatColor.RED + "Stats snapshot writer is unavailable.");
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
		sender.sendMessage(ChatColor.DARK_GRAY + "1. " + ChatColor.GRAY + "Get a Portal Wand and portal runes from your server or an administrator.");
        sender.sendMessage(ChatColor.DARK_GRAY + "2. " + ChatColor.GRAY + "Place the runes in any connected shape on one flat surface.");
        sender.sendMessage(ChatColor.GRAY + "   Any connected shape works: rectangles, lines (3x1), single blocks, L-shapes, crosses.");
        sender.sendMessage(ChatColor.GRAY + "   The runes must sit flat on one axis-aligned wall, floor, or ceiling.");
        sender.sendMessage(ChatColor.DARK_GRAY + "3. " + ChatColor.GRAY + "Hold the Portal Wand and " + ChatColor.WHITE + "left-click any rune block" + ChatColor.GRAY + " to form the portal.");
        sender.sendMessage(ChatColor.GRAY + "   Shapes that do not sit flat on one surface are refunded automatically.");
        sender.sendMessage(ChatColor.DARK_GRAY + "4. " + ChatColor.GRAY + "Build a SECOND portal somewhere else (any distance, any world).");
        sender.sendMessage(ChatColor.DARK_GRAY + "5. " + ChatColor.GRAY + "Click the open portal with the wand to open the main menu.");
		sender.sendMessage(ChatColor.GRAY + "   Choose " + ChatColor.WHITE + "Destination" + ChatColor.GRAY + " and select the other portal. Repeat from the other side.");
		sender.sendMessage(ChatColor.GRAY + "   Orientation and access controls are grouped into their own simple menus.");
        sender.sendMessage(ChatColor.DARK_GRAY + "6. " + ChatColor.GRAY + "Stand within 16 blocks of either portal — the destination world will project through the frame and walking in teleports you.");
        sender.sendMessage(ChatColor.GRAY + "Administrators can create supplies with " + ChatColor.WHITE + "/wormholes wand rune=<portal|wormhole|gateway> count=<n>");
		sender.sendMessage(ChatColor.GRAY + "Dimensional Doors are crafted with Wormhole Runes. Open a placed door and physically cross its threshold to travel; a closed door never activates.");
    }

    public static final class DoorTypeHandler implements DirectorParameterHandler<String> {
        @Override
        public KList<String> getPossibilities() {
            return new KList<>(DOOR_TYPE_COMPLETIONS);
        }

        @Override
        public String toString(String value) {
            return value == null ? "" : value;
        }

        @Override
        public String parse(String input, boolean force) throws DirectorParsingException {
            if (input == null || input.trim().isEmpty()) {
                throw new DirectorParsingException("Door type cannot be empty");
            }
            return input.trim();
        }

        @Override
        public boolean supports(Class<?> type) {
            return type == String.class;
        }
    }
}
