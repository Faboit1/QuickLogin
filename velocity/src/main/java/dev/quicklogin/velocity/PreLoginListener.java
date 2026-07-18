package dev.quicklogin.velocity;

import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.PreLoginEvent;
import com.velocitypowered.api.event.connection.PreLoginEvent.PreLoginComponentResult;
import net.kyori.adventure.text.Component;
import org.slf4j.Logger;

/**
 * Decides, for each connecting Java player, whether the proxy should perform an
 * online-mode (premium) login.
 *
 * <p>The handler is intentionally a plain blocking {@code void} listener: for a
 * {@link PreLoginEvent} Velocity waits for the handler before continuing the
 * login, which is exactly what we need (the decision must be made before the
 * handshake proceeds). This also keeps the plugin compatible across Velocity 3
 * and 4, whose async-return APIs differ.
 */
public final class PreLoginListener {

    private final Logger logger;
    private final QuickLoginConfig config;
    private final MojangApiService mojang;

    public PreLoginListener(Logger logger, QuickLoginConfig config, MojangApiService mojang) {
        this.logger = logger;
        this.config = config;
        this.mojang = mojang;
    }

    @Subscribe
    public void onPreLogin(PreLoginEvent event) {
        if (!config.premiumEnabled()) {
            return;
        }
        // Respect an earlier denial (e.g. a whitelist plugin).
        if (!event.getResult().isAllowed()) {
            return;
        }

        String name = event.getUsername();
        if (config.looksBedrock(name) || !QuickLoginConfig.isValidJavaName(name)) {
            // Bedrock / non-standard name: leave to Floodgate / default handling.
            return;
        }

        MojangApiService.Result result = mojang.lookup(name);
        switch (result) {
            case PREMIUM -> {
                event.setResult(PreLoginComponentResult.forceOnlineMode());
                if (config.debug()) logger.info("'{}' is premium -> forcing online-mode login.", name);
            }
            case CRACKED -> {
                if (config.allowCracked()) {
                    event.setResult(PreLoginComponentResult.forceOfflineMode());
                } else {
                    event.setResult(PreLoginComponentResult.denied(
                            Component.text("This server requires a premium Minecraft account.")));
                }
            }
            case UNKNOWN -> {
                // Transient Mojang failure.
                if (config.allowCracked()) {
                    event.setResult(PreLoginComponentResult.forceOfflineMode());
                    if (config.debug()) logger.warn("Mojang lookup for '{}' failed; allowing as offline.", name);
                } else {
                    event.setResult(PreLoginComponentResult.denied(
                            Component.text("Could not verify your account. Please try again shortly.")));
                }
            }
        }
    }
}
