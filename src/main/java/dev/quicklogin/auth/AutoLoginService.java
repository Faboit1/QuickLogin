package dev.quicklogin.auth;

import dev.quicklogin.config.QuickLoginConfig;
import dev.quicklogin.login.PremiumLoginManager;
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
 * Performs the actual AuthMe auto-login for verified premium and Bedrock
 * players at join time.
 *
 * <p>Threading: database work runs off the main thread; the AuthMe API is only
 * ever touched on the main thread, a couple of ticks after join so AuthMe has
 * finished its own join handling first.
 */
public final class AutoLoginService {

    private static final char[] CHARSET =
            "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789".toCharArray();

    private final Plugin plugin;
    private final Logger logger;
    private final QuickLoginConfig config;
    private final Database db;
    private final AuthMeHook authme;
    private final FloodgateHook floodgate;   // may be null
    private final PremiumLoginManager premium; // may be null (premium disabled)
    private final SecureRandom random = new SecureRandom();

    public AutoLoginService(Plugin plugin, QuickLoginConfig config, Database db,
                            AuthMeHook authme, FloodgateHook floodgate, PremiumLoginManager premium) {
        this.plugin = plugin;
        this.logger = plugin.getLogger();
        this.config = config;
        this.db = db;
        this.authme = authme;
        this.floodgate = floodgate;
        this.premium = premium;
    }

    public void onJoin(Player player) {
        String name = player.getName();
        String lower = name.toLowerCase(Locale.ROOT);

        PremiumLoginManager.PremiumMark mark = premium == null ? null : premium.consumeVerified(lower);
        boolean isPremium = mark != null;
        boolean isBedrock = !isPremium
                && config.floodgateEnabled()
                && floodgate != null
                && floodgate.isBedrockPlayer(player);

        if (!isPremium && !isBedrock) {
            // Ordinary cracked player: leave them to AuthMe's normal /login flow.
            return;
        }

        UUID onlineUuid = isPremium ? mark.onlineUuid() : null;
        final boolean premiumFlag = isPremium;

        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            long now = System.currentTimeMillis();
            PlayerData existing = db.find(lower);
            String password;
            long firstSeen;
            String uuidStr = onlineUuid != null ? onlineUuid.toString()
                    : (existing != null ? existing.premiumUuid() : null);

            if (existing == null || existing.password() == null || existing.password().isEmpty()) {
                // Brand new account (or one that never got a managed password):
                // generate one now and persist it.
                password = generatePassword();
                firstSeen = existing != null ? existing.firstSeen() : now;
            } else {
                password = existing.password();
                firstSeen = existing.firstSeen();
            }

            boolean premiumStored = premiumFlag || (existing != null && existing.premium());
            db.save(new PlayerData(lower, name, uuidStr, premiumStored, password, firstSeen, now));

            final String pw = password;
            // Defer AuthMe interaction to the main thread, a couple of ticks
            // later, so it wins over AuthMe's own join handling.
            Bukkit.getScheduler().runTaskLater(plugin, () -> applyLogin(player, name, pw), 2L);
        });
    }

    private void applyLogin(Player player, String name, String password) {
        if (!player.isOnline()) {
            return;
        }
        if (authme.isAuthenticated(player)) {
            return;
        }
        boolean ok;
        if (authme.isRegistered(name)) {
            ok = authme.forceLogin(player);
        } else {
            // Register the account with the managed password, then log in.
            ok = authme.forceRegister(player, password);
        }
        if (config.debug()) {
            logger.info("Auto-login for '" + name + "': " + (ok ? "success" : "FAILED"));
        }
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
