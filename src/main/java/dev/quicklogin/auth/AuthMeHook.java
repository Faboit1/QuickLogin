package dev.quicklogin.auth;

import fr.xephi.authme.api.v3.AuthMeApi;
import org.bukkit.entity.Player;

import java.util.logging.Logger;

/**
 * Thin wrapper over the AuthMeReloaded v3 API. All calls must run on the main
 * server thread (AuthMe is not thread-safe).
 */
public final class AuthMeHook {

    private final Logger logger;
    private final AuthMeApi api;

    private AuthMeHook(Logger logger, AuthMeApi api) {
        this.logger = logger;
        this.api = api;
    }

    /** @return a hook, or {@code null} if AuthMe is not present/enabled. */
    public static AuthMeHook tryHook(Logger logger) {
        try {
            AuthMeApi api = AuthMeApi.getInstance();
            if (api == null) {
                return null;
            }
            return new AuthMeHook(logger, api);
        } catch (Throwable t) {
            logger.warning("AuthMeReloaded API is unavailable: " + t.getMessage());
            return null;
        }
    }

    public boolean isRegistered(String name) {
        try {
            return api.isRegistered(name);
        } catch (Throwable t) {
            logger.warning("AuthMe isRegistered failed for '" + name + "': " + t.getMessage());
            return false;
        }
    }

    public boolean isAuthenticated(Player player) {
        try {
            return api.isAuthenticated(player);
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * Register a player with the given password and log them in.
     *
     * @return true on success
     */
    public boolean forceRegister(Player player, String password) {
        try {
            api.forceRegister(player, password);
            return true;
        } catch (Throwable t) {
            logger.warning("AuthMe forceRegister failed for '" + player.getName() + "': " + t.getMessage());
            return false;
        }
    }

    /** Log an already-registered player in without a password prompt. */
    public boolean forceLogin(Player player) {
        try {
            api.forceLogin(player);
            return true;
        } catch (Throwable t) {
            logger.warning("AuthMe forceLogin failed for '" + player.getName() + "': " + t.getMessage());
            return false;
        }
    }

    /** Remove an account from AuthMe (used by the admin reset command). */
    public boolean unregister(String name) {
        try {
            api.forceUnregister(name);
            return true;
        } catch (Throwable t) {
            logger.warning("AuthMe forceUnregister failed for '" + name + "': " + t.getMessage());
            return false;
        }
    }
}
