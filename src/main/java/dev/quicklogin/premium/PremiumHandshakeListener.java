package dev.quicklogin.premium;

import com.github.retrooper.packetevents.event.PacketListenerAbstract;
import com.github.retrooper.packetevents.event.PacketListenerPriority;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.netty.channel.ChannelHelper;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.protocol.player.User;
import com.github.retrooper.packetevents.wrapper.handshaking.client.WrapperHandshakingClientHandshake;
import com.github.retrooper.packetevents.wrapper.login.client.WrapperLoginClientEncryptionResponse;
import com.github.retrooper.packetevents.wrapper.login.client.WrapperLoginClientLoginStart;
import com.github.retrooper.packetevents.wrapper.login.server.WrapperLoginServerEncryptionRequest;
import dev.quicklogin.auth.FloodgateHook;
import dev.quicklogin.config.QuickLoginConfig;
import dev.quicklogin.mojang.MojangApiService;
import io.netty.channel.ChannelPipeline;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.net.InetSocketAddress;
import java.security.GeneralSecurityException;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.logging.Logger;
import java.util.regex.Pattern;

/**
 * Intercepts the Minecraft login sequence to perform Mojang premium verification
 * for paid accounts, using a Netty pipeline handler ({@link PremiumLoginHandler})
 * to physically hold the LOGIN_START packet while the async Mojang check runs.
 *
 * <p>On {@code HANDSHAKE} (login intent) a {@link PremiumLoginHandler} is injected
 * before {@code packet_handler} so it can catch the upcoming LOGIN_START.
 * Bedrock/Floodgate connections are detected at HANDSHAKE time (via the {@code \0}
 * separator Geyser injects into the hostname) and skipped entirely — no handler
 * is injected for them.
 *
 * <p>The held-packet approach sidesteps the fact that PacketEvents'
 * {@code event.setCancelled(true)} does not prevent the vanilla
 * {@code ServerLoginPacketListenerImpl} from processing LOGIN_START on Paper 1.21.x.
 */
public final class PremiumHandshakeListener extends PacketListenerAbstract {

    private static final Pattern VALID_USERNAME = Pattern.compile("^[a-zA-Z0-9_]{3,16}$");

    private final PremiumVerifier verifier;
    private final MojangApiService mojang;
    private final ExecutorService executor;
    private final Logger logger;
    private final FloodgateHook floodgate;
    private final QuickLoginConfig config;

    private final ConcurrentHashMap<String, PremiumLoginHandler> handlers = new ConcurrentHashMap<>();

    public PremiumHandshakeListener(PremiumVerifier verifier, MojangApiService mojang,
                                    ExecutorService executor, Logger logger,
                                    FloodgateHook floodgate, QuickLoginConfig config) {
        super(PacketListenerPriority.LOW);
        this.verifier = verifier;
        this.mojang = mojang;
        this.executor = executor;
        this.logger = logger;
        this.floodgate = floodgate;
        this.config = config;
    }

    @Override
    public void onPacketReceive(PacketReceiveEvent event) {
        if (event.getPacketType() == PacketType.Handshaking.Client.HANDSHAKE) {
            handleHandshake(event);
        } else if (event.getPacketType() == PacketType.Login.Client.LOGIN_START) {
            handleLoginStart(event);
        } else if (event.getPacketType() == PacketType.Login.Client.ENCRYPTION_RESPONSE) {
            handleEncryptionResponse(event);
        }
    }

    private void handleHandshake(PacketReceiveEvent event) {
        WrapperHandshakingClientHandshake wrapper = new WrapperHandshakingClientHandshake(event);
        if (wrapper.getIntention() != WrapperHandshakingClientHandshake.ConnectionIntention.LOGIN) {
            return;
        }

        User user = event.getUser();
        String key = connectionKey(user);

        if (isFloodgateHandshake(wrapper)) {
            if (config.debug()) logger.info("[QuickLogin] Detected Floodgate handshake for " + key + "; skipping premium.");
            return;
        }

        try {
            PremiumLoginHandler handler = new PremiumLoginHandler(logger, config.debug());
            ChannelPipeline pipeline = (ChannelPipeline) ChannelHelper.getPipeline(user.getChannel());
            pipeline.addBefore("packet_handler", PremiumLoginHandler.HANDLER_NAME, handler);
            handlers.put(key, handler);
        } catch (Exception e) {
            logger.warning("[QuickLogin] Failed to inject premium handler: " + e.getMessage());
        }
    }

    private boolean isFloodgateHandshake(WrapperHandshakingClientHandshake wrapper) {
        if (floodgate == null || !config.floodgateEnabled()) {
            return false;
        }
        String addr = wrapper.getServerAddress();
        return addr != null && addr.indexOf('\0') >= 0;
    }

