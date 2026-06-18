package art.arcane.wormholes.network;

import art.arcane.wormholes.config.toml.NetworkConfig;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;

public final class PeerRouteStore {
    private static final String ROUTES_FILE = "peers.properties";

    private final Path file;
    private final Map<String, NetworkConfig.PeerEntry> routes = new ConcurrentHashMap<>();

    private PeerRouteStore(Path file) {
        this.file = file;
    }

    public static PeerRouteStore loadOrCreate(Path dataDirectory) throws IOException {
        Path routeDirectory = dataDirectory.resolve("routes");
        Files.createDirectories(routeDirectory);
        PeerRouteStore store = new PeerRouteStore(routeDirectory.resolve(ROUTES_FILE));
        store.load();
        return store;
    }

    public NetworkConfig.PeerEntry get(String name) {
        NetworkConfig.PeerEntry route = routes.get(name);
        return route == null ? null : copy(route);
    }

    public List<NetworkConfig.PeerEntry> all() {
        List<NetworkConfig.PeerEntry> entries = new ArrayList<>(routes.size());
        for (NetworkConfig.PeerEntry route : routes.values()) {
            entries.add(copy(route));
        }
        return entries;
    }

    public void save(NetworkConfig.PeerEntry route) {
        if (route == null || route.name == null || route.name.isBlank()) {
            return;
        }
        NetworkConfig.PeerEntry existing = routes.get(route.name);
        if (existing != null && sameRoute(existing, route)) {
            return;
        }
        routes.put(route.name, copy(route));
        persist();
    }

    private void load() throws IOException {
        routes.clear();
        if (!Files.isRegularFile(file)) {
            persist();
            return;
        }
        Properties properties = new Properties();
        try (InputStream input = Files.newInputStream(file)) {
            properties.load(input);
        }
        for (String key : properties.stringPropertyNames()) {
            NetworkConfig.PeerEntry route = decode(properties.getProperty(key));
            if (route != null && route.name != null && !route.name.isBlank()) {
                routes.put(route.name, route);
            }
        }
    }

    private synchronized void persist() {
        Properties properties = new Properties();
        for (NetworkConfig.PeerEntry route : routes.values()) {
            properties.setProperty(encodeName(route.name), encode(route));
        }
        try {
            Files.createDirectories(file.getParent());
            try (OutputStream output = Files.newOutputStream(file)) {
                properties.store(output, "Wormholes peer routes learned from portal links");
            }
        } catch (IOException e) {
            throw new IllegalStateException("Could not persist Wormholes peer route store", e);
        }
    }

    private static boolean sameRoute(NetworkConfig.PeerEntry a, NetworkConfig.PeerEntry b) {
        return Objects.equals(a.name, b.name)
            && Objects.equals(a.host, b.host)
            && Objects.equals(a.fallbackHosts, b.fallbackHosts)
            && a.port == b.port
            && Objects.equals(a.publicHost, b.publicHost)
            && a.publicPort == b.publicPort
            && a.useProxy == b.useProxy;
    }

    private static NetworkConfig.PeerEntry copy(NetworkConfig.PeerEntry source) {
        NetworkConfig.PeerEntry copy = new NetworkConfig.PeerEntry();
        copy.name = source.name;
        copy.host = source.host;
        copy.fallbackHosts = source.fallbackHosts;
        copy.port = source.port;
        copy.publicHost = source.publicHost;
        copy.publicPort = source.publicPort;
        copy.useProxy = source.useProxy;
        return copy;
    }

    private static String encodeName(String name) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(name.getBytes(StandardCharsets.UTF_8));
    }

    private static String encode(NetworkConfig.PeerEntry route) {
        try {
            ByteArrayOutputStream buffer = new ByteArrayOutputStream(128);
            DataOutputStream out = new DataOutputStream(buffer);
            out.writeUTF(blank(route.name));
            out.writeUTF(blank(route.host));
            out.writeUTF(blank(route.fallbackHosts));
            out.writeShort(route.port);
            out.writeUTF(blank(route.publicHost));
            out.writeShort(route.publicPort);
            out.writeBoolean(route.useProxy);
            out.flush();
            return Base64.getUrlEncoder().withoutPadding().encodeToString(buffer.toByteArray());
        } catch (IOException e) {
            throw new IllegalStateException("Could not encode Wormholes peer route", e);
        }
    }

    private static NetworkConfig.PeerEntry decode(String encoded) {
        if (encoded == null || encoded.isBlank()) {
            return null;
        }
        try {
            byte[] data = Base64.getUrlDecoder().decode(encoded);
            DataInputStream in = new DataInputStream(new ByteArrayInputStream(data));
            NetworkConfig.PeerEntry route = new NetworkConfig.PeerEntry();
            route.name = in.readUTF();
            route.host = in.readUTF();
            route.fallbackHosts = in.readUTF();
            route.port = in.readUnsignedShort();
            route.publicHost = in.readUTF();
            route.publicPort = in.readUnsignedShort();
            if (in.available() > 0) {
                route.useProxy = in.readBoolean();
            }
            return route;
        } catch (IllegalArgumentException | IOException e) {
            return null;
        }
    }

    private static String blank(String value) {
        return value == null ? "" : value;
    }
}
