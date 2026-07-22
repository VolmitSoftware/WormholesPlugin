package art.arcane.wormholes.commands;

import art.arcane.volmlib.util.collection.KList;
import art.arcane.volmlib.util.director.DirectorParameterHandler;
import art.arcane.volmlib.util.director.annotations.Director;
import art.arcane.volmlib.util.director.annotations.Param;
import art.arcane.volmlib.util.director.exceptions.DirectorParsingException;
import art.arcane.volmlib.util.localization.LinesKey;
import art.arcane.volmlib.util.localization.LocalizationReloadResult;
import art.arcane.volmlib.util.localization.MessageArgs;
import art.arcane.volmlib.util.localization.MessageArgument;
import art.arcane.volmlib.util.localization.TextKey;
import art.arcane.wormholes.Settings;
import art.arcane.wormholes.Wormholes;
import art.arcane.wormholes.door.DimensionalDoorManager;
import art.arcane.wormholes.localization.WormholesLocalization;
import art.arcane.wormholes.localization.WormholesMessages;
import art.arcane.wormholes.service.StatsSnapshotWriter;
import art.arcane.wormholes.service.WormholesAudience;
import net.kyori.adventure.text.Component;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

@Director(name = "wormholes", aliases = {"wh", "wormhole"}, descriptionKey = "command.help.root", description = "Wormholes command root")
public class CommandWormholes {
    private static final List<String> DOOR_TYPE_COMPLETIONS = List.of("pair", "personal", "public");

    private final Wormholes plugin;
    private CommandAdmin admin = new CommandAdmin();
    private CommandNetwork network = new CommandNetwork();

    public CommandWormholes(Wormholes plugin) {
        this.plugin = plugin;
    }

    @Director(name = "wand", sync = true, descriptionKey = "command.help.wand", description = "Give yourself the portal wand, or runes with rune=<type>")
    public void wand(@Param(name = "sender", contextual = true) CommandSender sender,
                     @Param(name = "rune", descriptionKey = "command.help.wand.rune", description = "portal | wormhole | gateway", defaultValue = "none") String rune,
                     @Param(name = "count", descriptionKey = "command.help.wand.count", description = "How many runes (default 1)", defaultValue = "1") int count) {
        if (!sender.hasPermission("wormholes.admin.items")) {
            send(sender, WormholesMessages.COMMAND_NO_PERMISSION);
            return;
        }
        if (!(sender instanceof Player player)) {
            send(sender, WormholesMessages.COMMAND_ONLY_PLAYERS);
            return;
        }

        String runeType = rune == null ? "none" : rune.toLowerCase(Locale.ROOT);
        if (!runeType.equals("none")) {
            int safeCount = Math.max(1, Math.min(count, 64));
            ItemStack runes = switch (runeType) {
                case "portal" -> Wormholes.blockManager.getPortalRune(safeCount);
                case "wormhole" -> Wormholes.blockManager.getWormholeRune(safeCount);
                case "gateway" -> Wormholes.blockManager.getGatewayRune(safeCount);
                default -> null;
            };
            if (runes == null) {
                send(sender, WormholesMessages.COMMAND_UNKNOWN_RUNE, args("rune", runeType));
                return;
            }
            player.getInventory().addItem(runes);
            WormholesAudience.sendMessage(player, Wormholes.text().component(
                    WormholesMessages.COMMAND_GRANTED_RUNES,
                    args("count", Integer.valueOf(safeCount), "rune", runeType)
            ));
            return;
        }

        ItemStack wand = Wormholes.blockManager.getWand();
        ItemStack wormholeRune = Wormholes.blockManager.getWormholeRune(1);
        player.getInventory().addItem(wand, wormholeRune);
        sendLines(player, WormholesMessages.COMMAND_GRANTED_STARTER, MessageArgs.empty());
    }

