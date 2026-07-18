package dev.quicklogin.velocity;

import org.slf4j.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Properties;

/**
 * Minimal properties-backed configuration for the Velocity plugin. Written to
 * {@code <datadir>/config.properties} on first run.
 */
public final class QuickLoginConfig {

    private final boolean premiumEnabled;
    private final boolean allowCracked;
    private final String bedrockPrefix;
    private final int requestTimeoutMs;
    private final int cacheSeconds;
    private final boolean debug;

    private QuickLoginConfig(Properties p) {
        this.premiumEnabled = bool(p, "premium-enabled", true);
        this.allowCracked = bool(p, "allow-cracked", true);
        this.bedrockPrefix = p.getProperty("bedrock-prefix", ".");
        this.requestTimeoutMs = clamp(intProp(p, "mojang-request-timeout-ms", 5000), 1000, 30000);
        this.cacheSeconds = Math.max(0, intProp(p, "mojang-cache-seconds", 300));
        this.debug = bool(p, "debug", false);
    }

    public static QuickLoginConfig load(Path dataDirectory, Logger logger) {
        Properties props = new Properties();
        try {
            Files.createDirectories(dataDirectory);
            Path file = dataDirectory.resolve("config.properties");
            if (Files.exists(file)) {
                try (InputStream in = Files.newInputStream(file)) {
                    props.load(in);
                }
            } else {
                writeDefaults(file);
                logger.info("Wrote default config to {}", file);
            }
        } catch (IOException e) {
            logger.warn("Could not read/write config, using defaults: {}", e.toString());
        }
        return new QuickLoginConfig(props);
    }

    private static void writeDefaults(Path file) throws IOException {
        Properties defaults = new Properties();
        defaults.setProperty("premium-enabled", "true");
        defaults.setProperty("allow-cracked", "true");
        defaults.setProperty("bedrock-prefix", ".");
        defaults.setProperty("mojang-request-timeout-ms", "5000");
        defaults.setProperty("mojang-cache-seconds", "300");
        defaults.setProperty("debug", "false");
        try (OutputStream out = Files.newOutputStream(file)) {
            defaults.store(out, "QuickLogin (Velocity) configuration\n"
                    + "premium-enabled: verify paid Minecraft accounts (force online-mode login).\n"
                    + "allow-cracked: allow non-premium names to join as offline players.\n"
                    + "bedrock-prefix: names starting with this are treated as Bedrock and skipped.\n"
                    + "mojang-*: direct Mojang API tuning. debug: verbose logging.");
        }
    }

    private static boolean bool(Properties p, String key, boolean def) {
        String v = p.getProperty(key);
        return v == null ? def : Boolean.parseBoolean(v.trim());
    }

    private static int intProp(Properties p, String key, int def) {
        try {
            String v = p.getProperty(key);
            return v == null ? def : Integer.parseInt(v.trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }

    private static int clamp(int v, int min, int max) {
        return Math.max(min, Math.min(max, v));
    }

    public boolean premiumEnabled() { return premiumEnabled; }
    public boolean allowCracked() { return allowCracked; }
    public String bedrockPrefix() { return bedrockPrefix; }
    public int requestTimeoutMs() { return requestTimeoutMs; }
    public int cacheSeconds() { return cacheSeconds; }
    public boolean debug() { return debug; }

    public boolean looksBedrock(String username) {
        return !bedrockPrefix.isEmpty() && username.startsWith(bedrockPrefix);
    }

    /** Standard Java name check: 3-16 of [A-Za-z0-9_]. */
    public static boolean isValidJavaName(String name) {
        if (name == null || name.length() < 3 || name.length() > 16) {
            return false;
        }
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            boolean ok = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
                    || (c >= '0' && c <= '9') || c == '_';
            if (!ok) return false;
        }
        return true;
    }

    public String lower(String s) {
        return s.toLowerCase(Locale.ROOT);
    }
}
