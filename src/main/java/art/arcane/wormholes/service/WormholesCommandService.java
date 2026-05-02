package art.arcane.wormholes.service;

import art.arcane.volmlib.util.collection.KList;
import art.arcane.volmlib.util.director.compat.DirectorEngineFactory;
import art.arcane.volmlib.util.director.context.DirectorContextRegistry;
import art.arcane.volmlib.util.director.runtime.DirectorExecutionResult;
import art.arcane.volmlib.util.director.runtime.DirectorInvocation;
import art.arcane.volmlib.util.director.runtime.DirectorRuntimeEngine;
import art.arcane.volmlib.util.director.runtime.DirectorSender;
import art.arcane.volmlib.util.director.visual.DirectorVisualCommand;
import art.arcane.volmlib.util.director.visual.DirectorVisualCommand.HelpRequest;
import art.arcane.wormholes.Wormholes;
import art.arcane.wormholes.commands.CommandWormholes;
import art.arcane.wormholes.util.common.cache.AtomicCache;
import org.bukkit.Sound;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginCommand;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;

public final class WormholesCommandService implements CommandExecutor, TabCompleter {
    private static final String ROOT_COMMAND = "wormholes";
    private static final String ROOT_PERMISSION = "wormholes.admin";
    private static final int HELP_PAGE_SIZE = 8;

    private final Wormholes plugin;
    private final AtomicCache<DirectorRuntimeEngine> directorCache = new AtomicCache<>();
    private final AtomicCache<DirectorVisualCommand> visualCache = new AtomicCache<>();

    public WormholesCommandService(Wormholes plugin) {
        this.plugin = plugin;
    }

    public void register() {
        PluginCommand command = plugin.getCommand(ROOT_COMMAND);
        if (command == null) {
            plugin.getLogger().warning("Failed to find command '" + ROOT_COMMAND + "'");
            return;
        }
        command.setExecutor(this);
        command.setTabCompleter(this);
        getDirector();
    }

    public void invalidateCache() {
        directorCache.invalidate();
        visualCache.invalidate();
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        if (!command.getName().equalsIgnoreCase(ROOT_COMMAND)) {
            return false;
        }
        if (!sender.hasPermission(ROOT_PERMISSION)) {
            sender.sendMessage("§cYou do not have permission.");
            playFailureChime(sender);
            return true;
        }

        Optional<HelpRequest> help = resolveHelpRequest(args);
        if (help.isPresent()) {
            renderHelp(sender, help.get());
            playInfoChime(sender);
            return true;
        }

        DirectorExecutionResult result = runDirector(sender, label, args);
        if (result.isSuccess()) {
            playSuccessChime(sender);
            return true;
        }

        sender.sendMessage("§7Usage: §f/wormholes help");
        playFailureChime(sender);
        return true;
    }

