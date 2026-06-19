package art.arcane.wormholes.commands;

import art.arcane.volmlib.util.director.annotations.Director;
import art.arcane.volmlib.util.director.annotations.Param;
import art.arcane.wormholes.Wormholes;
import art.arcane.wormholes.config.toml.NetworkConfig;
import art.arcane.wormholes.network.NetworkManager;
import org.bukkit.ChatColor;
import org.bukkit.command.CommandSender;

import java.util.List;

@Director(name = "network", description = "Cross-server wormhole network")
public class CommandNetwork {
    @Director(name = "import", sync = true, description = "Import a portal code from another server (saves an internal route; link via a gateway's Link menu)")
    public void importCode(@Param(name = "sender", contextual = true) CommandSender sender,
                           @Param(name = "code", description = "Portal code from the other server's Export button") String code) {
        if (!sender.hasPermission("wormholes.admin.network")) {
            sender.sendMessage(Wormholes.tag + ChatColor.RED + "You do not have permission.");
            return;
        }
        if (Wormholes.importExportService == null) {
            sender.sendMessage(Wormholes.tag + ChatColor.RED + "Networking is not initialized.");
            return;
        }
        Wormholes.importExportService.importCode(sender, null, code);
    }

    @Director(name = "status", sync = true, description = "Show peer connection status")
    public void status(@Param(name = "sender", contextual = true) CommandSender sender) {
        if (!sender.hasPermission("wormholes.admin.network")) {
            sender.sendMessage(Wormholes.tag + ChatColor.RED + "You do not have permission.");
            return;
        }
        NetworkManager network = Wormholes.networkManager;
        NetworkConfig config = Wormholes.settings.getNetwork();
        if (network == null || !config.enabled) {
            sender.sendMessage(Wormholes.tag + ChatColor.GRAY + "Networking is " + ChatColor.RED + "disabled" + ChatColor.GRAY + " (config/wormholes.toml).");
            return;
        }
        if (!network.isRunning()) {
            sender.sendMessage(Wormholes.tag + ChatColor.RED + "Networking is enabled but not running. Check the identity store and network port.");
            return;
        }
        if (config.listenEnabled) {
            sender.sendMessage(Wormholes.tag + ChatColor.GRAY + "This server: " + ChatColor.WHITE + network.getLocalName() + ChatColor.GRAY + " listening on " + ChatColor.WHITE + network.getListenAddress());
        } else {
            sender.sendMessage(Wormholes.tag + ChatColor.GRAY + "This server: " + ChatColor.WHITE + network.getLocalName() + ChatColor.GRAY + " outbound-only Boat mode");
        }
        sender.sendMessage(Wormholes.tag + ChatColor.DARK_GRAY + "Public key: " + network.getPublicKeyFingerprint());
        List<NetworkManager.PeerStatus> statuses = network.status();
        if (statuses.isEmpty()) {
            sender.sendMessage(Wormholes.tag + ChatColor.GRAY + "No peer routes linked yet.");
            return;
        }
        for (NetworkManager.PeerStatus status : statuses) {
            ChatColor stateColor = switch (status.state()) {
                case "CONNECTED" -> ChatColor.GREEN;
                case "CONNECTING", "WAITING" -> ChatColor.YELLOW;
                default -> ChatColor.RED;
            };
            String rtt = status.rttMillis() >= 0 ? ChatColor.DARK_GRAY + " " + status.rttMillis() + "ms" : "";
            sender.sendMessage(Wormholes.tag + ChatColor.WHITE + status.name() + ChatColor.GRAY + " " + stateColor + status.state().toLowerCase() + ChatColor.GRAY + " " + status.address() + rtt);
            if (status.lastError() != null && !status.state().equals("CONNECTED")) {
                sender.sendMessage(Wormholes.tag + ChatColor.DARK_GRAY + "  last attempt: " + status.lastError());
            }
        }
        if (hasUnconnectedPeer(statuses)) {
            printDiagnostics(sender, network);
        }
    }

    @Director(name = "doctor", sync = true, description = "Explain why network peers are not connecting")
    public void doctor(@Param(name = "sender", contextual = true) CommandSender sender) {
        if (!sender.hasPermission("wormholes.admin.network")) {
            sender.sendMessage(Wormholes.tag + ChatColor.RED + "You do not have permission.");
            return;
        }
        NetworkManager network = Wormholes.networkManager;
        if (network == null) {
            sender.sendMessage(Wormholes.tag + ChatColor.RED + "Networking is not initialized.");
            return;
        }
        printDiagnostics(sender, network);
    }

    private static boolean hasUnconnectedPeer(List<NetworkManager.PeerStatus> statuses) {
        for (NetworkManager.PeerStatus status : statuses) {
            if (!status.state().equals("CONNECTED")) {
                return true;
            }
        }
        return false;
    }

    private static void printDiagnostics(CommandSender sender, NetworkManager network) {
        List<String> diagnostics = network.diagnostics();
        if (diagnostics.isEmpty()) {
            sender.sendMessage(Wormholes.tag + ChatColor.GREEN + "No network setup issues detected.");
            return;
        }
        sender.sendMessage(Wormholes.tag + ChatColor.YELLOW + "Network doctor:");
        for (String diagnostic : diagnostics) {
            sender.sendMessage(Wormholes.tag + ChatColor.GRAY + "- " + diagnostic);
        }
    }
}
