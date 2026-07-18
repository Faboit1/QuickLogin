package dev.quicklogin.velocity;

import org.slf4j.Logger;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Direct client for the public Mojang profile API, used to decide whether a
 * connecting name is a paid account (so the proxy can force online-mode login
 * for it). Talks to Mojang directly and never through a configured proxy.
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
                .version(HttpClient.Version.HTTP_2)
                .proxy(HttpClient.Builder.NO_PROXY)
                .build();
    }

    /** Tri-state so callers can distinguish "definitely not premium" from "unknown". */
    public enum Result {
        PREMIUM,
        CRACKED,
        UNKNOWN
    }

    private record Cached(Result result, long expiresAt) {
    }

    /** Blocking lookup of whether {@code name} is a paid Minecraft account. */
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
            } else if (code == 429) {
                logger.warn("Mojang API rate-limited (429) while looking up '{}'.", name);
                return Result.UNKNOWN; // do not cache transient states
            } else {
                if (debug) logger.warn("Unexpected Mojang status {} for '{}'.", code, name);
                return Result.UNKNOWN;
            }
        } catch (Exception e) {
            if (debug) logger.warn("Mojang lookup failed for '{}': {}", name, e.toString());
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
