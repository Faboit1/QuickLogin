package dev.quicklogin;

import com.github.retrooper.packetevents.PacketEvents;
import dev.quicklogin.auth.AuthMeHook;
import dev.quicklogin.auth.AuthMeInternalHook;
import dev.quicklogin.auth.AutoLoginService;
import dev.quicklogin.auth.FloodgateHook;
import dev.quicklogin.command.QuickLoginCommand;
import dev.quicklogin.config.QuickLoginConfig;
import dev.quicklogin.listener.JoinListener;
import dev.quicklogin.listener.PreLoginListener;
import dev.quicklogin.mojang.MojangApiService;
import dev.quicklogin.premium.PremiumHandshakeListener;
import dev.quicklogin.premium.PremiumVerifier;
import dev.quicklogin.storage.Database;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * QuickLogin backend plugin: auto-registers and auto-logs Bedrock (Floodgate)
 * and Mojang-verified players into AuthMeReloaded.
 *
 * <p>For AuthMe 6, trusted players are approved through its configuration-phase
 * pre-join dialog ({@link AuthMeInternalHook}) so it never shows for them, then
 * force-logged-in on join.
 */
public final class QuickLoginPlugin extends JavaPlugin {

    private QuickLoginConfig config;
    private Database database;
    private AuthMeHook authme;
    private FloodgateHook floodgate;
    private ExecutorService workers;
    private PremiumHandshakeListener premiumListener;
    private MojangApiService mojang;
    private boolean proxyMode;
    private AutoLoginService autoLogin;
    private PreLoginListener preLoginListener;

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

        // --- AuthMe 6 pre-join dialog hook (reflection) ---
        AuthMeInternalHook preJoinHook = AuthMeInternalHook.create(getLogger(), config.debug());

        // --- Mojang (direct) + premium verifier ---
        this.workers = Executors.newFixedThreadPool(3, daemonThreads());
        this.mojang = new MojangApiService(getLogger(), config.debug(),
                config.mojangTimeoutMs(), config.mojangCacheSeconds());
        PremiumVerifier premiumVerifier = new PremiumVerifier(mojang, workers);

        // --- Proxy detection ---
        boolean behindProxy = detectProxy();
        this.proxyMode = behindProxy;

        // --- Premium handshake (PacketEvents, standalone only) ---
        boolean premiumActive = false;
        if (config.premiumEnabled()) {
            if (behindProxy) {
                getLogger().info("Proxy mode — premium detected via Mojang API name lookup. "
                        + "PacketEvents handshake skipped (not possible behind a proxy).");
            } else {
                premiumActive = registerPremiumHandshake(premiumVerifier, mojang);
            }
        }

        // --- Auto-login + listeners ---
        this.autoLogin = new AutoLoginService(
                this, config, database, authme, floodgate, premiumVerifier, behindProxy, mojang);
        this.preLoginListener = new PreLoginListener(this, config, floodgate, preJoinHook,
                premiumVerifier, behindProxy, mojang);
        getServer().getPluginManager().registerEvents(new JoinListener(autoLogin), this);
        getServer().getPluginManager().registerEvents(preLoginListener, this);

        QuickLoginCommand command = new QuickLoginCommand(this);
        if (getCommand("quicklogin") != null) {
            getCommand("quicklogin").setExecutor(command);
            getCommand("quicklogin").setTabCompleter(command);
        }

        String premiumStatus;
        if (!config.premiumEnabled()) {
            premiumStatus = "disabled";
        } else if (behindProxy) {
            premiumStatus = "active (proxy/Mojang API)";
        } else if (premiumActive) {
            premiumStatus = "active (PacketEvents)";
        } else {
            premiumStatus = "inactive";
        }

