package art.arcane.wormholes.commands;

import art.arcane.volmlib.util.director.annotations.Director;
import art.arcane.volmlib.util.director.annotations.Param;
import art.arcane.volmlib.util.localization.MessageArgs;
import art.arcane.volmlib.util.localization.MessageArgument;
import art.arcane.volmlib.util.localization.TextKey;
import art.arcane.wormholes.Wormholes;
import art.arcane.wormholes.config.toml.NetworkConfig;
import art.arcane.wormholes.localization.WormholesLocalization;
import art.arcane.wormholes.localization.WormholesMessages;
import art.arcane.wormholes.network.NetworkManager;
import art.arcane.wormholes.service.WormholesAudience;
import org.bukkit.command.CommandSender;

import java.util.List;
import java.util.Locale;

@Director(name = "network", descriptionKey = "command.help.network", description = "Cross-server wormhole network")
public class CommandNetwork {
    @Director(name = "import", sync = true, descriptionKey = "command.help.network.import", description = "Import a portal code from another server (saves an internal route; link via a gateway's Link menu)")
    public void importCode(@Param(name = "sender", contextual = true) CommandSender sender,
                           @Param(name = "code", descriptionKey = "command.help.network.import.code", description = "Portal code from the other server's Export button") String code) {
        if (!sender.hasPermission("wormholes.admin.network")) {
            send(sender, WormholesMessages.COMMAND_NO_PERMISSION);
            return;
        }
        if (Wormholes.importExportService == null) {
            send(sender, WormholesMessages.NETWORK_NOT_INITIALIZED);
            return;
        }
        Wormholes.importExportService.importCode(sender, null, code);
    }

    @Director(name = "status", sync = true, descriptionKey = "command.help.network.status", description = "Show peer connection status")
    public void status(@Param(name = "sender", contextual = true) CommandSender sender) {
        if (!sender.hasPermission("wormholes.admin.network")) {
            send(sender, WormholesMessages.COMMAND_NO_PERMISSION);
            return;
        }
        NetworkManager network = Wormholes.networkManager;
        NetworkConfig config = Wormholes.settings.getNetwork();
        if (network == null || !config.enabled) {
            send(sender, WormholesMessages.NETWORK_DISABLED);
            return;
        }
        if (!network.isRunning()) {
            send(sender, WormholesMessages.NETWORK_NOT_RUNNING);
            return;
        }
        if (config.listenEnabled) {
            send(sender, WormholesMessages.NETWORK_LISTENING, args(
                    MessageArgument.untrusted("server", network.getLocalName()),
                    MessageArgument.untrusted("address", network.getListenAddress())
            ));
        } else {
            send(sender, WormholesMessages.NETWORK_OUTBOUND_ONLY,
                    args(MessageArgument.untrusted("server", network.getLocalName())));
        }
        send(sender, WormholesMessages.NETWORK_PUBLIC_KEY,
                args(MessageArgument.untrusted("fingerprint", network.getPublicKeyFingerprint())));
        List<NetworkManager.PeerStatus> statuses = network.status();
        if (statuses.isEmpty()) {
            send(sender, WormholesMessages.NETWORK_NO_ROUTES);
            return;
        }
        for (NetworkManager.PeerStatus status : statuses) {
            String stateColor = switch (status.state()) {
                case "CONNECTED" -> "<green>";
                case "CONNECTING", "WAITING" -> "<yellow>";
                default -> "<red>";
            };
            String rtt = status.rttMillis() >= 0 ? " " + status.rttMillis() + "ms" : "";
            send(sender, WormholesMessages.NETWORK_PEER, args(
                    MessageArgument.untrusted("server", status.name()),
                    MessageArgument.trusted("state", stateColor + escapeState(status.state())),
                    MessageArgument.untrusted("address", status.address()),
                    MessageArgument.untrusted("rtt", rtt)
            ));
            if (status.lastError() != null && !status.state().equals("CONNECTED")) {
                send(sender, WormholesMessages.NETWORK_LAST_ATTEMPT,
                        args(MessageArgument.untrusted("error", status.lastError())));
            }
        }
        if (hasUnconnectedPeer(statuses)) {
            printDiagnostics(sender, network);
        }
    }

    @Director(name = "doctor", sync = true, descriptionKey = "command.help.network.doctor", description = "Explain why network peers are not connecting")
    public void doctor(@Param(name = "sender", contextual = true) CommandSender sender) {
        if (!sender.hasPermission("wormholes.admin.network")) {
            send(sender, WormholesMessages.COMMAND_NO_PERMISSION);
            return;
        }
        NetworkManager network = Wormholes.networkManager;
        if (network == null) {
            send(sender, WormholesMessages.NETWORK_NOT_INITIALIZED);
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
            send(sender, WormholesMessages.NETWORK_DOCTOR_CLEAR);
            return;
        }
        send(sender, WormholesMessages.NETWORK_DOCTOR_HEADER);
        for (String diagnostic : diagnostics) {
            send(sender, WormholesMessages.NETWORK_DOCTOR_LINE,
                    args(MessageArgument.untrusted("diagnostic", diagnostic)));
        }
    }

    private static MessageArgs args(MessageArgument... arguments) {
        return WormholesLocalization.args(arguments);
    }

    private static String escapeState(String state) {
        return state.toLowerCase(Locale.ROOT).replace("<", "").replace(">", "");
    }

    private static void send(CommandSender sender, TextKey key) {
        send(sender, key, MessageArgs.empty());
    }

    private static void send(CommandSender sender, TextKey key, MessageArgs arguments) {
        WormholesAudience.sendMessage(sender, Wormholes.text().component(key, arguments));
    }
}
