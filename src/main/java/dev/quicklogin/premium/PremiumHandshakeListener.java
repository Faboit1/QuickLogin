package dev.quicklogin.premium;

import com.github.retrooper.packetevents.event.PacketListenerAbstract;
import com.github.retrooper.packetevents.event.PacketListenerPriority;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.netty.channel.ChannelHelper;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.protocol.player.ClientVersion;
import com.github.retrooper.packetevents.protocol.player.User;
import com.github.retrooper.packetevents.wrapper.login.client.WrapperLoginClientEncryptionResponse;
import com.github.retrooper.packetevents.wrapper.login.client.WrapperLoginClientLoginStart;
import com.github.retrooper.packetevents.wrapper.login.server.WrapperLoginServerEncryptionRequest;
import dev.quicklogin.mojang.MojangApiService;
import io.netty.channel.ChannelPipeline;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.net.InetSocketAddress;
import java.security.GeneralSecurityException;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.logging.Logger;
import java.util.regex.Pattern;

/**
 * Intercepts {@code LOGIN_START} / {@code ENCRYPTION_RESPONSE} to run the Mojang
 * premium handshake for paid accounts — exactly how an online-mode server (and
 * AuthMe's own premium feature) verifies identity, but driven by a direct Mojang
 * name lookup so <b>every</b> premium account is verified automatically (no
 * {@code /premium} opt-in).
 *
 * <p>On success the name is recorded in {@link PremiumVerifier}; QuickLogin then
 * approves it through AuthMe's pre-join dialog and auto-registers/auto-logs it in.
 * Cracked players and unverifiable sessions pass straight through to AuthMe's
 * normal flow (no kick).
 *
 * <p>Structure adapted from AuthMeReloaded's {@code PremiumVerificationPacketListener}.
 */
public final class PremiumHandshakeListener extends PacketListenerAbstract {

    private static final Pattern VALID_USERNAME = Pattern.compile("^[a-zA-Z0-9_]{3,16}$");

    private final PremiumVerifier verifier;
    private final MojangApiService mojang;
    private final ExecutorService executor;
    private final Logger logger;
    private final boolean debug;

    public PremiumHandshakeListener(PremiumVerifier verifier, MojangApiService mojang,
                                    ExecutorService executor, Logger logger, boolean debug) {
        super(PacketListenerPriority.LOW);
        this.verifier = verifier;
        this.mojang = mojang;
        this.executor = executor;
        this.logger = logger;
        this.debug = debug;
    }

    @Override
    public void onPacketReceive(PacketReceiveEvent event) {
        if (event.getPacketType() == PacketType.Login.Client.LOGIN_START) {
            handleLoginStart(event);
        } else if (event.getPacketType() == PacketType.Login.Client.ENCRYPTION_RESPONSE) {
            handleEncryptionResponse(event);
        }
    }

    private void handleLoginStart(PacketReceiveEvent event) {
        WrapperLoginClientLoginStart wrapper = new WrapperLoginClientLoginStart(event);
        String username = wrapper.getUsername();
        if (username == null || !VALID_USERNAME.matcher(username).matches()) {
            return; // Bedrock / invalid names: leave for Floodgate or vanilla.
        }

        User user = event.getUser();
        ClientVersion clientVersion = user.getClientVersion();
        String connectionKey = connectionKey(user);
        UUID playerUuid = wrapper.getPlayerUUID().orElse(null);

        // Take over this login while we decide (off the event loop for the HTTP lookup).
        event.setCancelled(true);

        executor.execute(() -> {
            boolean premium = mojang.lookup(username) == MojangApiService.Result.PREMIUM;
            if (premium) {
                if (debug) logger.info("[QuickLogin] '" + username + "' is premium; requesting Mojang verification.");
                byte[] verifyToken = verifier.startVerification(connectionKey, username, playerUuid);
                WrapperLoginServerEncryptionRequest encReq = new WrapperLoginServerEncryptionRequest(
                        "", verifier.getPublicKey(), verifyToken, true);
                user.sendPacket(encReq);
            } else {
                // Cracked / unknown: resume normal (offline) login untouched.
                resumeLogin(user, username, clientVersion, playerUuid);
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
        UUID playerUuid = verifier.getPendingPlayerUuid(connectionKey);
        ClientVersion clientVersion = user.getClientVersion();

        WrapperLoginClientEncryptionResponse wrapper = new WrapperLoginClientEncryptionResponse(event);
        Optional<byte[]> encVerifyTokenOpt = wrapper.getEncryptedVerifyToken();

        event.setCancelled(true);

        if (encVerifyTokenOpt.isEmpty()) {
            // Signed-nonce variant (we sent a plain token): give up premium, resume normally.
            verifier.cleanupPending(connectionKey);
            resumeLogin(user, username, clientVersion, playerUuid);
            return;
        }

        byte[] encSharedSecret = wrapper.getEncryptedSharedSecret().clone();
        byte[] encVerifyToken = encVerifyTokenOpt.get().clone();

        // RSA-decrypt + install AES ciphers synchronously (we're on the event loop). The client
        // is already encrypting after ENCRYPTION_RESPONSE, so this must happen before any further
        // packet arrives.
        byte[] sharedSecret;
        try {
            sharedSecret = verifier.decryptData(encSharedSecret);
        } catch (GeneralSecurityException e) {
            logger.warning("[QuickLogin] RSA decryption failed for '" + username + "': " + e.getMessage());
            verifier.cleanupPending(connectionKey);
            resumeLogin(user, username, clientVersion, playerUuid);
            return;
        }
        enableChannelEncryption(user.getChannel(), sharedSecret);

        verifier.completeVerification(connectionKey, sharedSecret, encVerifyToken)
                .thenAccept(maybeUuid -> {
                    if (maybeUuid.isPresent()) {
                        verifier.storeVerified(username, maybeUuid.get());
                        if (debug) logger.info("[QuickLogin] Verified premium session for '" + username + "'.");
                    } else if (debug) {
                        logger.info("[QuickLogin] Premium session NOT verified for '" + username
                                + "' (expired/invalid); resuming normally.");
                    }
                    resumeLogin(user, username, clientVersion, playerUuid);
                })
                .exceptionally(ex -> {
                    logger.warning("[QuickLogin] Premium verification error for '" + username + "': " + ex);
                    resumeLogin(user, username, clientVersion, playerUuid);
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

    private void resumeLogin(User user, String username, ClientVersion clientVersion, UUID playerUuid) {
        WrapperLoginClientLoginStart resume =
                new WrapperLoginClientLoginStart(clientVersion, username, null, playerUuid);
        user.receivePacketSilently(resume);
    }

    private static String connectionKey(User user) {
        InetSocketAddress addr = user.getAddress();
        return addr.getAddress().getHostAddress() + ":" + addr.getPort();
    }
}
