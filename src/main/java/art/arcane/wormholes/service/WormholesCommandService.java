package art.arcane.wormholes.service;

import art.arcane.volmlib.util.director.compat.BukkitDirectorContext;
import art.arcane.volmlib.util.director.compat.DirectorEngineFactory;
import art.arcane.volmlib.util.director.context.DirectorContextRegistry;
import art.arcane.volmlib.util.director.help.DirectorMiniMenu;
import art.arcane.volmlib.util.director.runtime.DirectorExecutionMode;
import art.arcane.volmlib.util.director.runtime.DirectorExecutionResult;
import art.arcane.volmlib.util.director.runtime.DirectorInvocation;
import art.arcane.volmlib.util.director.runtime.DirectorInvocationHook;
import art.arcane.volmlib.util.director.runtime.DirectorRuntimeEngine;
import art.arcane.volmlib.util.director.runtime.DirectorRuntimeNode;
import art.arcane.volmlib.util.director.runtime.DirectorSender;
import art.arcane.volmlib.util.director.theme.DirectorProduct;
import art.arcane.volmlib.util.director.theme.DirectorTheme;
import art.arcane.volmlib.util.director.theme.DirectorThemes;
import art.arcane.wormholes.Wormholes;
import art.arcane.wormholes.commands.CommandWormholes;
import art.arcane.wormholes.util.common.cache.AtomicCache;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.SoundCategory;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginCommand;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.logging.Level;

public final class WormholesCommandService implements CommandExecutor, TabCompleter, DirectorInvocationHook {
    private static final String ROOT_COMMAND = "wormholes";
    private static final String ROOT_PERMISSION = "wormholes.admin";
    private static final int HELP_PAGE_SIZE = 8;
    private static final MiniMessage MINI_MESSAGE = MiniMessage.miniMessage();
    private static final LegacyComponentSerializer LEGACY_SERIALIZER = LegacyComponentSerializer.legacySection();

    private final Wormholes plugin;
    private final DirectorTheme theme;
    private final AtomicCache<DirectorRuntimeEngine> directorCache = new AtomicCache<>();

