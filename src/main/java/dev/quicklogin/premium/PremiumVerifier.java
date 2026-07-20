package dev.quicklogin.premium;

import dev.quicklogin.mojang.MojangApiService;

import javax.crypto.Cipher;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;

/**
 * Performs cryptographic premium session verification, the same way an online-
 * mode server (and AuthMe's own premium feature) does: a per-server RSA key
 * pair drives an EncryptionRequest / EncryptionResponse handshake so the backend
 * can verify premium identity via Mojang's {@code hasJoined} endpoint.
 *
 * <p>Adapted from AuthMeReloaded's {@code PremiumLoginVerifier}.
 */
public final class PremiumVerifier {

    private static final long VERIFIED_TTL_MS = 120_000L;
    private static final long PENDING_TTL_MS = 30_000L;

    private final MojangApiService mojang;
    private final ExecutorService executor;
    private final KeyPair rsaKeyPair;
    private final SecureRandom secureRandom = new SecureRandom();

    /** Keyed by connection address (ip:port). */
    private final ConcurrentHashMap<String, Pending> pending = new ConcurrentHashMap<>();
    /** Keyed by lower-cased username; entries expire after {@link #VERIFIED_TTL_MS}. */
    private final ConcurrentHashMap<String, Verified> verified = new ConcurrentHashMap<>();

    public PremiumVerifier(MojangApiService mojang, ExecutorService executor) {
        this.mojang = mojang;
        this.executor = executor;
        try {
            KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
            gen.initialize(1024, secureRandom);
            this.rsaKeyPair = gen.generateKeyPair();
        } catch (Exception e) {
            throw new IllegalStateException("Unable to generate RSA key pair", e);
        }
    }

    public PublicKey getPublicKey() {
        return rsaKeyPair.getPublic();
    }

    /** Begin a verification handshake; returns the 4-byte verify token. */
    public byte[] startVerification(String connectionKey, String username, UUID playerUuid) {
        evictStalePending();
        byte[] verifyToken = new byte[4];
        secureRandom.nextBytes(verifyToken);
        pending.put(connectionKey, new Pending(username, playerUuid, verifyToken, System.currentTimeMillis()));
        return verifyToken;
    }

    private void evictStalePending() {
        long now = System.currentTimeMillis();
        pending.entrySet().removeIf(e -> now - e.getValue().startedAt > PENDING_TTL_MS);
    }

    public boolean hasPending(String connectionKey) {
        return pending.containsKey(connectionKey);
    }

    public String getPendingUsername(String connectionKey) {
        Pending p = pending.get(connectionKey);
        return p != null ? p.username : null;
    }

    public UUID getPendingPlayerUuid(String connectionKey) {
        Pending p = pending.get(connectionKey);
        return p != null ? p.playerUuid : null;
    }

    public void cleanupPending(String connectionKey) {
        pending.remove(connectionKey);
    }

    /** RSA-decrypt the shared secret (must run on the event loop, before installing ciphers). */
    public byte[] decryptData(byte[] encrypted) throws GeneralSecurityException {
        Cipher cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding");
        cipher.init(Cipher.DECRYPT_MODE, rsaKeyPair.getPrivate());
        return cipher.doFinal(encrypted);
    }

    /**
     * Verify the token and call Mojang {@code hasJoined} asynchronously.
     *
     * @param sharedSecret already RSA-decrypted AES shared secret
     */
    public CompletableFuture<Optional<UUID>> completeVerification(
            String connectionKey, byte[] sharedSecret, byte[] encVerifyToken) {
        Pending p = pending.remove(connectionKey);
        if (p == null) {
            return CompletableFuture.completedFuture(Optional.empty());
        }
        return CompletableFuture.supplyAsync(() -> {
            try {
                byte[] decryptedToken = decryptData(encVerifyToken);
                if (!Arrays.equals(decryptedToken, p.verifyToken)) {
                    return Optional.<UUID>empty();
                }
                String serverHash = computeServerHash(sharedSecret);
                return mojang.hasJoined(p.username, serverHash);
            } catch (Exception e) {
                return Optional.<UUID>empty();
            }
        }, executor);
    }

    public void storeVerified(String username, UUID mojangUuid) {
        verified.put(username.toLowerCase(Locale.ROOT), new Verified(mojangUuid, System.currentTimeMillis()));
    }

    /** @return the verified Mojang UUID for the name, or null if none/expired. */
    public UUID getVerifiedUuid(String username) {
        String key = username.toLowerCase(Locale.ROOT);
        Verified v = verified.get(key);
        if (v == null) {
            return null;
        }
        if (System.currentTimeMillis() - v.verifiedAt > VERIFIED_TTL_MS) {
            verified.remove(key);
            return null;
        }
        return v.mojangUuid;
    }

    public boolean isVerified(String username) {
        return getVerifiedUuid(username) != null;
    }

    private String computeServerHash(byte[] sharedSecret) throws GeneralSecurityException {
        MessageDigest sha1 = MessageDigest.getInstance("SHA-1");
        sha1.update("".getBytes(StandardCharsets.ISO_8859_1)); // empty server id
        sha1.update(sharedSecret);
        sha1.update(rsaKeyPair.getPublic().getEncoded());
        return new BigInteger(sha1.digest()).toString(16);
    }

    private record Pending(String username, UUID playerUuid, byte[] verifyToken, long startedAt) {
    }

    private record Verified(UUID mojangUuid, long verifiedAt) {
    }
}
