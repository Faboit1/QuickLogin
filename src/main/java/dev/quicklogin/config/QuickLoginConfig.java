package dev.quicklogin.config;

import org.bukkit.configuration.file.FileConfiguration;

/**
 * Immutable snapshot of the backend plugin configuration.
 */
public final class QuickLoginConfig {

    private final boolean premiumEnabled;
    private final boolean premiumAutoEnroll;
    private final boolean floodgateEnabled;
    private final boolean authAutoRegister;
    private final boolean skipPreJoinDialog;
    private final int generatedPasswordLength;
    private final int loginDelayTicks;
    private final int mojangTimeoutMs;
    private final int mojangCacheSeconds;
    private final String databaseFile;
    private final boolean debug;

    private QuickLoginConfig(FileConfiguration c) {
        this.premiumEnabled = c.getBoolean("premium.enabled", true);
        this.premiumAutoEnroll = c.getBoolean("premium.auto-enroll", true);
        this.floodgateEnabled = c.getBoolean("floodgate.enabled", true);
        this.authAutoRegister = c.getBoolean("auth.auto-register", true);
        this.skipPreJoinDialog = c.getBoolean("auth.skip-prejoin-dialog", true);
        // Keep well under AuthMe's max password length (default 30) or registration is rejected.
        this.generatedPasswordLength = clamp(c.getInt("auth.generated-password-length", 16), 8, 30);
        this.loginDelayTicks = clamp(c.getInt("auth.login-delay-ticks", 5), 1, 200);
        this.mojangTimeoutMs = clamp(c.getInt("mojang.request-timeout-ms", 5000), 1000, 30000);
        this.mojangCacheSeconds = Math.max(0, c.getInt("mojang.cache-seconds", 600));
        this.databaseFile = c.getString("database.file", "quicklogin.db");
        this.debug = c.getBoolean("debug", false);
    }

    public static QuickLoginConfig from(FileConfiguration c) {
        return new QuickLoginConfig(c);
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    public boolean premiumEnabled() { return premiumEnabled; }
    public boolean premiumAutoEnroll() { return premiumAutoEnroll; }
    public boolean floodgateEnabled() { return floodgateEnabled; }
    public int mojangTimeoutMs() { return mojangTimeoutMs; }
    public int mojangCacheSeconds() { return mojangCacheSeconds; }
    public boolean authAutoRegister() { return authAutoRegister; }
    public boolean skipPreJoinDialog() { return skipPreJoinDialog; }
    public int generatedPasswordLength() { return generatedPasswordLength; }
    public int loginDelayTicks() { return loginDelayTicks; }
    public String databaseFile() { return databaseFile; }
    public boolean debug() { return debug; }
}
