package dev.quicklogin.login;

import com.comphenix.protocol.PacketType;
import com.comphenix.protocol.ProtocolManager;
import com.comphenix.protocol.events.PacketContainer;
import com.comphenix.protocol.events.PacketEvent;
import com.comphenix.protocol.wrappers.WrappedChatComponent;
import com.comphenix.protocol.wrappers.WrappedGameProfile;
import dev.quicklogin.config.QuickLoginConfig;
import dev.quicklogin.mojang.MojangApiService;
import io.netty.channel.Channel;
import org.bukkit.entity.Player;

import javax.crypto.SecretKey;
import java.security.KeyPair;
import java.security.PublicKey;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;
import java.util.regex.Pattern;

/**
 * Drives the premium (paid Minecraft account) verification handshake for a
 * standalone offline-mode server: it hijacks the login-start packet, performs
 * the Mojang encryption exchange itself, verifies the session against Mojang
 * and, on success, enables channel encryption and lets the vanilla offline
 * login proceed. On failure the client is kicked with the same message a
 * cracked client sees on an online-mode server.
 */
public final class PremiumLoginManager {

    /** Standard Java username: 3-16 of [A-Za-z0-9_]. Excludes Bedrock/prefixed names. */
    private static final Pattern JAVA_NAME = Pattern.compile("^[A-Za-z0-9_]{3,16}$");

    /** Verified-premium marks live at most this long before being purged. */
    private static final long MARK_TTL_MS = 60_000L;

    private final Logger logger;
    private final QuickLoginConfig config;
    private final ProtocolManager protocol;
    private final MojangApiService mojang;
    private final KeyPair keyPair;
    private final ExecutorService workers;

    private final ConcurrentHashMap<Channel, LoginSession> sessions = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, PremiumMark> verified = new ConcurrentHashMap<>();

    public PremiumLoginManager(Logger logger, QuickLoginConfig config,
                               ProtocolManager protocol, MojangApiService mojang) {
        this.logger = logger;
        this.config = config;
        this.protocol = protocol;
        this.mojang = mojang;
        this.keyPair = EncryptionUtil.generateKeyPair();
        this.workers = Executors.newFixedThreadPool(config.workerThreads(), namedThreads());
    }

    private static ThreadFactory namedThreads() {
        AtomicInteger idx = new AtomicInteger();
        return r -> {
            Thread t = new Thread(r, "QuickLogin-Worker-" + idx.incrementAndGet());
            t.setDaemon(true);
            return t;
        };
    }

    /** A verified premium account waiting to be auto-logged-in at join time. */
    public record PremiumMark(String username, UUID onlineUuid, long createdAt) {
    }

    // ---------------------------------------------------------------------
    //  Packet entry points (called from the ProtocolLib listener, sync)
    // ---------------------------------------------------------------------

    public void onLoginStart(PacketEvent event) {
        Player player = event.getPlayer();
        String name = readLoginName(event.getPacket());
        if (name == null || !JAVA_NAME.matcher(name).matches()) {
            // Bedrock / invalid name: leave the packet untouched for vanilla or
            // Floodgate to handle. We never interfere with those logins.
            return;
        }

        Channel channel = ChannelAccess.getChannel(player);
        if (channel == null) {
            if (config.debug()) logger.warning("Could not resolve channel for '" + name + "'; skipping premium check.");
            return;
        }

        // Take over the login: prevent vanilla from starting the offline login.
        event.setCancelled(true);
        workers.execute(() -> nameCheck(player, channel, name));
    }

    public void onEncryptionResponse(PacketEvent event) {
        Player player = event.getPlayer();
        Channel channel = ChannelAccess.getChannel(player);

        // An encryption response can only exist because we sent a request, so
        // we always take ownership of it.
        event.setCancelled(true);

        LoginSession session = channel == null ? null : sessions.get(channel);
        if (session == null) {
            if (config.debug()) logger.warning("Encryption response without an active session; ignoring.");
            return;
        }

        byte[] sharedSecret = event.getPacket().getByteArrays().readSafely(0);
        byte[] token = event.getPacket().getByteArrays().readSafely(1);
        if (sharedSecret == null) {
            kick(player, channel, config.invalidSessionMessage());
            sessions.remove(channel);
            return;
        }
        workers.execute(() -> verify(player, channel, session, sharedSecret, token));
    }

    // ---------------------------------------------------------------------
    //  Async handshake steps (worker threads)
    // ---------------------------------------------------------------------

    private void nameCheck(Player player, Channel channel, String name) {
        try {
            MojangApiService.PremiumResult result = mojang.lookupPremium(name);
            boolean premium = result != null && result.premium();

            if (result == null && !config.allowCracked()) {
                // Transient Mojang error and cracked logins are disabled: fail closed.
                kick(player, channel, config.invalidSessionMessage());
                return;
            }

            if (!premium) {
                if (config.allowCracked()) {
                    if (config.debug()) logger.info("'" + name + "' is not premium; continuing as offline player.");
                    receiveFakeStart(player, name);
                } else {
                    kick(player, channel, config.invalidSessionMessage());
                }
                return;
            }

            byte[] verifyToken = EncryptionUtil.generateVerifyToken();
            sessions.put(channel, new LoginSession(name, verifyToken));
            if (!sendEncryptionRequest(player, verifyToken)) {
                sessions.remove(channel);
                // Could not start encryption; fall back to cracked if permitted.
                if (config.allowCracked()) {
                    receiveFakeStart(player, name);
                } else {
                    kick(player, channel, config.invalidSessionMessage());
                }
            }
        } catch (Throwable t) {
            logger.warning("Name check failed for '" + name + "': " + t);
            sessions.remove(channel);
            kick(player, channel, config.invalidSessionMessage());
        }
    }

