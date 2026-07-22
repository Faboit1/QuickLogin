package dev.quicklogin.mojang;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

/**
 * Minimal direct client for the Mojang APIs: answers "is this name a paid
 * account?" and performs the {@code hasJoined} session check. Talks to Mojang
 * directly, never through a proxy.
 */
public final class MojangApiService {

    private static final String PROFILE_URL = "https://api.mojang.com/users/profiles/minecraft/";
    private static final String HAS_JOINED_URL =
            "https://sessionserver.mojang.com/session/minecraft/hasJoined";

    private final Logger logger;
    private volatile boolean debug;
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
                logger.warning("Mojang API returned HTTP " + code + " for '" + name
                        + "' — premium check skipped for this player.");
                return Result.UNKNOWN;
            }
        } catch (Exception e) {
            logger.warning("Mojang API unreachable for '" + name + "': " + e.getMessage());
            return Result.UNKNOWN;
        }

        if (cacheMillis > 0) {
            cache.put(key, new Cached(result, System.currentTimeMillis() + cacheMillis));
        }
        return result;
    }

    /**
     * Verify a client's session via {@code hasJoined}. Blocking; call off the
     * main thread. The server IP is never sent (so it works behind a proxy).
     *
     * @return the Mojang-verified UUID, or empty if the session is invalid/expired
     */
    public Optional<UUID> hasJoined(String username, String serverHash) {
        try {
            String url = HAS_JOINED_URL
                    + "?username=" + URLEncoder.encode(username, StandardCharsets.UTF_8)
                    + "&serverId=" + URLEncoder.encode(serverHash, StandardCharsets.UTF_8);
            HttpResponse<String> resp = http.send(
                    HttpRequest.newBuilder().uri(URI.create(url)).timeout(timeout)
                            .header("Accept", "application/json").GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() == 200 && resp.body() != null && !resp.body().isBlank()) {
                String id = extractJsonString(resp.body(), "id");
                UUID uuid = id == null ? null : parseUndashedUuid(id);
                return Optional.ofNullable(uuid);
            }
            if (debug) logger.info("hasJoined for '" + username + "' returned " + resp.statusCode());
            return Optional.empty();
        } catch (Exception e) {
            if (debug) logger.warning("hasJoined failed for '" + username + "': " + e);
            return Optional.empty();
        }
    }

    public void clearCache() {
        cache.clear();
    }

    /** Refresh the debug flag on config reload. */
    public void setDebug(boolean debug) {
        this.debug = debug;
    }

    /** Convert Mojang's un-dashed 32-char UUID into a {@link UUID}. */
    public static UUID parseUndashedUuid(String undashed) {
        if (undashed == null || undashed.length() != 32) {
            return null;
        }
        String dashed = undashed.substring(0, 8) + "-" + undashed.substring(8, 12) + "-"
                + undashed.substring(12, 16) + "-" + undashed.substring(16, 20) + "-"
                + undashed.substring(20, 32);
        try {
            return UUID.fromString(dashed);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** Minimal extractor for a top-level string field in flat Mojang JSON. */
    static String extractJsonString(String json, String field) {
        String needle = "\"" + field + "\"";
        int keyIdx = json.indexOf(needle);
        if (keyIdx < 0) return null;
        int colon = json.indexOf(':', keyIdx + needle.length());
        if (colon < 0) return null;
        int firstQuote = json.indexOf('"', colon + 1);
        if (firstQuote < 0) return null;
        int endQuote = json.indexOf('"', firstQuote + 1);
        if (endQuote < 0) return null;
        return json.substring(firstQuote + 1, endQuote);
    }
}
