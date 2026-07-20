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

    private final Logger logger;
    private final boolean debug;

    private Object preJoinService;
    private Method approveMethod;
    private boolean available;
    private boolean warned;

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
            Object injector = readInjector(authme);
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
        } catch (Throwable t) {
            warnOnce("AuthMe pre-join hook unavailable (" + t + "); using join-time fallback only.");
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
