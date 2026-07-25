package dev.quicklogin.listener;

import dev.quicklogin.auth.AuthMeInternalHook;
import dev.quicklogin.auth.FloodgateHook;
import dev.quicklogin.config.QuickLoginConfig;
import dev.quicklogin.mojang.MojangApiService;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;

import java.util.UUID;
import java.util.logging.Logger;

/**
 * Runs at the earliest point of a connection ({@link AsyncPlayerPreLoginEvent})
 * and, for trusted players, approves them through AuthMe 6's pre-join dialog so
 * it never shows for them. Normal (cracked) players are left alone and still get
 * the dialog.
 *
 * <p>"Trusted" = a Bedrock/Floodgate player, or a player already carrying a
 * Mojang-verified v4 UUID (online-mode server / proxy forwarding). On an
 * offline server with no proxy, premium Java players are not yet verified here
 * (v3 UUID) and cannot be approved — that case needs AuthMe's own premium
 * feature.
 */
public final class PreLoginListener implements Listener {

    /** Poll cadence and cap: ~4 ticks * 75 ≈ 15s, covering the dialog window. */
    private static final long POLL_TICKS = 4L;
    private static final int MAX_POLLS = 75;

    private final Plugin plugin;
    private final Logger logger;
    private volatile QuickLoginConfig config;
    private final FloodgateHook floodgate;         // may be null
    private final AuthMeInternalHook preJoinHook;
    private final dev.quicklogin.premium.PremiumVerifier premium;
    private final boolean proxyMode;
    private final MojangApiService mojang;           // may be null

    public PreLoginListener(Plugin plugin, QuickLoginConfig config, FloodgateHook floodgate,
                            AuthMeInternalHook preJoinHook, dev.quicklogin.premium.PremiumVerifier premium,
                            boolean proxyMode, MojangApiService mojang) {
        this.plugin = plugin;
        this.logger = plugin.getLogger();
        this.config = config;
        this.floodgate = floodgate;
        this.preJoinHook = preJoinHook;
        this.premium = premium;
        this.proxyMode = proxyMode;
        this.mojang = mojang;
    }

    /** Apply a freshly reloaded config to this live listener. */
    public void updateConfig(QuickLoginConfig config) {
        this.config = config;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPreLogin(AsyncPlayerPreLoginEvent event) {
        if (!config.skipPreJoinDialog() || !preJoinHook.isAvailable()) {
            return; // Nothing to do; join-time path handles the rest (e.g. preJoin disabled).
        }
        if (event.getLoginResult() != AsyncPlayerPreLoginEvent.Result.ALLOWED) {
            return;
        }

        String name = event.getName();
        UUID uuid = event.getUniqueId();

        boolean bedrock = config.floodgateEnabled()
                && (floodgate != null
                    ? floodgate.isBedrockPlayer(uuid)
                    : FloodgateHook.hasFloodgateUuid(uuid));
        // SECURITY: only approve players whose identity is cryptographically proven
        // (verified v4 UUID from an online-mode proxy, or our own PacketEvents
        // handshake). A Mojang API name lookup does NOT prove ownership behind an
        // offline-mode proxy, so it must never gate a pre-join approval — otherwise
        // a cracked client could be waved past the dialog onto a premium account.
        boolean verifiedPremium = config.premiumEnabled()
                && (uuid.version() == 4 || premium.isVerified(name));

        if (!bedrock && !verifiedPremium) {
            return;
        }

        String type = bedrock ? "Bedrock" : "premium";
        if (config.debug()) {
            logger.info("Pre-login '" + name + "' (" + type + "): will approve through pre-join dialog.");
        }

        if (preJoinHook.approvePreJoin(name)) {
            if (config.debug()) {
                logger.info("Approved " + type + " player '" + name + "' through the pre-join dialog (immediate).");
            }
            return;
        }
        startApproveLoop(name, type);
    }

    /**
     * The pre-join dialog is only registered once AuthMe reaches the config
     * phase, which is slightly after this event, so we poll until the approval
     * lands (or give up).
     */
    private void startApproveLoop(String name, String type) {
        new BukkitRunnable() {
            int attempts = 0;

            @Override
            public void run() {
                attempts++;
                boolean approved = preJoinHook.approvePreJoin(name);
                if (approved) {
                    if (config.debug()) {
                        logger.info("Approved " + type + " player '" + name + "' through the pre-join dialog.");
                    }
                    cancel();
                    return;
                }
                if (attempts >= MAX_POLLS) {
                    cancel();
                }
            }
        }.runTaskTimerAsynchronously(plugin, 1L, POLL_TICKS);
    }
}
