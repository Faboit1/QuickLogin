package dev.quicklogin.mojang;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

/**
 * Minimal direct client for the Mojang profile API: answers "is this name a
 * paid Minecraft account?" Talks to Mojang directly, never through a proxy.
 */
public final class MojangApiService {

    private static final String PROFILE_URL = "https://api.mojang.com/users/profiles/minecraft/";

    private final Logger logger;
    private final boolean debug;
    private final Duration timeout;
    private final long cacheMillis;
    private final HttpClient http;

    private final ConcurrentHashMap<String, Cached> cache = new ConcurrentHashMap<>();

    public MojangApiService(Logger logger, boolean debug, int timeoutMs, int cacheSeconds) {
        this.logger = logger;
        this.debug = debug;
        this.timeout = Duration.ofMillis(timeoutMs);
        this.cacheMillis = cacheSeconds * 1000L;
        this.http = HttpClient.newBuilder()
                .connectTimeout(this.timeout)
                .followRedirects(HttpClient.Redirect.NORMAL)
                .proxy(HttpClient.Builder.NO_PROXY)
                .build();
    }

    /** PREMIUM = paid account; CRACKED = not; UNKNOWN = transient error (don't cache). */
    public enum Result { PREMIUM, CRACKED, UNKNOWN }

    private record Cached(Result result, long expiresAt) {
    }

    /** Blocking; call off the main thread. */
    public Result lookup(String name) {
        String key = name.toLowerCase(Locale.ROOT);
        if (cacheMillis > 0) {
            Cached cached = cache.get(key);
            if (cached != null && cached.expiresAt > System.currentTimeMillis()) {
                return cached.result;
            }
        }

        Result result;
        try {
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(PROFILE_URL + URLEncoder.encode(name, StandardCharsets.UTF_8)))
                    .timeout(timeout)
                    .header("Accept", "application/json")
                    .GET()
                    .build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            int code = resp.statusCode();
            if (code == 200 && resp.body() != null && !resp.body().isBlank()) {
                result = Result.PREMIUM;
            } else if (code == 204 || code == 404) {
                result = Result.CRACKED;
            } else {
                if (debug) logger.warning("Mojang lookup for '" + name + "' returned " + code);
                return Result.UNKNOWN;
            }
        } catch (Exception e) {
            if (debug) logger.warning("Mojang lookup failed for '" + name + "': " + e);
            return Result.UNKNOWN;
        }

        if (cacheMillis > 0) {
            cache.put(key, new Cached(result, System.currentTimeMillis() + cacheMillis));
        }
        return result;
    }

    public void clearCache() {
        cache.clear();
    }
}