    @Nullable
    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String alias, @NotNull String[] args) {
        if (!command.getName().equalsIgnoreCase(ROOT_COMMAND) || !sender.hasPermission(ROOT_PERMISSION)) {
            return List.of();
        }
        return runDirectorTab(sender, alias, args);
    }

    private DirectorRuntimeEngine getDirector() {
        return directorCache.aquire(this::buildDirector);
    }

    private DirectorVisualCommand getVisual() {
        return visualCache.aquire(() -> DirectorVisualCommand.createRoot(getDirector()));
    }

    private DirectorRuntimeEngine buildDirector() {
        return DirectorEngineFactory.create(
            new CommandWormholes(plugin),
            null,
            buildDirectorContexts(),
            null,
            null,
            null
        );
    }

    private DirectorContextRegistry buildDirectorContexts() {
        DirectorContextRegistry contexts = new DirectorContextRegistry();
        contexts.register(CommandSender.class, (invocation, map) -> {
            if (invocation.getSender() instanceof BukkitDirectorSender sender) {
                return sender.sender();
            }
            return null;
        });
        contexts.register(Player.class, (invocation, map) -> {
            if (invocation.getSender() instanceof BukkitDirectorSender sender && sender.sender() instanceof Player player) {
                return player;
            }
            return null;
        });
        return contexts;
    }

    private DirectorExecutionResult runDirector(CommandSender sender, String label, String[] args) {
        try {
            return getDirector().execute(new DirectorInvocation(new BukkitDirectorSender(sender), label, Arrays.asList(args)));
        } catch (Throwable e) {
            plugin.getLogger().warning("Director command execution failed: " + e.getClass().getSimpleName() + " " + e.getMessage());
            return DirectorExecutionResult.notHandled();
        }
    }

    private List<String> runDirectorTab(CommandSender sender, String alias, String[] args) {
        try {
            return getDirector().tabComplete(new DirectorInvocation(new BukkitDirectorSender(sender), alias, Arrays.asList(args)));
        } catch (Throwable e) {
            plugin.getLogger().warning("Director tab completion failed: " + e.getClass().getSimpleName() + " " + e.getMessage());
            return List.of();
        }
    }

    private Optional<HelpRequest> resolveHelpRequest(String[] args) {
        if (args.length == 0) {
            return Optional.of(new HelpRequest(getVisual(), 0));
        }
        if (args.length == 1 && (args[0].equalsIgnoreCase("help") || args[0].equalsIgnoreCase("?"))) {
            return Optional.of(new HelpRequest(getVisual(), 0));
        }
        if (args.length == 2 && (args[0].equalsIgnoreCase("help") || args[0].equalsIgnoreCase("?"))) {
            int page = parsePage(args[1]);
            return Optional.of(new HelpRequest(getVisual(), page));
        }
        return Optional.empty();
    }

    private int parsePage(String raw) {
        try {
            return Math.max(0, Integer.parseInt(raw.trim()) - 1);
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }

    private void renderHelp(CommandSender sender, HelpRequest request) {
        DirectorVisualCommand root = request.command();
        KList<DirectorVisualCommand> children = root.getNodes();
        int total = children.size();
        if (total == 0) {
            sender.sendMessage("§7No subcommands available.");
            return;
        }
        int totalPages = Math.max(1, (int) Math.ceil(total / (double) HELP_PAGE_SIZE));
        int page = Math.min(request.page(), totalPages - 1);
        int start = page * HELP_PAGE_SIZE;
        int end = Math.min(start + HELP_PAGE_SIZE, total);

        sender.sendMessage("§8§m-----§r §6Wormholes §8(§7page " + (page + 1) + "/" + totalPages + "§8) §m-----");
        for (int i = start; i < end; i++) {
            DirectorVisualCommand child = children.get(i);
            String description = child.getDescription();
            if (description == null || description.isBlank()) {
                description = "§7No description";
            }
            sender.sendMessage("§e/" + ROOT_COMMAND + " " + child.getName() + " §8- §f" + description);
        }
        if (totalPages > 1) {
            sender.sendMessage("§8§oUse §f§o/" + ROOT_COMMAND + " help <page> §8§oto see more.");
        }
    }

    private void playSuccessChime(CommandSender sender) {
        if (sender instanceof Player player) {
            player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 0.5f, 1.5f);
        }
    }

    private void playFailureChime(CommandSender sender) {
        if (sender instanceof Player player) {
            player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 0.4f, 0.6f);
        }
    }

    private void playInfoChime(CommandSender sender) {
        if (sender instanceof Player player) {
            player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 0.4f, 1.0f);
        }
    }

    private record BukkitDirectorSender(CommandSender sender) implements DirectorSender {
        @Override
        public String getName() {
            return sender.getName();
        }

        @Override
        public boolean isPlayer() {
            return sender instanceof Player;
        }

        @Override
        public void sendMessage(String message) {
            if (message != null && !message.trim().isEmpty()) {
                sender.sendMessage(message);
            }
        }
    }
}
