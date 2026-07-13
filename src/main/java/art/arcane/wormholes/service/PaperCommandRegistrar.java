package art.arcane.wormholes.service;

import art.arcane.wormholes.Wormholes;
import io.papermc.paper.command.brigadier.BasicCommand;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import org.bukkit.command.CommandSender;

import java.util.Collection;
import java.util.List;

final class PaperCommandRegistrar {
    private static final String ROOT_COMMAND = "wormholes";

    private PaperCommandRegistrar() {
    }

    static void register(Wormholes plugin, WormholesCommandService commandService) {
        plugin.getLifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS, event ->
            event.registrar().register(
                ROOT_COMMAND,
                "Wormholes base command.",
                List.of("wh", "wormhole"),
                new PaperCommand(commandService)
            )
        );
    }

    private record PaperCommand(WormholesCommandService commandService) implements BasicCommand {
        @Override
        public void execute(CommandSourceStack source, String[] args) {
            commandService.executeCommand(source.getSender(), ROOT_COMMAND, args);
        }

        @Override
        public Collection<String> suggest(CommandSourceStack source, String[] args) {
            return commandService.tabComplete(source.getSender(), ROOT_COMMAND, args);
        }

        @Override
        public boolean canUse(CommandSender sender) {
            return true;
        }
    }
}
