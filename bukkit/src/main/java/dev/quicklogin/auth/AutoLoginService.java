package dev.quicklogin.auth;

import dev.quicklogin.config.QuickLoginConfig;
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
 * Auto-logs verified players into AuthMe at join time.
 *
 * <p>On a Velocity network the proxy performs premium verification (the
 * companion QuickLogin-Velocity plugin forces online-mode login for paid
 * accounts). With modern forwarding the backend then receives the player's real
 * Mojang UUID, so we can classify each player without any cross-plugin
 * messaging:
 * <ul>
 *   <li>Bedrock (Floodgate) &rarr; auto-login</li>
 *   <li>Version-4 UUID &rarr; premium, Mojang-verified by the proxy &rarr; auto-login</li>
 *   <li>Version-3 UUID &rarr; offline/cracked &rarr; left to AuthMe's normal flow</li>
 * </ul>
 *
 * <p>Database work runs off the main thread; the AuthMe API is only ever touched
 * on the main thread, a couple of ticks after join.
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
    private final SecureRandom random = new SecureRandom();

    public AutoLoginService(Plugin plugin, QuickLoginConfig config, Database db,
                            AuthMeHook authme, FloodgateHook floodgate) {
        this.plugin = plugin;
        this.logger = plugin.getLogger();
        this.config = config;
        this.db = db;
        this.authme = authme;
        this.floodgate = floodgate;
    }

    public void onJoin(Player player) {
        String name = player.getName();
        String lower = name.toLowerCase(Locale.ROOT);
        UUID uuid = player.getUniqueId();

        boolean bedrock = config.floodgateEnabled()
                && floodgate != null
                && floodgate.isBedrockPlayer(player);

        boolean premium = !bedrock
                && config.premiumEnabled()
                && uuid.version() == 4; // Mojang UUIDs are version 4; offline UUIDs are version 3.

        if (!bedrock && !premium) {
            // Ordinary cracked player: leave them to AuthMe's normal /login flow.
            return;
        }

        final boolean premiumFlag = premium;
        final String uuidStr = uuid.toString();

        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
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

            boolean premiumStored = premiumFlag || (existing != null && existing.premium());
            db.save(new PlayerData(lower, name, uuidStr, premiumStored, password, firstSeen, now));

            final String pw = password;
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
        } else if (config.authAutoRegister()) {
            ok = authme.forceRegister(player, password);
        } else {
            return; // auto-register disabled and not registered: leave to AuthMe.
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
