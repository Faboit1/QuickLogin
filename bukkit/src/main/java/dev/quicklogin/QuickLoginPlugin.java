package dev.quicklogin;

import dev.quicklogin.auth.AuthMeHook;
import dev.quicklogin.auth.AutoLoginService;
import dev.quicklogin.auth.FloodgateHook;
import dev.quicklogin.command.QuickLoginCommand;
import dev.quicklogin.config.QuickLoginConfig;
import dev.quicklogin.listener.JoinListener;
import dev.quicklogin.storage.Database;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.util.Locale;

/**
 * QuickLogin backend plugin: auto-logs proxy-verified premium players and
 * Bedrock (Floodgate) players into AuthMeReloaded, registering new accounts with
 * a random password stored in SQLite.
 *
 * <p>Premium verification itself happens on the Velocity proxy (companion
 * QuickLogin-Velocity plugin); this plugin classifies players by their forwarded
 * UUID and Floodgate status.
 */
public final class QuickLoginPlugin extends JavaPlugin {

    private QuickLoginConfig config;
    private Database database;
    private AuthMeHook authme;
    private FloodgateHook floodgate;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        this.config = QuickLoginConfig.from(getConfig());

        // --- AuthMe (required) ---
        this.authme = AuthMeHook.tryHook(getLogger());
        if (authme == null) {
            getLogger().severe("AuthMeReloaded is required but was not found. Disabling QuickLogin.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        // --- Database ---
        this.database = new Database(new File(getDataFolder(), config.databaseFile()), getLogger());
        try {
            getDataFolder().mkdirs();
            database.connect();
        } catch (Exception e) {
            getLogger().severe("Failed to initialise the SQLite database: " + e.getMessage());
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        // --- Floodgate (optional) ---
        this.floodgate = FloodgateHook.tryHook(getLogger());
        if (config.floodgateEnabled() && floodgate == null) {
            getLogger().info("Floodgate not detected; Bedrock auto-login is inactive.");
        }

        // --- Auto-login + listeners ---
        AutoLoginService autoLogin = new AutoLoginService(this, config, database, authme, floodgate);
        getServer().getPluginManager().registerEvents(new JoinListener(autoLogin), this);

        QuickLoginCommand command = new QuickLoginCommand(this);
        if (getCommand("quicklogin") != null) {
            getCommand("quicklogin").setExecutor(command);
            getCommand("quicklogin").setTabCompleter(command);
        }

        getLogger().info("QuickLogin (backend) enabled.");
    }

    @Override
    public void onDisable() {
        if (database != null) {
            database.close();
        }
    }

    public QuickLoginConfig config() {
        return config;
    }

    public boolean hasFloodgate() {
        return floodgate != null;
    }

    public void reloadPluginConfig() {
        reloadConfig();
        this.config = QuickLoginConfig.from(getConfig());
    }

    /**
     * Remove stored credentials for a player so a fresh password is generated on
     * their next join, and unregister them from AuthMe. Call from async context.
     */
    public boolean resetAccount(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        boolean removed = database.delete(lower);
        getServer().getScheduler().runTask(this, () -> authme.unregister(name));
        return removed;
    }
}