    public WormholesCommandService(Wormholes plugin) {
        this.plugin = plugin;
        this.theme = DirectorThemes.forProduct(DirectorProduct.WORMHOLES);
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
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        if (!command.getName().equalsIgnoreCase(ROOT_COMMAND)) {
            return false;
        }
        if (!sender.hasPermission(ROOT_PERMISSION)) {
            if (sendPublicCommandIfRequested(sender, args)) {
                playInfoChime(sender);
            } else {
                sender.sendMessage(Wormholes.tag + "§cYou do not have permission to use that command.");
                playFailureChime(sender);
            }
            return true;
        }

        if (sendHelpIfRequested(sender, args)) {
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
        if (!command.getName().equalsIgnoreCase(ROOT_COMMAND)) {
            return List.of();
        }
        if (!sender.hasPermission(ROOT_PERMISSION)) {
            return publicTabCompletions(args);
        }
        return runDirectorTab(sender, alias, args);
    }

	private boolean sendPublicCommandIfRequested(CommandSender sender, String[] args) {
		if(!isPublicCommandRequest(args)) {
			return false;
		}
		if(isPublicInfoRequest(args)) {
			new CommandWormholes(plugin).info(sender);
			return true;
		}
		sender.sendMessage(Wormholes.tag + "§7Portal help: §f/wormholes info");
		sender.sendMessage(Wormholes.tag + "§7Use the Portal Wand on a portal to open its destination, view, travel, and access controls.");
		return true;
	}

	static boolean isPublicCommandRequest(String[] args) {
		return isPublicHelpRequest(args) || isPublicInfoRequest(args);
	}

	private static boolean isPublicHelpRequest(String[] args) {
		return args == null || args.length == 0 || (args.length == 1 && isHelpWord(args[0]));
	}

	private static boolean isPublicInfoRequest(String[] args) {
		return args != null && args.length == 1 && "info".equalsIgnoreCase(args[0]);
	}

	static List<String> publicTabCompletions(String[] args) {
		if(args == null || args.length != 1) {
			return List.of();
		}
		String prefix = args[0] == null ? "" : args[0].toLowerCase(Locale.ROOT);
		return List.of("help", "info").stream().filter(value -> value.startsWith(prefix)).toList();
	}

    private DirectorRuntimeEngine getDirector() {
        return directorCache.aquire(this::buildDirector);
    }

    private DirectorRuntimeEngine buildDirector() {
        return DirectorEngineFactory.create(
            new CommandWormholes(plugin),
            null,
            buildDirectorContexts(),
            this::dispatchDirector,
            this,
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

    private void dispatchDirector(DirectorExecutionMode mode, Runnable runnable) {
        runnable.run();
    }

    @Override
    public void beforeInvoke(DirectorInvocation invocation, DirectorRuntimeNode node) {
        if (invocation.getSender() instanceof BukkitDirectorSender sender) {
            BukkitDirectorContext.touch(sender.sender());
        }
    }

    @Override
    public void afterInvoke(DirectorInvocation invocation, DirectorRuntimeNode node) {
        BukkitDirectorContext.remove();
    }

    private DirectorExecutionResult runDirector(CommandSender sender, String label, String[] args) {
        try {
            return getDirector().execute(new DirectorInvocation(new BukkitDirectorSender(sender), label, Arrays.asList(args)));
        } catch (Throwable e) {
            plugin.getLogger().log(Level.SEVERE, "Director command execution failed", e);
            return DirectorExecutionResult.notHandled();
        }
    }

    private List<String> runDirectorTab(CommandSender sender, String alias, String[] args) {
        try {
            return getDirector().tabComplete(new DirectorInvocation(new BukkitDirectorSender(sender), alias, Arrays.asList(args)));
        } catch (Throwable e) {
            plugin.getLogger().log(Level.WARNING, "Director tab completion failed", e);
            return List.of();
        }
    }

    private boolean sendHelpIfRequested(CommandSender sender, String[] args) {
        Optional<DirectorMiniMenu.DirectorHelpPage> page = DirectorMiniMenu.resolveHelp(getDirector(), Arrays.asList(normalizeHelpArgs(args)), HELP_PAGE_SIZE);
        if (page.isEmpty()) {
            return false;
        }

        DirectorMiniMenu.Theme helpTheme = DirectorMiniMenu.Theme.fromDirectorTheme(theme);
        for (String line : DirectorMiniMenu.render(page.get(), helpTheme)) {
            sendRich(sender, line);
        }

        return true;
    }

    static String[] normalizeHelpArgs(String[] args) {
        if (args == null || args.length == 0) {
            return new String[0];
        }

        List<String> normalized = new ArrayList<>(args.length);
        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            if (!isHelpWord(arg)) {
                normalized.add(arg);
                continue;
            }

            String page = "1";
            if (i + 1 < args.length && isPageToken(args[i + 1])) {
                page = args[i + 1].trim();
                i++;
            }
            normalized.add("help=" + page);
        }

        return normalized.toArray(new String[0]);
    }

    private static boolean isHelpWord(String value) {
        return value != null && (value.equalsIgnoreCase("help") || value.equals("?"));
    }

    private static boolean isPageToken(String value) {
        if (value == null || value.isBlank()) {
            return false;
        }

        try {
            Integer.parseInt(value.trim());
            return true;
        } catch (NumberFormatException ignored) {
            return false;
        }
    }

    private void sendRich(CommandSender sender, String miniMessage) {
        if (miniMessage == null || miniMessage.trim().isEmpty()) {
            return;
        }

        try {
            sender.getClass().getMethod("sendRichMessage", String.class).invoke(sender, miniMessage);
            return;
        } catch (Throwable ignored) {
        }

        Component component = MINI_MESSAGE.deserialize(miniMessage);
        sender.sendMessage(LEGACY_SERIALIZER.serialize(component));
    }

    private void playSuccessChime(CommandSender sender) {
        if (sender instanceof Player player) {
            player.playSound(player.getLocation(), theme.getSuccessSound(), SoundCategory.MASTER, 0.5f, 1.5f);
        }
    }

    private void playFailureChime(CommandSender sender) {
        if (sender instanceof Player player) {
            player.playSound(player.getLocation(), theme.getErrorSound(), SoundCategory.MASTER, 0.4f, 0.6f);
        }
    }

    private void playInfoChime(CommandSender sender) {
        if (sender instanceof Player player) {
            player.playSound(player.getLocation(), theme.getSuccessSound(), SoundCategory.MASTER, 0.4f, 1.0f);
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
