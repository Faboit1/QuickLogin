package dev.quicklogin.listener;

import dev.quicklogin.auth.AutoLoginService;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;

/**
 * Triggers auto-login once a verified premium or Bedrock player is fully on the
 * server. Runs at MONITOR so AuthMe's own join handling happens first.
 */
public final class JoinListener implements Listener {

    private final AutoLoginService service;

    public JoinListener(AutoLoginService service) {
        this.service = service;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        service.onJoin(event.getPlayer());
    }
}
