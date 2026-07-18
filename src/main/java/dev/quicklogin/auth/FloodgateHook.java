package dev.quicklogin.auth;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.lang.reflect.Method;
import java.util.UUID;
import java.util.logging.Logger;

/**
 * Optional, reflection-based hook into Floodgate to detect Bedrock players.
 *
 * <p>Floodgate only publishes moving SNAPSHOT artifacts, so instead of a
 * compile-time dependency QuickLogin calls the two stable API methods it needs
 * ({@code FloodgateApi.getInstance()} and {@code isFloodgatePlayer(UUID)})
 * reflectively. If Floodgate is not installed, {@link #tryHook} returns
 * {@code null} and Bedrock auto-login is simply inactive.
 */
public final class FloodgateHook {

    private final Object api;
    private final Method isFloodgatePlayer;

    private FloodgateHook(Object api, Method isFloodgatePlayer) {
        this.api = api;
        this.isFloodgatePlayer = isFloodgatePlayer;
    }

    /** @return a hook, or {@code null} if Floodgate is not installed. */
    public static FloodgateHook tryHook(Logger logger) {
        if (Bukkit.getPluginManager().getPlugin("floodgate") == null) {
            return null;
        }
        try {
            Class<?> apiClass = Class.forName("org.geysermc.floodgate.api.FloodgateApi");
            Object api = apiClass.getMethod("getInstance").invoke(null);
            if (api == null) {
                return null;
            }
            Method isFloodgatePlayer = apiClass.getMethod("isFloodgatePlayer", UUID.class);
            return new FloodgateHook(api, isFloodgatePlayer);
        } catch (Throwable t) {
            logger.warning("Floodgate is present but its API could not be hooked: " + t.getMessage());
            return null;
        }
    }

    public boolean isBedrockPlayer(UUID uuid) {
        try {
            Object result = isFloodgatePlayer.invoke(api, uuid);
            return result instanceof Boolean b && b;
        } catch (Throwable t) {
            return false;
        }
    }

    public boolean isBedrockPlayer(Player player) {
        return isBedrockPlayer(player.getUniqueId());
    }
}
