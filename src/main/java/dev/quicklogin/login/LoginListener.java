package dev.quicklogin.login;

import com.comphenix.protocol.PacketType;
import com.comphenix.protocol.events.ListenerPriority;
import com.comphenix.protocol.events.PacketAdapter;
import com.comphenix.protocol.events.PacketEvent;
import org.bukkit.plugin.Plugin;

/**
 * ProtocolLib listener for the two login-phase packets QuickLogin needs to see:
 * the client's login-start and its encryption response.
 */
public final class LoginListener extends PacketAdapter {

    private final PremiumLoginManager manager;

    public LoginListener(Plugin plugin, PremiumLoginManager manager) {
        super(plugin, ListenerPriority.LOWEST,
                PacketType.Login.Client.START,
                PacketType.Login.Client.ENCRYPTION_BEGIN);
        this.manager = manager;
    }

    @Override
    public void onPacketReceiving(PacketEvent event) {
        PacketType type = event.getPacketType();
        if (type == PacketType.Login.Client.START) {
            manager.onLoginStart(event);
        } else if (type == PacketType.Login.Client.ENCRYPTION_BEGIN) {
            manager.onEncryptionResponse(event);
        }
    }
}
