package dev.quicklogin.auth;

import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Locale;
import java.util.logging.Logger;

/**
 * Reflective bridge into AuthMe 6's internal {@code PreJoinDialogService}.
 *
 * <p>AuthMe 6 (Paper) shows its login/register dialog during the connection's
 * <em>configuration phase</em>, before the player is a normal in-world player,
 * so a plain {@code PlayerJoinEvent} never fires for an unauthenticated player.
 * AuthMe exposes exactly one designed hook for other code to let a player skip
 * that dialog: {@code PreJoinDialogService.approvePreJoinForceLogin(name)},
 * which completes the blocking pre-join future with no kick and flags the
 * player for a force-login once they reach PLAY.
 *
 * <p>That service is internal (dependency-injected), so we reach it through the
 * {@code injector} field on AuthMe's main plugin class. If anything about that
 * shape changes, this hook degrades gracefully (logs once, reports unavailable)
 * and QuickLogin falls back to the normal join-time path.
 */
public final class AuthMeInternalHook {

    private static final String PRE_JOIN_SERVICE = "fr.xephi.authme.service.PreJoinDialogService";
    private static final String PREMIUM_VERIFIER = "fr.xephi.authme.service.PremiumLoginVerifier";
    private static final String PREMIUM_SERVICE = "fr.xephi.authme.service.PremiumService";
    private static final String PLAYER_CACHE = "fr.xephi.authme.data.auth.PlayerCache";

    private final Logger logger;
    private final boolean debug;

    private Object injector;
    private Object preJoinService;
    private Method approveMethod;
    private boolean available;
    private boolean warned;

    // Premium (optional; only present when AuthMe's premium feature is compiled in)
    private Object premiumVerifier;
    private Method getVerifiedUuidMethod;
    private Object premiumService;
    private Method enablePremiumMethod;
    private Object playerCache;
    private Method cacheGetAuthMethod;
    private Method authIsPremiumMethod;

    private AuthMeInternalHook(Logger logger, boolean debug) {
        this.logger = logger;
        this.debug = debug;
    }

    /** Attempt to wire up the reflective hook. Never throws. */
    public static AuthMeInternalHook create(Logger logger, boolean debug) {
        AuthMeInternalHook hook = new AuthMeInternalHook(logger, debug);
        hook.init();
        return hook;
    }

    private void init() {
        try {
            Plugin authme = Bukkit.getPluginManager().getPlugin("AuthMe");
            if (authme == null) {
                return;
            }
            this.injector = readInjector(authme);
            if (injector == null) {
                warnOnce("Could not access AuthMe's injector; pre-join skip disabled.");
                return;
            }
            Class<?> serviceClass = Class.forName(PRE_JOIN_SERVICE);
            Object service = getService(injector, serviceClass);
            if (service == null) {
                warnOnce("AuthMe PreJoinDialogService unavailable; pre-join skip disabled "
                        + "(this AuthMe version may not use the pre-join dialog).");
                return;
            }
            this.preJoinService = service;
            this.approveMethod = serviceClass.getMethod("approvePreJoinForceLogin", String.class);
            this.available = true;
            if (debug) logger.info("AuthMe pre-join hook ready.");

            initPremium();
        } catch (Throwable t) {
            warnOnce("AuthMe pre-join hook unavailable (" + t + "); using join-time fallback only.");
        }
    }