    @Director(name = "door", sync = true, descriptionKey = "command.help.door", description = "Give a survival Dimensional Door item")
    public void door(@Param(name = "sender", contextual = true) CommandSender sender,
                     @Param(name = "type", descriptionKey = "command.help.door.type", description = "pair | personal | public", defaultValue = "pair", customHandler = DoorTypeHandler.class) String type) {
        if (!sender.hasPermission("wormholes.admin.items")) {
            send(sender, WormholesMessages.COMMAND_NO_PERMISSION);
            return;
        }
        if (!(sender instanceof Player player)) {
            send(sender, WormholesMessages.COMMAND_ONLY_PLAYERS);
            return;
        }
        DimensionalDoorManager manager = plugin.getDimensionalDoorManager();
        if (!Settings.DIMENSIONAL_DOORS_ENABLED || manager == null) {
            send(sender, WormholesMessages.COMMAND_DOORS_UNAVAILABLE);
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
            send(sender, WormholesMessages.COMMAND_UNKNOWN_DOOR);
            return;
        }
        player.getInventory().addItem(item).values().forEach(overflow ->
                player.getWorld().dropItemNaturally(player.getLocation(), overflow));
        send(sender, WormholesMessages.COMMAND_GRANTED_DOOR, args("type", normalized));
    }

    @Director(name = "reload", sync = true, descriptionKey = "command.help.reload", description = "Reload Wormholes configuration and language files")
    public void reload(@Param(name = "sender", contextual = true) CommandSender sender) {
        if (!sender.hasPermission("wormholes.admin.reload")) {
            send(sender, WormholesMessages.COMMAND_NO_PERMISSION);
            return;
        }
        LocalizationReloadResult result = plugin.reloadAll();
        send(sender, result.applied()
                ? WormholesMessages.COMMAND_RELOADED
                : WormholesMessages.COMMAND_RELOADED_LANGUAGE_RETAINED);
    }

    @Director(name = "debug", sync = true, descriptionKey = "command.help.debug", description = "Toggle verbose console logs and one-second telemetry")
    public void debug(@Param(name = "sender", contextual = true) CommandSender sender) {
        if (!sender.hasPermission("wormholes.admin")) {
            send(sender, WormholesMessages.COMMAND_NO_PERMISSION);
            return;
        }
        plugin.toggleDebugTelemetry(sender.getName());
    }

    @Director(name = "stats", sync = true, descriptionKey = "command.help.stats", description = "Print the live stats-snapshot file path, optionally force a refresh with now=true")
    public void stats(@Param(name = "sender", contextual = true) CommandSender sender,
                      @Param(name = "now", descriptionKey = "command.help.stats.now", description = "Force-rebuild the snapshot synchronously", defaultValue = "false") boolean now) {
        if (!sender.hasPermission("wormholes.admin")) {
            send(sender, WormholesMessages.COMMAND_NO_PERMISSION);
            return;
        }
        StatsSnapshotWriter writer = plugin.getStatsSnapshotWriter();
        if (writer == null) {
            send(sender, WormholesMessages.COMMAND_STATS_UNAVAILABLE);
            return;
        }
        if (now) {
            writer.writeNow();
            send(sender, WormholesMessages.COMMAND_STATS_REFRESHED);
        }
        Path output = writer.getOutputFile();
        sendLines(sender, WormholesMessages.COMMAND_STATS_PATH, args("path", output.toAbsolutePath()));
    }

    @Director(name = "info", sync = true, descriptionKey = "command.help.info", description = "Show portal building instructions")
    public void info(@Param(name = "sender", contextual = true) CommandSender sender) {
        sendLines(sender, WormholesMessages.COMMAND_INFO, MessageArgs.empty());
    }

    private static MessageArgs args(String name, Object value) {
        return WormholesLocalization.args(MessageArgument.untrusted(name, value));
    }

    private static MessageArgs args(String firstName, Object firstValue, String secondName, Object secondValue) {
        return WormholesLocalization.args(
                MessageArgument.untrusted(firstName, firstValue),
                MessageArgument.untrusted(secondName, secondValue)
        );
    }

    private static void send(CommandSender sender, TextKey key) {
        send(sender, key, MessageArgs.empty());
    }

    private static void send(CommandSender sender, TextKey key, MessageArgs arguments) {
        WormholesAudience.sendMessage(sender, Wormholes.text().component(key, arguments));
    }

    private static void sendLines(CommandSender sender, LinesKey key, MessageArgs arguments) {
        for (Component line : Wormholes.text().components(key, arguments)) {
            WormholesAudience.sendMessage(sender, line);
        }
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
                throw new DirectorParsingException(Wormholes.text().plain(WormholesMessages.COMMAND_EMPTY_DOOR));
            }
            return input.trim();
        }

        @Override
        public boolean supports(Class<?> type) {
            return type == String.class;
        }
    }
}
