package art.arcane.wormholes.network;

import art.arcane.volmlib.util.scheduling.FoliaScheduler;
import art.arcane.volmlib.util.localization.MessageArgument;
import art.arcane.wormholes.Wormholes;
import art.arcane.wormholes.config.toml.NetworkConfig;
import art.arcane.wormholes.localization.WormholesLocalization;
import art.arcane.wormholes.localization.WormholesMessages;
import art.arcane.wormholes.portal.ILocalPortal;
import art.arcane.wormholes.service.WormholesAudience;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;

public final class ImportExportService {
    private static final int CHAT_SAFE_CODE_LENGTH = 250;

    private final NetworkManager network;

    public ImportExportService(NetworkManager network) {
        this.network = network;
    }

    public void exportToChat(Player player, ILocalPortal portal) {
        WormholesAudience.sendMessage(player, Wormholes.text().component(WormholesMessages.NETWORK_BUILDING_CODE));
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

        Component message = Wormholes.text().component(
                WormholesMessages.NETWORK_COPY_CODE,
                WormholesLocalization.args(MessageArgument.untrusted("portal", portal.getName())))
            .clickEvent(ClickEvent.copyToClipboard(encoded))
            .hoverEvent(HoverEvent.showText(Wormholes.text().component(WormholesMessages.NETWORK_COPY_CODE_HOVER)));
        WormholesAudience.sendMessage(player, message);
        WormholesAudience.sendMessage(player, Wormholes.text().component(
                WormholesMessages.NETWORK_CODE_FINGERPRINT,
                WormholesLocalization.args(MessageArgument.untrusted("fingerprint", network.getPublicKeyFingerprint()))));
        if (encoded.length() > CHAT_SAFE_CODE_LENGTH) {
            WormholesAudience.sendMessage(player, Wormholes.text().component(WormholesMessages.NETWORK_CODE_TOO_LONG));
        }
    }

    private void importNow(CommandSender sender, ILocalPortal portal, String raw) {
        PortalCode code = PortalCode.decode(raw);
        if (code == null) {
            WormholesAudience.sendMessage(sender, Wormholes.text().component(
                    WormholesMessages.NETWORK_CODE_INVALID,
                    WormholesLocalization.args(MessageArgument.untrusted("prefix", PortalCode.PREFIX))));
            return;
        }

        NetworkConfig config = Wormholes.settings.getNetwork();

        if (code.serverName().equals(network.getLocalName())) {
            boolean ownPortal = Wormholes.portalManager != null && Wormholes.portalManager.getLocalPortal(code.portalId()) != null;
            if (ownPortal) {
                WormholesAudience.sendMessage(sender, Wormholes.text().component(WormholesMessages.NETWORK_CODE_SAME_SERVER));
            } else {
                WormholesAudience.sendMessage(sender, Wormholes.text().component(
                        WormholesMessages.NETWORK_CODE_SAME_IDENTITY,
                        WormholesLocalization.args(MessageArgument.untrusted("server", code.serverName()))));
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
            WormholesAudience.sendMessage(sender, Wormholes.text().component(
                    WormholesMessages.NETWORK_LINKED,
                    WormholesLocalization.args(
                            MessageArgument.untrusted("portal", portal.getName()),
                            MessageArgument.untrusted("destination", code.portalName()),
                            MessageArgument.untrusted("server", code.serverName()))));
        } else {
            WormholesAudience.sendMessage(sender, Wormholes.text().component(
                    WormholesMessages.NETWORK_ROUTE_SAVED,
                    WormholesLocalization.args(
                            MessageArgument.untrusted("server", code.serverName()),
                            MessageArgument.untrusted("fingerprint", Handshake.fingerprint(Handshake.decodePublicKeyText(code.publicKey()))),
                            MessageArgument.untrusted("portal", code.portalName()))));
        }
        WormholesAudience.sendMessage(sender, Wormholes.text().component(WormholesMessages.NETWORK_CHECK_STATUS));
    }

    private String resolveAdvertiseHost(CommandSender sender, NetworkConfig config) {
        if (config.advertiseHostOverride != null && !config.advertiseHostOverride.isBlank()) {
            return config.advertiseHostOverride;
        }
        String resolved = network.getAdvertiseHost();
        WormholesAudience.sendMessage(sender, Wormholes.text().component(
                WormholesMessages.NETWORK_USING_ADDRESS,
                WormholesLocalization.args(MessageArgument.untrusted("address", resolved))));
        return resolved;
    }

    private List<String> alternateHosts(String identityHost) {
        List<String> alternates = new ArrayList<>(2);
        String publicIp = network.getResolvedPublicHost();
        if (publicIp != null && !publicIp.equals(identityHost)) {
            alternates.add(publicIp);
        }
        String lan = LanAddressResolver.detectLanAddress();
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
        FoliaScheduler.runAsync(Wormholes.instance, () -> {
            try {
                Wormholes.settings.save(Wormholes.instance.getDataFolder().toPath());
            } catch (Throwable e) {
                Wormholes.w("net: failed to persist wormholes.toml: " + e.getMessage());
            }
        });
    }

}
