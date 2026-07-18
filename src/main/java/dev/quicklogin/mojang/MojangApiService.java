package dev.quicklogin.mojang;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

/**
 * Direct client for the public Mojang HTTP API.
 *
 * <p>The Minecraft server is assumed to sit behind a proxy (BungeeCord /
 * Velocity), so QuickLogin talks to Mojang <b>directly</b> and never forwards
 * the client's IP to the {@code hasJoined} endpoint — the address the backend
 * sees is the proxy's, and passing it would make Mojang reject valid sessions.
 *
 * <p>The {@link HttpClient} is created with no proxy selector, so requests are
 * not tunnelled through any configured system/HTTP proxy either.
 */
public final class MojangApiService {

    private static final String PROFILE_URL = "https://api.mojang.com/users/profiles/minecraft/";
    private static final String HAS_JOINED_URL = "https://sessionserver.mojang.com/session/minecraft/hasJoined";

    private final Logger logger;
    private final boolean debug;
    private final Duration timeout;
    private final long cacheMillis;
    private final HttpClient http;

    private final ConcurrentHashMap<String, CachedLookup> cache = new ConcurrentHashMap<>();

    public MojangApiService(Logger logger, boolean debug, int timeoutMs, int cacheSeconds) {
        this.logger = logger;
        this.debug = debug;
        this.timeout = Duration.ofMillis(timeoutMs);
        this.cacheMillis = cacheSeconds * 1000L;
        this.http = HttpClient.newBuilder()
                .connectTimeout(this.timeout)
                .followRedirects(HttpClient.Redirect.NORMAL)
                .version(HttpClient.Version.HTTP_2)
                // Talk to Mojang directly: never tunnel through a JVM-configured proxy.
                .proxy(HttpClient.Builder.NO_PROXY)
                .build();
    }

    /** Result of a name -> premium lookup. */
    public record PremiumResult(boolean premium, UUID uuid) {
        static final PremiumResult NOT_PREMIUM = new PremiumResult(false, null);
    }

    private record CachedLookup(PremiumResult result, long expiresAt) {
    }

    /**
     * Look up whether {@code name} is a paid Minecraft account and its UUID.
     * Blocking; call from a worker thread. Returns {@code null} on a transient
     * error (so the caller can decide to fail open/closed).
     */
    public PremiumResult lookupPremium(String name) {
        String key = name.toLowerCase(java.util.Locale.ROOT);
        if (cacheMillis > 0) {
            CachedLookup cached = cache.get(key);
            if (cached != null && cached.expiresAt > System.currentTimeMillis()) {
                return cached.result;
            }
        }

        try {
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(PROFILE_URL + URLEncoder.encode(name, StandardCharsets.UTF_8)))
                    .timeout(timeout)
                    .header("Accept", "application/json")
                    .GET()
                    .build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            int code = resp.statusCode();

            PremiumResult result;
            if (code == 200 && resp.body() != null && !resp.body().isBlank()) {
                String id = extractJsonString(resp.body(), "id");
                result = new PremiumResult(true, id == null ? null : parseUndashedUuid(id));
            } else if (code == 204 || code == 404) {
                result = PremiumResult.NOT_PREMIUM;
            } else if (code == 429) {
                logger.warning("Mojang API rate-limited (429) while looking up '" + name + "'.");
                return null; // transient
            } else {
                if (debug) logger.warning("Unexpected Mojang profile status " + code + " for '" + name + "'.");
                return null; // transient
            }

            if (cacheMillis > 0) {
                cache.put(key, new CachedLookup(result, System.currentTimeMillis() + cacheMillis));
            }
            return result;
        } catch (Exception e) {
            if (debug) logger.warning("Mojang profile lookup failed for '" + name + "': " + e);
            return null;
        }
    }

    /**
     * Verify a client's session via {@code hasJoined}. Blocking; call from a
     * worker thread.
     *
     * @return the verified online UUID, or {@code null} if the session is
     * invalid / expired / could not be verified.
     */
    public UUID hasJoined(String username, String serverHash) {
        try {
            String url = HAS_JOINED_URL
                    + "?username=" + URLEncoder.encode(username, StandardCharsets.UTF_8)
                    + "&serverId=" + URLEncoder.encode(serverHash, StandardCharsets.UTF_8);
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(timeout)
                    .header("Accept", "application/json")
                    .GET()
                    .build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            int code = resp.statusCode();
            if (code == 200 && resp.body() != null && !resp.body().isBlank()) {
                String id = extractJsonString(resp.body(), "id");
                return id == null ? null : parseUndashedUuid(id);
            }
            // 204 / 403 / empty body => session not valid.
            if (debug) logger.info("hasJoined for '" + username + "' returned status " + code);
            return null;
        } catch (Exception e) {
            if (debug) logger.warning("hasJoined verification failed for '" + username + "': " + e);
            return null;
        }
    }

    public void invalidate(String name) {
        cache.remove(name.toLowerCase(java.util.Locale.ROOT));
    }

    public void clearCache() {
        cache.clear();
    }

    /** Convert Mojang's un-dashed 32-char UUID into a {@link UUID}. */
    public static UUID parseUndashedUuid(String undashed) {
        if (undashed == null || undashed.length() != 32) {
            return null;
        }
        String dashed = undashed.substring(0, 8) + "-"
                + undashed.substring(8, 12) + "-"
                + undashed.substring(12, 16) + "-"
                + undashed.substring(16, 20) + "-"
                + undashed.substring(20, 32);
        try {
            return UUID.fromString(dashed);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /**
     * Minimal extractor for a top-level string field in a flat Mojang JSON
     * object. Avoids pulling in a JSON library for two well-known fields whose
     * values contain no escaping.
     */
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