    private void handleLoginStart(PacketReceiveEvent event) {
        User user = event.getUser();
        String connectionKey = connectionKey(user);

        if (!handlers.containsKey(connectionKey)) {
            return;
        }

        WrapperLoginClientLoginStart wrapper = new WrapperLoginClientLoginStart(event);
        String username = wrapper.getUsername();
        if (username == null || !VALID_USERNAME.matcher(username).matches()) {
            releaseHandler(user);
            return;
        }

        UUID playerUuid = wrapper.getPlayerUUID().orElse(null);

        executor.execute(() -> {
            boolean premium = mojang.lookup(username) == MojangApiService.Result.PREMIUM;
            if (premium) {
                if (config.debug()) logger.info("[QuickLogin] '" + username + "' is premium; requesting Mojang verification.");
                byte[] verifyToken = verifier.startVerification(connectionKey, username, playerUuid);
                WrapperLoginServerEncryptionRequest encReq = new WrapperLoginServerEncryptionRequest(
                        "", verifier.getPublicKey(), verifyToken, true);
                user.sendPacket(encReq);
            } else {
                if (config.debug()) logger.info("[QuickLogin] '" + username + "' is not premium; releasing to vanilla.");
                releaseHandler(user);
            }
        });
    }

    private void handleEncryptionResponse(PacketReceiveEvent event) {
        User user = event.getUser();
        String connectionKey = connectionKey(user);
        if (!verifier.hasPending(connectionKey)) {
            return;
        }

        String username = verifier.getPendingUsername(connectionKey);

        WrapperLoginClientEncryptionResponse wrapper = new WrapperLoginClientEncryptionResponse(event);
        Optional<byte[]> encVerifyTokenOpt = wrapper.getEncryptedVerifyToken();

        event.setCancelled(true);

        if (encVerifyTokenOpt.isEmpty()) {
            verifier.cleanupPending(connectionKey);
            releaseHandler(user);
            return;
        }

        byte[] encSharedSecret = wrapper.getEncryptedSharedSecret().clone();
        byte[] encVerifyToken = encVerifyTokenOpt.get().clone();

        byte[] sharedSecret;
        try {
            sharedSecret = verifier.decryptData(encSharedSecret);
        } catch (GeneralSecurityException e) {
            logger.warning("[QuickLogin] RSA decryption failed for '" + username + "': " + e.getMessage());
            verifier.cleanupPending(connectionKey);
            releaseHandler(user);
            return;
        }
        enableChannelEncryption(user.getChannel(), sharedSecret);

        verifier.completeVerification(connectionKey, sharedSecret, encVerifyToken)
                .thenAccept(maybeUuid -> {
                    if (maybeUuid.isPresent()) {
                        verifier.storeVerified(username, maybeUuid.get());
                        if (config.debug()) logger.info("[QuickLogin] Verified premium session for '" + username + "'.");
                    } else if (config.debug()) {
                        logger.info("[QuickLogin] Premium session NOT verified for '" + username
                                + "' (expired/invalid); resuming normally.");
                    }
                    releaseHandler(user);
                })
                .exceptionally(ex -> {
                    logger.warning("[QuickLogin] Premium verification error for '" + username + "': " + ex);
                    releaseHandler(user);
                    return null;
                });
    }

    private void enableChannelEncryption(Object channel, byte[] sharedSecret) {
        try {
            SecretKeySpec key = new SecretKeySpec(sharedSecret, "AES");
            IvParameterSpec iv = new IvParameterSpec(sharedSecret);

            Cipher decryptCipher = Cipher.getInstance("AES/CFB8/NoPadding");
            decryptCipher.init(Cipher.DECRYPT_MODE, key, iv);
            Cipher encryptCipher = Cipher.getInstance("AES/CFB8/NoPadding");
            encryptCipher.init(Cipher.ENCRYPT_MODE, key, iv);

            ChannelPipeline pipeline = (ChannelPipeline) ChannelHelper.getPipeline(channel);
            pipeline.addBefore("splitter", "decrypt", new AesCfb8Decoder(decryptCipher));
            pipeline.addBefore("prepender", "encrypt", new AesCfb8Encoder(encryptCipher));
        } catch (Exception e) {
            logger.warning("[QuickLogin] Failed to install AES cipher handlers: " + e.getMessage());
        }
    }

    private void releaseHandler(User user) {
        String key = connectionKey(user);
        PremiumLoginHandler handler = handlers.remove(key);
        if (handler != null) {
            handler.releaseLogin();
        }
    }

    public void cleanup() {
        handlers.values().forEach(PremiumLoginHandler::releaseLogin);
        handlers.clear();
    }

    private static String connectionKey(User user) {
        InetSocketAddress addr = user.getAddress();
        return addr.getAddress().getHostAddress() + ":" + addr.getPort();
    }
}
