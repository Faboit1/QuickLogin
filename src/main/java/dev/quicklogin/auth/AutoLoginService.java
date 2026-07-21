package dev.quicklogin.auth;

import dev.quicklogin.config.QuickLoginConfig;
import dev.quicklogin.mojang.MojangApiService;
import dev.quicklogin.storage.Database;
import dev.quicklogin.storage.PlayerData;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.security.SecureRandom;
import java.util.Locale;
import java.util.UUID;
import java.util.logging.Logger;

/**
 * Auto-registers and auto-logs verified players into AuthMe at join time.
 *
 * <p>Classification (no proxy plugin needed):
 * <ul>
 *   <li><b>Bedrock</b> (Floodgate) &rarr; auto-login. Works on any Velocity mode.</li>
 *   <li><b>Premium</b> &rarr; a Java player who arrives with a real, version-4
 *       Mojang UUID. That only happens when the network already verified them
 *       (Velocity {@code online-mode = true}), which also means expired/invalid
 *       sessions were already kicked upstream with the vanilla prompt.</li>
 *   <li><b>Cracked</b> (version-3 offline UUID) &rarr; left to AuthMe's normal
 *       {@code /register} + {@code /login} flow.</li>
 * </ul>
 *
 * <p>Database work runs off the main thread; the AuthMe API is only ever touched
 * on the main thread, shortly after join, with a couple of retries so AuthMe's
 * own join handling can't race us.
 */
public final class AutoLoginService {

    private static final char[] CHARSET =
            "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789".toCharArray();

    /** Extra login attempts (beyond the first) if AuthMe hasn't taken it yet. */
    private static final int MAX_ATTEMPTS = 4;
    private static final long RETRY_INTERVAL_TICKS = 15L;

    private final Plugin plugin;
    private final Logger logger;
    private final QuickLoginConfig config;
    private final Database db;
    private final AuthMeHook authme;
    private final FloodgateHook floodgate;   // may be null
    private final dev.quicklogin.premium.PremiumVerifier premium;
    private final boolean proxyMode;
    private final MojangApiService mojang;   // may be null
    private final SecureRandom random = new SecureRandom();

    public AutoLoginService(Plugin plugin, QuickLoginConfig config, Database db, AuthMeHook authme,
                            FloodgateHook floodgate, dev.quicklogin.premium.PremiumVerifier premium,
                            boolean proxyMode, MojangApiService mojang) {
        this.plugin = plugin;
        this.logger = plugin.getLogger();
        this.config = config;
        this.db = db;
        this.authme = authme;
        this.floodgate = floodgate;
        this.premium = premium;
        this.proxyMode = proxyMode;
        this.mojang = mojang;
    }

    public void onJoin(Player player) {
        String name = player.getName();
        String lower = name.toLowerCase(Locale.ROOT);
        UUID uuid = player.getUniqueId();

        boolean bedrock = config.floodgateEnabled()
                && floodgate != null
                && floodgate.isBedrockPlayer(player);

        boolean isPremium = !bedrock
                && config.premiumEnabled()
                && (uuid.version() == 4 || this.premium.isVerified(name));

        // In proxy mode we may need an async Mojang API lookup to detect premium
        boolean needsProxyCheck = !bedrock && !isPremium
                && proxyMode && config.premiumEnabled() && mojang != null;

        if (config.debug()) {
            logger.info("Join '" + name + "': uuid=" + uuid + " (v" + uuid.version() + "), "
                    + "floodgate=" + (floodgate != null && floodgate.isBedrockPlayer(player))
                    + ", verified=" + this.premium.isVerified(name)
                    + ", proxyMode=" + proxyMode
                    + " -> " + (bedrock ? "BEDROCK" : isPremium ? "PREMIUM"
                            : needsProxyCheck ? "proxy check" : "cracked (ignored)"));
        }

        if (!bedrock && !isPremium && !needsProxyCheck) {
            return;
        }

        final boolean premiumFlag = isPremium;

        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            boolean finalPremium = premiumFlag;

            if (needsProxyCheck) {
                MojangApiService.Result result = mojang.lookup(name);
                finalPremium = result == MojangApiService.Result.PREMIUM;
                logger.info("[QuickLogin] Proxy premium check for '" + name + "': " + result
                        + (finalPremium ? " → will auto-login" : " → leaving to AuthMe"));
                if (!finalPremium) {
                    return;
                }
            }

            final String type = bedrock ? "Bedrock" : "premium";
            final String uuidStr = uuid.toString();
            final boolean storedPremium = finalPremium;

            long now = System.currentTimeMillis();
            PlayerData existing = db.find(lower);
            String password;
            long firstSeen;

            if (existing == null || existing.password() == null || existing.password().isEmpty()) {
                password = generatePassword();
                firstSeen = existing != null ? existing.firstSeen() : now;
            } else {
                password = existing.password();
                firstSeen = existing.firstSeen();
            }

            boolean premiumStored = storedPremium || (existing != null && existing.premium());
            db.save(new PlayerData(lower, name, uuidStr, premiumStored, password, firstSeen, now));

            final String pw = password;
            scheduleLogin(player, name, pw, type, 1);
        });
    }

    private void scheduleLogin(Player player, String name, String password, String type, int attempt) {
        long delay = attempt == 1 ? Math.max(1L, config.loginDelayTicks()) : RETRY_INTERVAL_TICKS;
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (!player.isOnline()) {
                return;
            }
            if (authme.isAuthenticated(player)) {
                if (config.debug()) logger.info("'" + name + "' already authenticated.");
                return;
            }

            boolean ok;
            if (authme.isRegistered(name)) {
                ok = authme.forceLogin(player);
                if (config.debug()) logger.info("forceLogin(" + name + ") [" + type + "] attempt "
                        + attempt + " -> " + ok);
            } else if (config.authAutoRegister()) {
                ok = authme.forceRegister(player, password);
                logger.info("Auto-registered " + type + " player '" + name + "'"
                        + (ok ? "." : " FAILED."));
            } else {
                return; // not registered and auto-register disabled: leave to AuthMe.
            }

            // If it didn't take (AuthMe processed us late), retry a few times.
            if ((!ok || !authme.isAuthenticated(player)) && attempt < MAX_ATTEMPTS) {
                scheduleLogin(player, name, password, type, attempt + 1);
            }
        }, delay);
    }

    private String generatePassword() {
        int len = config.generatedPasswordLength();
        StringBuilder sb = new StringBuilder(len);
        for (int i = 0; i < len; i++) {
            sb.append(CHARSET[random.nextInt(CHARSET.length)]);
        }
        return sb.toString();
    }
}
