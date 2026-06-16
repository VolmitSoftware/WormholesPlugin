package art.arcane.wormholes.network;

import art.arcane.volmlib.util.scheduling.FoliaScheduler;
import art.arcane.wormholes.Wormholes;
import art.arcane.wormholes.config.toml.NetworkConfig;
import art.arcane.wormholes.portal.ILocalPortal;
import art.arcane.wormholes.service.WormholesAudience;
import art.arcane.wormholes.util.project.config.TomlCodec;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

public final class ImportExportService {
    private static final int CHAT_SAFE_CODE_LENGTH = 250;

    private final NetworkManager network;

    public ImportExportService(NetworkManager network) {
        this.network = network;
    }

    public void exportToChat(Player player, ILocalPortal portal) {
        WormholesAudience.sendMessage(player, Component.text("Building portal code...", NamedTextColor.DARK_GRAY));
        FoliaScheduler.runAsync(Wormholes.instance, () -> exportNow(player, portal));
    }

    public void importCode(CommandSender sender, ILocalPortal portal, String raw) {
        FoliaScheduler.runAsync(Wormholes.instance, () -> importNow(sender, portal, raw));
    }

    private void exportNow(Player player, ILocalPortal portal) {
        NetworkConfig config = Wormholes.settings.getNetwork();
        String advertiseHost = resolveAdvertiseHost(player, config);
        boolean configChanged = false;
        if (!config.enabled) {
            config.enabled = true;
            configChanged = true;
        }
        if (configChanged) {
            persistConfig(config);
        }
        if (!network.isRunning()) {
            network.start();
        }

        PortalCode code = new PortalCode(
            network.getLocalName(),
            advertiseHost,
            alternateHosts(advertiseHost),
            network.getBoundListenPort(),
            Bukkit.getPort(),
            network.getPublicKey(),
            portal.getId(),
            portal.getName()
        );
        String encoded = code.encode();

        Component message = Component.text("[Copy portal code: " + portal.getName() + "]", NamedTextColor.GOLD, TextDecoration.BOLD)
            .clickEvent(ClickEvent.copyToClipboard(encoded))
            .hoverEvent(HoverEvent.showText(Component.text("Click to copy. Paste it on the other server:\nportal menu > Import, or /wh network import <code>", NamedTextColor.GRAY)));
        WormholesAudience.sendMessage(player, message);
        WormholesAudience.sendMessage(player, Component.text("Contains this server's address and public key fingerprint " + network.getPublicKeyFingerprint() + ".", NamedTextColor.DARK_GRAY));
        if (encoded.length() > CHAT_SAFE_CODE_LENGTH) {
            WormholesAudience.sendMessage(player, Component.text("This code is too long to paste into chat - use /wh network import <code> on the other server instead.", NamedTextColor.YELLOW));
        }
    }

    private void importNow(CommandSender sender, ILocalPortal portal, String raw) {
        PortalCode code = PortalCode.decode(raw);
        if (code == null) {
            WormholesAudience.sendMessage(sender, Component.text("Invalid portal code. Codes start with " + PortalCode.PREFIX + " - if pasted into chat it may have been truncated; try /wh network import <code>. Codes from older plugin versions must be re-exported.", NamedTextColor.RED));
            return;
        }

        NetworkConfig config = Wormholes.settings.getNetwork();

        if (code.serverName().equals(network.getLocalName())) {
            boolean ownPortal = Wormholes.portalManager != null && Wormholes.portalManager.getLocalPortal(code.portalId()) != null;
            if (ownPortal) {
                WormholesAudience.sendMessage(sender, Component.text("That code is from this server.", NamedTextColor.RED));
            } else {
                WormholesAudience.sendMessage(sender, Component.text("That code resolved to this server identity (" + code.serverName() + "). Re-export from the other server after both servers restart with their own Wormholes identity.", NamedTextColor.RED));
            }
            return;
        }

        network.trustPeer(code.serverName(), code.publicKey());

        NetworkConfig.PeerEntry entry = new NetworkConfig.PeerEntry();
        entry.name = code.serverName();
        entry.host = code.advertiseHost();
        entry.fallbackHosts = joinFallbacks(code.advertiseHost(), code.fallbackHosts());
        entry.port = code.wormholePort();
        entry.publicHost = code.advertiseHost();
        entry.publicPort = code.gamePort() > 0 ? code.gamePort() : 25565;
        network.savePeer(entry);

        if (!config.enabled) {
            config.enabled = true;
            persistConfig(config);
        }

        if (!network.isRunning()) {
            network.start();
        }

        if (portal != null) {
            FoliaScheduler.runRegion(Wormholes.instance, portal.getCenter(), () -> portal.linkRemote(code.serverName(), code.portalId()));
            WormholesAudience.sendMessage(sender, Component.text("Linked ", NamedTextColor.GREEN)
                .append(Component.text(portal.getName(), NamedTextColor.WHITE))
                .append(Component.text(" -> ", NamedTextColor.GRAY))
                .append(Component.text(code.portalName(), NamedTextColor.WHITE))
                .append(Component.text(" on ", NamedTextColor.GREEN))
                .append(Component.text(code.serverName(), NamedTextColor.WHITE))
                .append(Component.text(". It opens once the servers connect.", NamedTextColor.GREEN)));
        } else {
            WormholesAudience.sendMessage(sender, Component.text("Saved route to " + code.serverName() + " with public key " + Handshake.fingerprint(Handshake.decodePublicKeyText(code.publicKey())) + ". '" + code.portalName() + "' will appear in gateway Link menus once connected.", NamedTextColor.GREEN));
        }
        WormholesAudience.sendMessage(sender, Component.text("Check /wh network status for the connection state.", NamedTextColor.DARK_GRAY));
    }

    private String resolveAdvertiseHost(CommandSender sender, NetworkConfig config) {
        if (config.advertiseHostOverride != null && !config.advertiseHostOverride.isBlank()) {
            return config.advertiseHostOverride;
        }
        String resolved = network.getAdvertiseHost();
        WormholesAudience.sendMessage(sender, Component.text("Using " + resolved + " in this portal code. Set advertise-host-override in network.toml only if that address is wrong.", NamedTextColor.GRAY));
        return resolved;
    }

    private static List<String> alternateHosts(String identityHost) {
        List<String> alternates = new ArrayList<>(2);
        String publicIp = AddressResolver.detectPublicAddress();
        if (publicIp != null && !publicIp.equals(identityHost)) {
            alternates.add(publicIp);
        }
        String lan = AddressResolver.detectLanAddress();
        if (lan != null && !lan.equals(identityHost) && !alternates.contains(lan)) {
            alternates.add(lan);
        }
        return alternates;
    }

    private static String joinFallbacks(String primaryHost, List<String> fallbacks) {
        List<String> filtered = new ArrayList<>(fallbacks.size());
        for (String host : fallbacks) {
            if (host != null && !host.isBlank() && !host.equals(primaryHost) && !filtered.contains(host)) {
                filtered.add(host);
            }
        }
        return String.join(",", filtered);
    }

    private void persistConfig(NetworkConfig config) {
        File file = new File(new File(Wormholes.instance.getDataFolder(), "config"), "network.toml");
        FoliaScheduler.runAsync(Wormholes.instance, () -> {
            try {
                TomlCodec.writeCanonical(file, config);
            } catch (Throwable e) {
                Wormholes.w("net: failed to persist network.toml: " + e.getMessage());
            }
        });
    }

}
