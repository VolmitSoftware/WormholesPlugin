package art.arcane.wormholes.network;

import art.arcane.wormholes.config.toml.NetworkConfig;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.List;

final class PeerEndpointResolver {
    private PeerEndpointResolver() {
    }

    static String playerTransferHost(NetworkConfig.PeerEntry peer, InetSocketAddress clientAddress,
                                     String verifiedPrivateHost) {
        if (isLocalClient(clientAddress) && isLocalLiteral(verifiedPrivateHost)) {
            return verifiedPrivateHost.trim();
        }
        List<String> candidates = gameHosts(peer);
        return candidates.isEmpty() ? null : candidates.getFirst();
    }

    static String privateGameHost(NetworkConfig.PeerEntry peer, String statusGameHost,
                                  InetSocketAddress rawPeerAddress, boolean loopbackTransport) {
        if (isLocalLiteral(statusGameHost)) {
            return statusGameHost.trim();
        }
        if (loopbackTransport) {
            String loopback = firstLocalCandidate(peer, true);
            return loopback == null ? "127.0.0.1" : loopback;
        }
        if (isLocalClient(rawPeerAddress)) {
            return rawPeerAddress.getAddress().getHostAddress();
        }
        return null;
    }

    static List<String> gameHosts(NetworkConfig.PeerEntry peer) {
        List<String> candidates = new ArrayList<>(4);
        add(candidates, peer.publicHost);
        add(candidates, peer.host);
        addFallbacks(candidates, peer.fallbackHosts);
        return List.copyOf(candidates);
    }

    static int gamePort(NetworkConfig.PeerEntry peer) {
        return peer.publicPort > 0 ? peer.publicPort : 25565;
    }

    static boolean isLocalClient(InetSocketAddress clientAddress) {
        InetAddress address = clientAddress == null ? null : clientAddress.getAddress();
        return isLocalAddress(address);
    }

    private static String firstLocalCandidate(NetworkConfig.PeerEntry peer, boolean requireLoopback) {
        List<String> localCandidates = new ArrayList<>(4);
        add(localCandidates, peer.host);
        addFallbacks(localCandidates, peer.fallbackHosts);
        add(localCandidates, peer.publicHost);
        for (String candidate : localCandidates) {
            InetAddress address = localLiteral(candidate);
            if (address != null && (!requireLoopback || address.isLoopbackAddress())) {
                return candidate;
            }
        }
        return null;
    }

    private static boolean isLocalLiteral(String host) {
        return localLiteral(host) != null;
    }

    private static InetAddress localLiteral(String host) {
        if (host == null || host.isBlank()) {
            return null;
        }
        String candidate = host.trim();
        if (candidate.equalsIgnoreCase("localhost") || candidate.equalsIgnoreCase("localhost.")) {
            return InetAddress.getLoopbackAddress();
        }
        InetAddress address = parseLiteral(candidate);
        return isLocalAddress(address) ? address : null;
    }

    private static InetAddress parseLiteral(String host) {
        String candidate = host;
        if (candidate.startsWith("[") && candidate.endsWith("]") && candidate.length() > 2) {
            candidate = candidate.substring(1, candidate.length() - 1);
        }
        boolean ipv4 = candidate.indexOf('.') >= 0;
        boolean ipv6 = candidate.indexOf(':') >= 0;
        if (!ipv4 && !ipv6) {
            return null;
        }
        for (int index = 0; index < candidate.length(); index++) {
            char character = candidate.charAt(index);
            boolean valid = character >= '0' && character <= '9' || character == '.';
            if (ipv6) {
                valid = valid || character >= 'a' && character <= 'f'
                    || character >= 'A' && character <= 'F' || character == ':';
            }
            if (!valid) {
                return null;
            }
        }
        try {
            return InetAddress.getByName(candidate);
        } catch (UnknownHostException ignored) {
            return null;
        }
    }

    private static boolean isLocalAddress(InetAddress address) {
        if (address == null || address.isAnyLocalAddress()) {
            return false;
        }
        if (address.isLoopbackAddress() || address.isSiteLocalAddress() || address.isLinkLocalAddress()) {
            return true;
        }
        byte[] bytes = address.getAddress();
        return bytes.length == 16 && (bytes[0] & 0xFE) == 0xFC;
    }

    private static void addFallbacks(List<String> candidates, String fallbackHosts) {
        if (fallbackHosts == null || fallbackHosts.isBlank()) {
            return;
        }
        for (String fallback : fallbackHosts.split("\\s*,\\s*")) {
            add(candidates, fallback);
        }
    }

    private static void add(List<String> candidates, String host) {
        if (host == null || host.isBlank()) {
            return;
        }
        String candidate = host.trim();
        if (!candidates.contains(candidate)) {
            candidates.add(candidate);
        }
    }
}