    /** Wire up the premium bridge. Optional — absent on builds without the premium feature. */
    private void initPremium() {
        try {
            Class<?> verifierClass = Class.forName(PREMIUM_VERIFIER);
            Object verifier = getService(injector, verifierClass);
            Class<?> serviceClass = Class.forName(PREMIUM_SERVICE);
            Object service = getService(injector, serviceClass);
            if (verifier == null || service == null) {
                return;
            }
            this.premiumVerifier = verifier;
            this.getVerifiedUuidMethod = verifierClass.getMethod("getVerifiedUuid", String.class);
            this.premiumService = service;
            this.enablePremiumMethod = serviceClass.getMethod("enablePremium",
                    Class.forName("org.bukkit.entity.Player"));

            Class<?> cacheClass = Class.forName(PLAYER_CACHE);
            Object cache = getService(injector, cacheClass);
            if (cache != null) {
                this.playerCache = cache;
                this.cacheGetAuthMethod = cacheClass.getMethod("getAuth", String.class);
                this.authIsPremiumMethod = Class.forName("fr.xephi.authme.data.auth.PlayerAuth")
                        .getMethod("isPremium");
            }
            if (debug) logger.info("AuthMe premium bridge ready.");
        } catch (Throwable t) {
            if (debug) logger.info("AuthMe premium bridge unavailable (" + t + ").");
        }
    }

    /** @return true if AuthMe already has this player enrolled as premium (premium_uuid set). */
    public boolean isEnrolledPremium(String name) {
        if (playerCache == null) {
            return false;
        }
        try {
            Object auth = cacheGetAuthMethod.invoke(playerCache, name.toLowerCase(Locale.ROOT));
            if (auth == null) {
                return false;
            }
            Object premium = authIsPremiumMethod.invoke(auth);
            return premium instanceof Boolean b && b;
        } catch (Throwable t) {
            return false;
        }
    }

    public boolean premiumBridgeAvailable() {
        return premiumVerifier != null && premiumService != null;
    }

    /**
     * @return the Mojang UUID AuthMe cryptographically verified for this name on
     * the current connection, or {@code null} if not (yet) verified.
     */
    public java.util.UUID getVerifiedPremiumUuid(String name) {
        if (premiumVerifier == null) {
            return null;
        }
        try {
            Object result = getVerifiedUuidMethod.invoke(premiumVerifier, name);
            return result instanceof java.util.UUID u ? u : null;
        } catch (Throwable t) {
            return null;
        }
    }

    /** Ask AuthMe to enroll this (registered, logged-in) player into premium mode. */
    public void enablePremium(org.bukkit.entity.Player player) {
        if (premiumService == null) {
            return;
        }
        try {
            enablePremiumMethod.invoke(premiumService, player);
        } catch (Throwable t) {
            warnOnce("enablePremium failed (" + t + ").");
        }
    }

    /** Walk the plugin class hierarchy to find AuthMe's {@code injector} field. */
    private Object readInjector(Plugin authme) {
        Class<?> c = authme.getClass();
        while (c != null) {
            try {
                Field f = c.getDeclaredField("injector");
                f.setAccessible(true);
                return f.get(authme);
            } catch (NoSuchFieldException ignored) {
                c = c.getSuperclass();
            } catch (Throwable t) {
                return null;
            }
        }
        return null;
    }

    /** ch.jalu.injector: prefer getIfAvailable (no side effects), fall back to getSingleton. */
    private Object getService(Object injector, Class<?> serviceClass) {
        for (String method : new String[]{"getIfAvailable", "getSingleton"}) {
            try {
                Method m = injector.getClass().getMethod(method, Class.class);
                Object result = m.invoke(injector, serviceClass);
                if (result != null) {
                    return result;
                }
            } catch (Throwable ignored) {
                // try next
            }
        }
        return null;
    }

    public boolean isAvailable() {
        return available;
    }

    /**
     * Approve a force-login for a player currently blocked in AuthMe's pre-join
     * dialog.
     *
     * @return true if the player was in the dialog and was approved (so we can
     * stop retrying); false if not (yet) in the dialog or the hook is unavailable
     */
    public boolean approvePreJoin(String name) {
        if (!available) {
            return false;
        }
        try {
            Object result = approveMethod.invoke(preJoinService, name.toLowerCase(Locale.ROOT));
            return result instanceof Boolean b && b;
        } catch (Throwable t) {
            warnOnce("approvePreJoinForceLogin failed (" + t + "); using join-time fallback.");
            available = false;
            return false;
        }
    }

    private void warnOnce(String message) {
        if (!warned) {
            warned = true;
            logger.warning("[QuickLogin] " + message);
        }
    }
}
