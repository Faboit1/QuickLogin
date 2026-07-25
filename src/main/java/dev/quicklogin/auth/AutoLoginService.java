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
    private volatile QuickLoginConfig config;
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

    /** Apply a freshly reloaded config to this live service. */
    public void updateConfig(QuickLoginConfig config) {
        this.config = config;
    }

    public void onJoin(Player player) {
        String name = player.getName();
        String lower = name.toLowerCase(Locale.ROOT);
        UUID uuid = player.getUniqueId();

        boolean bedrock = config.floodgateEnabled()
                && (floodgate != null
                    ? floodgate.isBedrockPlayer(player)
                    : FloodgateHook.hasFloodgateUuid(uuid));

        // A player counts as premium ONLY when their identity was cryptographically
        // proven: either the network verified them (real version-4 Mojang UUID,
        // i.e. an online-mode proxy/forwarding) or QuickLogin's own PacketEvents
        // handshake verified them in standalone mode.
        //
        // SECURITY: behind an OFFLINE-mode proxy there is NO way to prove ownership
        // of a premium name — a Mojang API name lookup only says the name is paid,
        // not that this client owns it. Auto-logging on a name match let any cracked
        // client take over a premium account by simply using its name. That path is
        // removed; such players fall through to AuthMe's normal /login.
        boolean isPremium = !bedrock
                && config.premiumEnabled()
                && (uuid.version() == 4 || this.premium.isVerified(name));

        if (config.debug()) {
            logger.info("Join '" + name + "': uuid=" + uuid + " (v" + uuid.version() + "), "
                    + "floodgate=" + bedrock
                    + ", verified=" + this.premium.isVerified(name)
                    + ", proxyMode=" + proxyMode
                    + " -> " + (bedrock ? "BEDROCK" : isPremium ? "PREMIUM" : "not verified (AuthMe)"));
        }

        if (!bedrock && !isPremium) {
            return;
        }

        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            final String type = bedrock ? "Bedrock" : "premium";
            final String uuidStr = uuid.toString();
            final boolean storedPremium = isPremium;

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

            // Already logged in: stop. Never call login again on an authenticated
            // player — that leaves them stuck (AuthMe says "already logged in"
            // but never re-runs the limbo restore, so they can't move).
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
                boolean reg = authme.forceRegister(player, password);
                logger.info("Auto-registered " + type + " player '" + name + "'"
                        + (reg ? "." : " FAILED."));
                // AuthMe 6's forceRegister does not reliably log the player in,
                // so follow up with an explicit forceLogin in the same pass.
                ok = reg && authme.forceLogin(player);
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