        getLogger().info("QuickLogin enabled. AuthMe: hooked"
                + " | Floodgate: " + (floodgate != null ? "hooked" : "not found")
                + " | pre-join dialog hook: " + (preJoinHook.isAvailable() ? "active" : "unavailable")
                + " | premium: " + premiumStatus
                + " | Bedrock auto-login: " + (config.floodgateEnabled() && floodgate != null ? "on" : "off"));
        if (config.premiumEnabled() && premiumActive && !behindProxy) {
            getLogger().info("IMPORTANT: QuickLogin now performs premium verification itself. "
                    + "Set 'settings.enablePremium: false' in AuthMe's config to avoid a double handshake.");
        }
    }

    /**
     * Determine whether this server is behind a BungeeCord/Velocity proxy.
     * Config values: "true" forces proxy, "false" forces standalone, "auto" auto-detects.
     */
    private boolean detectProxy() {
        String setting = config.premiumProxy();
        if ("true".equals(setting)) {
            getLogger().info("premium.proxy = true (forced proxy mode).");
            return true;
        }
        if ("false".equals(setting)) {
            getLogger().info("premium.proxy = false (forced standalone mode).");
            return false;
        }
        // auto-detect
        boolean bungeecord = false;
        try {
            bungeecord = getServer().spigot().getConfig()
                    .getBoolean("settings.bungeecord", false);
        } catch (Throwable ignored) { }

        boolean velocity = detectVelocity();

        boolean result = bungeecord || velocity;
        getLogger().info("Proxy auto-detect: spigot bungeecord=" + bungeecord
                + ", paper velocity=" + velocity + " → " + (result ? "PROXY mode" : "STANDALONE mode")
                + ". Override with 'premium.proxy: true/false' in config.yml if wrong.");
        return result;
    }

    private boolean detectVelocity() {
        try {
            Class<?> globalConfig = Class.forName(
                    "io.papermc.paper.configuration.GlobalConfiguration");
            Object instance = globalConfig.getMethod("get").invoke(null);
            Object proxies = globalConfig.getField("proxies").get(instance);
            Object velocityObj = proxies.getClass().getField("velocity").get(proxies);
            Object enabled = velocityObj.getClass().getField("enabled").get(velocityObj);
            return enabled instanceof Boolean b && b;
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** Register the PacketEvents login listener. Returns true if it went live. */
    private boolean registerPremiumHandshake(PremiumVerifier verifier, MojangApiService mojang) {
        if (getServer().getPluginManager().getPlugin("packetevents") == null) {
            getLogger().warning("PacketEvents not found; premium verification is disabled. "
                    + "Install PacketEvents to enable premium auto-login.");
            return false;
        }
        try {
            this.premiumListener = new PremiumHandshakeListener(
                    verifier, mojang, workers, getLogger(), floodgate, config);
            PacketEvents.getAPI().getEventManager().registerListener(premiumListener);
            return true;
        } catch (Throwable t) {
            getLogger().warning("Failed to register premium handshake with PacketEvents: " + t);
            return false;
        }
    }

    private static ThreadFactory daemonThreads() {
        AtomicInteger idx = new AtomicInteger();
        return r -> {
            Thread t = new Thread(r, "QuickLogin-Worker-" + idx.incrementAndGet());
            t.setDaemon(true);
            return t;
        };
    }

    @Override
    public void onDisable() {
        if (premiumListener != null) {
            try {
                premiumListener.cleanup();
                PacketEvents.getAPI().getEventManager().unregisterListener(premiumListener);
            } catch (Throwable ignored) {
            }
        }
        if (workers != null) {
            workers.shutdownNow();
        }
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

    public boolean isProxyMode() {
        return proxyMode;
    }

    public MojangApiService getMojang() {
        return mojang;
    }

    /**
     * Reload config.yml and push the new settings into the live services so
     * changes take effect without a restart. Note: a few construction-time
     * settings (proxy mode, Mojang timeout/cache size, PacketEvents handshake)
     * still require a full server restart to change.
     */
    public void reloadPluginConfig() {
        reloadConfig();
        this.config = QuickLoginConfig.from(getConfig());
        if (autoLogin != null) {
            autoLogin.updateConfig(config);
        }
        if (preLoginListener != null) {
            preLoginListener.updateConfig(config);
        }
        if (mojang != null) {
            mojang.setDebug(config.debug());
            mojang.clearCache();
        }
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
