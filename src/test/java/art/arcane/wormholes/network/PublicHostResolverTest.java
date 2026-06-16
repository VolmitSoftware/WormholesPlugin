package art.arcane.wormholes.network;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Timeout(15)
class PublicHostResolverTest {
    private static final Logger LOGGER = Logger.getLogger("PublicHostResolverTest");

    private HttpServer good;
    private HttpServer bad;

    @BeforeEach
    void setUp() throws Exception {
        good = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        good.createContext("/ip", exchange -> {
            byte[] body = "203.0.113.42\n".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        good.start();

        bad = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        bad.createContext("/fail", exchange -> {
            exchange.sendResponseHeaders(500, -1);
            exchange.close();
        });
        bad.start();
    }

    @AfterEach
    void tearDown() {
        if (good != null) {
            good.stop(0);
        }
        if (bad != null) {
            bad.stop(0);
        }
    }

    private String url(HttpServer server, String path) {
        return "http://" + server.getAddress().getHostString() + ":" + server.getAddress().getPort() + path;
    }

    @Test
    void resolverReturnsFirstValidResponse() {
        PublicHostResolver resolver = new PublicHostResolver(LOGGER, List.of(url(bad, "/fail"), url(good, "/ip")), java.net.http.HttpClient.newHttpClient());
        String result = resolver.resolveBlocking();
        assertEquals("203.0.113.42", result);
    }

    @Test
    void resolverReturnsNullWhenAllEndpointsFail() {
        PublicHostResolver resolver = new PublicHostResolver(LOGGER, List.of(url(bad, "/fail"), url(bad, "/fail")), java.net.http.HttpClient.newHttpClient());
        String result = resolver.resolveBlocking();
        assertNull(result);
    }

    @Test
    void validHostLiteralAcceptsIpv4AndIpv6AndRejectsJunk() {
        assertTrue(PublicHostResolver.isValidHostLiteral("203.0.113.42"));
        assertTrue(PublicHostResolver.isValidHostLiteral("2001:db8::1"));
        assertFalse(PublicHostResolver.isValidHostLiteral("127.0.0.1"));
        assertFalse(PublicHostResolver.isValidHostLiteral("0.0.0.0"));
        assertFalse(PublicHostResolver.isValidHostLiteral(""));
        assertFalse(PublicHostResolver.isValidHostLiteral("<html>error</html>"));
        assertFalse(PublicHostResolver.isValidHostLiteral(null));
    }

    @Test
    void refreshAsyncInvokesCallbackOnSuccess() throws Exception {
        PublicHostResolver resolver = new PublicHostResolver(LOGGER, List.of(url(good, "/ip")), java.net.http.HttpClient.newHttpClient());
        AtomicReference<String> got = new AtomicReference<>();
        CountDownLatch latch = new CountDownLatch(1);
        resolver.refreshAsync(value -> {
            got.set(value);
            latch.countDown();
        });
        assertTrue(latch.await(5L, TimeUnit.SECONDS), "callback should fire within 5s");
        assertEquals("203.0.113.42", got.get());
        assertEquals("203.0.113.42", resolver.cached());
        resolver.shutdown();
    }
}
