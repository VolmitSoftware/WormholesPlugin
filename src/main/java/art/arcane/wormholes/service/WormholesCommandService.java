package art.arcane.wormholes.service;

import art.arcane.wormholes.Wormholes;
import art.arcane.wormholes.commands.CommandWormholes;
import art.arcane.volmlib.util.director.compat.DirectorEngineFactory;
import art.arcane.volmlib.util.director.context.DirectorContextRegistry;
import art.arcane.volmlib.util.director.runtime.DirectorExecutionResult;
import art.arcane.volmlib.util.director.runtime.DirectorInvocation;
import art.arcane.volmlib.util.director.runtime.DirectorRuntimeEngine;
import art.arcane.volmlib.util.director.runtime.DirectorSender;
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

public final class WormholesCommandService implements CommandExecutor, TabCompleter {
    private static final String ROOT_COMMAND = "wormholes";
    private static final String ROOT_PERMISSION = "wormholes.admin";

    private final Wormholes plugin;
    private volatile DirectorRuntimeEngine director;

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

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        if (!command.getName().equalsIgnoreCase(ROOT_COMMAND)) {
            return false;
        }
        if (!sender.hasPermission(ROOT_PERMISSION) && args.length > 0) {
            sender.sendMessage("§cYou do not have permission.");
            return true;
        }
        DirectorExecutionResult result = runDirector(sender, label, args);
        if (result.isSuccess()) {
            return true;
        }
        sender.sendMessage("§7Usage: /wormholes [wand|rune|info|reload]");
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
        DirectorRuntimeEngine local = director;
        if (local != null) {
            return local;
        }
        synchronized (this) {
            if (director != null) {
                return director;
            }
            director = DirectorEngineFactory.create(
                new CommandWormholes(plugin),
                null,
                buildDirectorContexts(),
                null,
                null,
                null
            );
            return director;
        }
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