    private void verify(Player player, Channel channel, LoginSession session,
                        byte[] encryptedSecret, byte[] encryptedToken) {
        try {
            if (encryptedToken != null
                    && !EncryptionUtil.verifyToken(keyPair.getPrivate(), session.verifyToken(), encryptedToken)) {
                if (config.debug()) logger.warning("Verify token mismatch for '" + session.username() + "'.");
                fail(player, channel, session);
                return;
            }

            SecretKey secret = EncryptionUtil.decryptSharedKey(keyPair.getPrivate(), encryptedSecret);
            String hash = EncryptionUtil.serverHash("", secret.getEncoded(), keyPair.getPublic());
            UUID onlineUuid = mojang.hasJoined(session.username(), hash);

            if (onlineUuid == null) {
                // Invalid / expired session. Kick with the vanilla-style prompt.
                if (config.debug()) logger.info("Session verification failed for '" + session.username() + "'.");
                fail(player, channel, session);
                return;
            }

            verified.put(session.usernameLower(),
                    new PremiumMark(session.username(), onlineUuid, System.currentTimeMillis()));

            // Install encryption, then re-inject the login-start so vanilla runs
            // the (now trusted) offline login on an encrypted channel.
            ChannelAccess.enableEncryption(channel, secret, () -> receiveFakeStart(player, session.username()));

            if (config.debug()) logger.info("Premium session verified for '" + session.username() + "'.");
        } catch (Throwable t) {
            logger.warning("Verification failed for '" + session.username() + "': " + t);
            fail(player, channel, session);
        } finally {
            sessions.remove(channel);
        }
    }

    private void fail(Player player, Channel channel, LoginSession session) {
        if (!config.kickOnInvalidSession() && config.allowCracked()) {
            // Explicitly configured to let unverifiable premium names in as cracked.
            receiveFakeStart(player, session.username());
        } else {
            kick(player, channel, config.invalidSessionMessage());
        }
    }

    // ---------------------------------------------------------------------
    //  Packet building helpers
    // ---------------------------------------------------------------------

    private boolean sendEncryptionRequest(Player player, byte[] verifyToken) {
        try {
            PacketContainer packet = new PacketContainer(PacketType.Login.Server.ENCRYPTION_BEGIN);
            packet.getStrings().write(0, ""); // empty server id
            PublicKey publicKey = keyPair.getPublic();

            if (packet.getSpecificModifier(PublicKey.class).size() > 0) {
                packet.getSpecificModifier(PublicKey.class).write(0, publicKey);
                packet.getByteArrays().write(0, verifyToken);
            } else {
                packet.getByteArrays().write(0, publicKey.getEncoded());
                packet.getByteArrays().write(1, verifyToken);
            }
            // 1.20.5+: a boolean telling the client it should authenticate. No-op otherwise.
            packet.getBooleans().writeSafely(0, true);

            protocol.sendServerPacket(player, packet, false);
            return true;
        } catch (Throwable t) {
            logger.warning("Failed to send encryption request: " + t);
            return false;
        }
    }

    private void receiveFakeStart(Player player, String name) {
        try {
            PacketContainer start = new PacketContainer(PacketType.Login.Client.START);
            if (start.getStrings().size() > 0) {
                start.getStrings().write(0, name);
            } else if (start.getGameProfiles().size() > 0) {
                start.getGameProfiles().write(0, new WrappedGameProfile((UUID) null, name));
            }
            // false => bypass listeners, so we don't recurse into onLoginStart.
            protocol.receiveClientPacket(player, start, false);
        } catch (Throwable t) {
            logger.warning("Failed to re-inject login start for '" + name + "': " + t);
        }
    }

    private void kick(Player player, Channel channel, String message) {
        try {
            PacketContainer disconnect = new PacketContainer(PacketType.Login.Server.DISCONNECT);
            disconnect.getChatComponents().write(0, WrappedChatComponent.fromText(message));
            protocol.sendServerPacket(player, disconnect, false);
        } catch (Throwable t) {
            if (config.debug()) logger.warning("Failed to send disconnect packet: " + t);
        } finally {
            if (channel != null && channel.isOpen()) {
                // Give the disconnect packet time to flush before closing.
                // Block lambda (not a method ref) to bind the Runnable overload of schedule().
                channel.eventLoop().schedule(() -> { channel.close(); }, 250, TimeUnit.MILLISECONDS);
            }
        }
    }

    // ---------------------------------------------------------------------
    //  Join-time consumption + lifecycle
    // ---------------------------------------------------------------------

    /** Consume (and remove) the verified-premium mark for a joining player. */
    public PremiumMark consumeVerified(String usernameLower) {
        purgeStaleMarks();
        return verified.remove(usernameLower);
    }

    private void purgeStaleMarks() {
        long now = System.currentTimeMillis();
        verified.values().removeIf(m -> now - m.createdAt() > MARK_TTL_MS);
    }

    public void shutdown() {
        workers.shutdownNow();
        sessions.clear();
        verified.clear();
    }

    private static String readLoginName(PacketContainer packet) {
        try {
            if (packet.getGameProfiles().size() > 0) {
                WrappedGameProfile profile = packet.getGameProfiles().readSafely(0);
                if (profile != null && profile.getName() != null) {
                    return profile.getName();
                }
            }
            if (packet.getStrings().size() > 0) {
                return packet.getStrings().readSafely(0);
            }
        } catch (Throwable ignored) {
        }
        return null;
    }
}
