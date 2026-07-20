package dev.quicklogin.listener;

import dev.quicklogin.auth.AuthMeInternalHook;
import dev.quicklogin.auth.FloodgateHook;
import dev.quicklogin.config.QuickLoginConfig;
import dev.quicklogin.mojang.MojangApiService;
import fr.xephi.authme.events.LoginEvent;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.plugin.Plugin;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

/**
 * Enrolls premium players into AuthMe's native premium mode automatically, so
 * nobody has to run {@code /premium}.
 *
 * <p>AuthMe only performs its cryptographic premium verification for players who
 * are already enrolled ({@code premium_uuid} in the DB) or pending. This listener
 * reacts to AuthMe's {@link LoginEvent}: when a premium Java player logs in and
 * isn't enrolled yet, it calls AuthMe's own {@code enablePremium}, which runs the
 * standard verify-on-reconnect flow. From then on AuthMe verifies and auto-logs
 * them in on every join, and {@link PreLoginListener} waves them past the
 * pre-join dialog.
 *
 * <p>We never fabricate premium status: enrollment is finalized by AuthMe's own
 * Mojang session check, so a cracked client using a premium name still fails.
 */
public final class PremiumEnrollmentListener implements Listener {

    private final Plugin plugin;
    private final Logger logger;
    private final QuickLoginConfig config;
    private final MojangApiService mojang;
    private final AuthMeInternalHook hook;
    private final FloodgateHook floodgate; // may be null

    /** Names we've already asked AuthMe to enroll this session (avoids re-triggering). */
    private final Set<String> triggered = ConcurrentHashMap.newKeySet();

    public PremiumEnrollmentListener(Plugin plugin, QuickLoginConfig config, MojangApiService mojang,
                                     AuthMeInternalHook hook, FloodgateHook floodgate) {
        this.plugin = plugin;
        this.logger = plugin.getLogger();
        this.config = config;
        this.mojang = mojang;
        this.hook = hook;
        this.floodgate = floodgate;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onLogin(LoginEvent event) {
        if (!config.premiumEnabled() || !config.premiumAutoEnroll() || !hook.premiumBridgeAvailable()) {
            return;
        }
        Player player = event.getPlayer();
        if (player == null) {
            return;
        }
        String name = player.getName();
        UUID uuid = player.getUniqueId();

        // Bedrock players are handled by the Floodgate auto-login path, not premium.
        if (floodgate != null && floodgate.isBedrockPlayer(uuid)) {
            return;
        }
        // Already enrolled (AuthMe verified them) — nothing to do.
        if (hook.getVerifiedPremiumUuid(name) != null && hook.isEnrolledPremium(name)) {
            return;
        }
        String lower = name.toLowerCase(java.util.Locale.ROOT);
        if (!triggered.add(lower)) {
            return; // already triggered this session
        }

        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            if (mojang.lookup(name) != MojangApiService.Result.PREMIUM) {
                triggered.remove(lower); // not premium; allow a future re-check
                return;
            }
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (!player.isOnline()) {
                    return;
                }
                if (hook.isEnrolledPremium(name)) {
                    return; // became enrolled in the meantime
                }
                if (config.debug()) {
                    logger.info("Enrolling premium player '" + name + "' into AuthMe (auto /premium).");
                }
                hook.enablePremium(player);
            });
        });
    }
}
