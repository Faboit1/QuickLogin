package dev.quicklogin.config;

import org.bukkit.configuration.file.FileConfiguration;

/**
 * Immutable snapshot of the plugin configuration.
 *
 * <p>Reading values through a snapshot (instead of hitting the
 * {@link FileConfiguration} on every packet) keeps the hot login path
 * allocation-free and thread-safe: the login handshake runs on Netty /
 * async threads, and touching Bukkit's config from there would otherwise be
 * unsafe.
 */
public final class QuickLoginConfig {

    // premium
    private final boolean premiumEnabled;
    private final boolean allowCracked;
    private final boolean kickOnInvalidSession;
    private final String invalidSessionMessage;

    // floodgate
    private final boolean floodgateEnabled;
    private final boolean floodgateAutoRegister;

    // auth
    private final boolean authAutoRegister;
    private final int generatedPasswordLength;

    // mojang
    private final int requestTimeoutMs;
    private final int cacheSeconds;
    private final int workerThreads;

    // database
    private final String databaseFile;

    private final boolean debug;

    private QuickLoginConfig(FileConfiguration c) {
        this.premiumEnabled = c.getBoolean("premium.enabled", true);
        this.allowCracked = c.getBoolean("premium.allow-cracked", true);
        this.kickOnInvalidSession = c.getBoolean("premium.kick-on-invalid-session", true);
        this.invalidSessionMessage = color(c.getString("premium.invalid-session-message", "Failed to verify username!"));

        this.floodgateEnabled = c.getBoolean("floodgate.enabled", true);
        this.floodgateAutoRegister = c.getBoolean("floodgate.auto-register", true);

        this.authAutoRegister = c.getBoolean("auth.auto-register", true);
        this.generatedPasswordLength = clamp(c.getInt("auth.generated-password-length", 32), 8, 128);

        this.requestTimeoutMs = clamp(c.getInt("mojang.request-timeout-ms", 5000), 1000, 30000);
        this.cacheSeconds = Math.max(0, c.getInt("mojang.cache-seconds", 300));
        this.workerThreads = clamp(c.getInt("mojang.worker-threads", 3), 1, 16);

        this.databaseFile = c.getString("database.file", "quicklogin.db");

        this.debug = c.getBoolean("debug", false);
    }

    public static QuickLoginConfig from(FileConfiguration c) {
        return new QuickLoginConfig(c);
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static String color(String s) {
        return s == null ? "" : s.replace('&', '§');
    }

    public boolean premiumEnabled() { return premiumEnabled; }
    public boolean allowCracked() { return allowCracked; }
    public boolean kickOnInvalidSession() { return kickOnInvalidSession; }
    public String invalidSessionMessage() { return invalidSessionMessage; }

    public boolean floodgateEnabled() { return floodgateEnabled; }
    public boolean floodgateAutoRegister() { return floodgateAutoRegister; }

    public boolean authAutoRegister() { return authAutoRegister; }
    public int generatedPasswordLength() { return generatedPasswordLength; }

    public int requestTimeoutMs() { return requestTimeoutMs; }
    public int cacheSeconds() { return cacheSeconds; }
    public int workerThreads() { return workerThreads; }

    public String databaseFile() { return databaseFile; }

    public boolean debug() { return debug; }
}
