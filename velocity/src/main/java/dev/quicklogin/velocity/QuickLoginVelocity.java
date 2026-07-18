package dev.quicklogin.velocity;

import com.google.inject.Inject;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.ProxyServer;
import org.slf4j.Logger;

import java.nio.file.Path;

/**
 * QuickLogin for Velocity: verifies paid Minecraft accounts at the proxy (the
 * only place it can be done on a Velocity network) by forcing online-mode login
 * for premium names. Cracked and Bedrock players fall through as offline.
 *
 * <p>Premium players then arrive at the backend with a real (version-4) Mojang
 * UUID, which the companion backend plugin uses to auto-login them into AuthMe.
 */
@Plugin(
        id = "quicklogin",
        name = "QuickLogin",
        version = "1.0.0",
        description = "Premium Java verification for offline Velocity networks.",
        authors = {"QuickLogin"}
)
public final class QuickLoginVelocity {

    private final ProxyServer server;
    private final Logger logger;
    private final Path dataDirectory;

    private QuickLoginConfig config;
    private MojangApiService mojang;

    @Inject
    public QuickLoginVelocity(ProxyServer server, Logger logger, @DataDirectory Path dataDirectory) {
        this.server = server;
        this.logger = logger;
        this.dataDirectory = dataDirectory;
    }

    @Subscribe
    public void onProxyInitialize(ProxyInitializeEvent event) {
        this.config = QuickLoginConfig.load(dataDirectory, logger);
        this.mojang = new MojangApiService(logger, config.debug(),
                config.requestTimeoutMs(), config.cacheSeconds());

        server.getEventManager().register(this, new PreLoginListener(logger, config, mojang));

        logger.info("QuickLogin (Velocity) enabled. Premium verification: {}",
                config.premiumEnabled() ? "on" : "off");
    }
}
