package art.arcane.wormholes.network;

import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.logging.Logger;

public final class PublicHostResolver {
    public static final List<String> DEFAULT_ENDPOINTS = List.of(
        "https://api.ipify.org",
        "https://ifconfig.me/ip",
        "https://icanhazip.com"
    );

    private static final Duration ATTEMPT_TIMEOUT = Duration.ofMillis(1_500L);
    private static final Duration TOTAL_BUDGET = Duration.ofMillis(5_000L);
    private static final long CACHE_TTL_NANOS = Duration.ofMinutes(60L).toNanos();

    private final Logger logger;
    private final List<String> endpoints;
    private final HttpClient httpClient;
    private final AtomicReference<String> cached = new AtomicReference<>();
    private final AtomicReference<Long> cachedAtNanos = new AtomicReference<>(0L);
    private final Object lock = new Object();
    private volatile ExecutorService executor;
    private volatile boolean inFlight;

    public PublicHostResolver(Logger logger) {
        this(logger, DEFAULT_ENDPOINTS, defaultClient());
    }

    public PublicHostResolver(Logger logger, List<String> endpoints, HttpClient httpClient) {
        this.logger = Objects.requireNonNull(logger);
        this.endpoints = List.copyOf(endpoints);
        this.httpClient = Objects.requireNonNull(httpClient);
    }

    private static HttpClient defaultClient() {
        return HttpClient.newBuilder().connectTimeout(ATTEMPT_TIMEOUT).build();
    }

    public String cached() {
        if (System.nanoTime() - cachedAtNanos.get() > CACHE_TTL_NANOS) {
            return null;
        }
        return cached.get();
    }

    public void refreshAsync(Consumer<String> onComplete) {
        synchronized (lock) {
            if (inFlight) {
                return;
            }
            inFlight = true;
            ExecutorService active = executor;
            if (active == null) {
                active = Executors.newSingleThreadExecutor(runnable -> {
                    Thread thread = new Thread(runnable, "Wormholes-PublicHost-Resolver");
                    thread.setDaemon(true);
                    return thread;
                });
                executor = active;
            }
            active.submit(() -> runResolution(onComplete));
        }
    }

    public String resolveBlocking() {
        return resolveOnce();
    }

    public void shutdown() {
        ExecutorService active = executor;
        executor = null;
        if (active != null) {
            active.shutdownNow();
        }
    }

    private void runResolution(Consumer<String> onComplete) {
        try {
            String resolved = resolveOnce();
            if (resolved != null) {
                cached.set(resolved);
                cachedAtNanos.set(System.nanoTime());
                logger.info("net: public host resolved to " + resolved);
            } else {
                logger.fine("net: public host resolution returned no address");
            }
            if (onComplete != null) {
                onComplete.accept(resolved);
            }
        } finally {
            synchronized (lock) {
                inFlight = false;
            }
        }
    }

    private String resolveOnce() {
        long deadlineNanos = System.nanoTime() + TOTAL_BUDGET.toNanos();
        for (String endpoint : endpoints) {
            if (System.nanoTime() >= deadlineNanos) {
                return null;
            }
            String result = attempt(endpoint, deadlineNanos);
            if (result != null) {
                return result;
            }
        }
        return null;
    }

    private String attempt(String endpoint, long deadlineNanos) {
        long remainingNanos = deadlineNanos - System.nanoTime();
        if (remainingNanos <= 0L) {
            return null;
        }
        Duration timeout = Duration.ofNanos(Math.min(ATTEMPT_TIMEOUT.toNanos(), remainingNanos));
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(endpoint))
                .timeout(timeout)
                .header("User-Agent", "Wormholes-PublicHostResolver/1")
                .GET()
                .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                return null;
            }
            String body = response.body();
            if (body == null) {
                return null;
            }
            String trimmed = body.trim();
            if (!isValidHostLiteral(trimmed)) {
                return null;
            }
            return trimmed;
        } catch (Exception e) {
            return null;
        }
    }

    public static boolean isValidHostLiteral(String candidate) {
        if (candidate == null || candidate.isBlank() || candidate.length() > 64) {
            return false;
        }
        for (int i = 0; i < candidate.length(); i++) {
            char ch = candidate.charAt(i);
            boolean ok = (ch >= '0' && ch <= '9')
                || (ch >= 'a' && ch <= 'f')
                || (ch >= 'A' && ch <= 'F')
                || ch == '.' || ch == ':';
            if (!ok) {
                return false;
            }
        }
        try {
            InetAddress address = InetAddress.getByName(candidate);
            if (address.isLoopbackAddress() || address.isAnyLocalAddress() || address.isLinkLocalAddress()) {
                return false;
            }
            return true;
        } catch (UnknownHostException e) {
            return false;
        }
    }
}
