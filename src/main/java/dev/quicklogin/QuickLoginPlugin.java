package dev.quicklogin;

import com.comphenix.protocol.ProtocolLibrary;
import com.comphenix.protocol.ProtocolManager;
import dev.quicklogin.auth.AuthMeHook;
import dev.quicklogin.auth.AutoLoginService;
import dev.quicklogin.auth.FloodgateHook;
import dev.quicklogin.command.QuickLoginCommand;
import dev.quicklogin.config.QuickLoginConfig;
import dev.quicklogin.listener.JoinListener;
import dev.quicklogin.login.LoginListener;
import dev.quicklogin.login.PremiumLoginManager;
import dev.quicklogin.mojang.MojangApiService;
import dev.quicklogin.storage.Database;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.util.Locale;

public final class QuickLoginPlugin extends JavaPlugin {

    private QuickLoginConfig config;
    private Database database;
    private MojangApiService mojang;
    private AuthMeHook authme;
    private FloodgateHook floodgate;
    private PremiumLoginManager premiumManager; // null when premium disabled
    private LoginListener loginListener;

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

        // --- Mojang (direct API) ---
        this.mojang = new MojangApiService(getLogger(), config.debug(),
                config.requestTimeoutMs(), config.cacheSeconds());

        // --- Floodgate (optional) ---
        this.floodgate = FloodgateHook.tryHook(getLogger());
        if (config.floodgateEnabled() && floodgate == null) {
            getLogger().info("Floodgate not detected; Bedrock auto-login is inactive.");
        }

        // --- Premium login (ProtocolLib) ---
        if (config.premiumEnabled()) {
            try {
                ProtocolManager protocol = ProtocolLibrary.getProtocolManager();
                this.premiumManager = new PremiumLoginManager(getLogger(), config, protocol, mojang);
                this.loginListener = new LoginListener(this, premiumManager);
                protocol.addPacketListener(loginListener);
                getLogger().info("Premium Java auto-login enabled.");
            } catch (Throwable t) {
                getLogger().severe("Failed to enable premium login (is ProtocolLib installed?): " + t.getMessage());
            }
        }

        // --- Auto-login + listeners ---
        AutoLoginService autoLogin = new AutoLoginService(this, config, database, authme, floodgate, premiumManager);
        getServer().getPluginManager().registerEvents(new JoinListener(autoLogin), this);

        QuickLoginCommand command = new QuickLoginCommand(this);
        if (getCommand("quicklogin") != null) {
            getCommand("quicklogin").setExecutor(command);
            getCommand("quicklogin").setTabCompleter(command);
        }

        getLogger().info("QuickLogin enabled.");
    }

    @Override
    public void onDisable() {
        if (premiumManager != null) {
            premiumManager.shutdown();
        }
        if (loginListener != null) {
            try {
                ProtocolLibrary.getProtocolManager().removePacketListener(loginListener);
            } catch (Throwable ignored) {
            }
        }
        if (database != null) {
            database.close();
        }
    }

    // ------------------------------------------------------------------
    //  Accessors / actions used by the command
    // ------------------------------------------------------------------

    public QuickLoginConfig config() {
        return config;
    }

    public boolean hasFloodgate() {
        return floodgate != null;
    }

    public void reloadPluginConfig() {
        reloadConfig();
        this.config = QuickLoginConfig.from(getConfig());
        if (mojang != null) {
            mojang.clearCache();
        }
    }

    /**
     * Remove the stored credentials for a player so a fresh password is
     * generated on their next join. Also unregisters them from AuthMe.
     * Call from an async context (does DB + AuthMe work).
     */
    public boolean resetAccount(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        boolean removed = database.delete(lower);
        if (mojang != null) {
            mojang.invalidate(name);
        }
        // AuthMe API must be touched on the main thread.
        getServer().getScheduler().runTask(this, () -> authme.unregister(name));
        return removed;
    }
}
