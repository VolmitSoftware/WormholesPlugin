package art.arcane.wormholes.network;

import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.regex.Pattern;

public final class AddressResolver {
    private static final String[] PUBLIC_IP_ENDPOINTS = {
        "https://checkip.amazonaws.com",
        "https://api.ipify.org",
        "https://ifconfig.me/ip"
    };
    private static final Pattern IPV4 = Pattern.compile("\\d{1,3}(\\.\\d{1,3}){3}");
    private static final Duration HTTP_TIMEOUT = Duration.ofSeconds(3);

    private static volatile String cachedPublic;
    private static volatile boolean publicResolved;

    private AddressResolver() {
    }

    public static String detectPublicAddress() {
        if (publicResolved) {
            return cachedPublic;
        }
        String result = null;
        HttpClient client = HttpClient.newBuilder().connectTimeout(HTTP_TIMEOUT).build();
        for (String endpoint : PUBLIC_IP_ENDPOINTS) {
            try {
                HttpRequest request = HttpRequest.newBuilder(URI.create(endpoint)).timeout(HTTP_TIMEOUT).GET().build();
                String body = client.send(request, HttpResponse.BodyHandlers.ofString()).body().trim();
                if (IPV4.matcher(body).matches()) {
                    result = body;
                    break;
                }
            } catch (Exception ignored) {
            }
        }
        cachedPublic = result;
        publicResolved = true;
        return result;
    }

    public static String detectLanAddress() {
        try (DatagramSocket socket = new DatagramSocket()) {
            socket.connect(InetAddress.getByName("8.8.8.8"), 53);
            InetAddress local = socket.getLocalAddress();
            if (local != null && !local.isAnyLocalAddress()) {
                return local.getHostAddress();
            }
        } catch (Exception ignored) {
        }
        try {
            return InetAddress.getLocalHost().getHostAddress();
        } catch (Exception e) {
            return "127.0.0.1";
        }
    }
}
