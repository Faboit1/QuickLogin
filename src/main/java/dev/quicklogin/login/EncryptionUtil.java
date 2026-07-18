package dev.quicklogin.login;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.util.Arrays;

/**
 * Crypto helpers that reproduce the vanilla Minecraft login-encryption
 * handshake. This is the same scheme an online-mode server uses; QuickLogin
 * performs it manually so a premium client authenticates against Mojang even
 * though the backend server itself runs in offline mode.
 */
public final class EncryptionUtil {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final String KEY_TRANSFORMATION = "RSA/ECB/PKCS1Padding";
    private static final String STREAM_TRANSFORMATION = "AES/CFB8/NoPadding";

    private EncryptionUtil() {
    }

    /** Generate the server's RSA key pair (1024-bit, as vanilla does). */
    public static KeyPair generateKeyPair() {
        try {
            KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
            gen.initialize(1024);
            return gen.generateKeyPair();
        } catch (Exception e) {
            throw new IllegalStateException("Unable to generate RSA key pair", e);
        }
    }

    /** Fresh 4-byte verify token sent inside the encryption request. */
    public static byte[] generateVerifyToken() {
        byte[] token = new byte[4];
        RANDOM.nextBytes(token);
        return token;
    }

    /** Decrypt an RSA-encrypted blob (shared secret or verify token). */
    public static byte[] decryptRsa(PrivateKey privateKey, byte[] data) throws Exception {
        Cipher cipher = Cipher.getInstance(KEY_TRANSFORMATION);
        cipher.init(Cipher.DECRYPT_MODE, privateKey);
        return cipher.doFinal(data);
    }

    /** Recover the AES shared secret from its RSA-encrypted form. */
    public static SecretKey decryptSharedKey(PrivateKey privateKey, byte[] encryptedSharedKey) throws Exception {
        return new SecretKeySpec(decryptRsa(privateKey, encryptedSharedKey), "AES");
    }

    public static boolean verifyToken(PrivateKey privateKey, byte[] expected, byte[] encryptedToken) {
        try {
            byte[] actual = decryptRsa(privateKey, encryptedToken);
            return Arrays.equals(expected, actual);
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Compute the Mojang server-id hash for {@code hasJoined}. Uses the vanilla
     * quirk of a signed hex representation via {@link BigInteger}.
     */
    public static String serverHash(String serverId, byte[] sharedSecret, PublicKey publicKey) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-1");
            digest.update(serverId.getBytes(StandardCharsets.ISO_8859_1));
            digest.update(sharedSecret);
            digest.update(publicKey.getEncoded());
            return new BigInteger(digest.digest()).toString(16);
        } catch (Exception e) {
            throw new IllegalStateException("Unable to compute server hash", e);
        }
    }

    public static Cipher newDecryptCipher(SecretKey key) {
        return newCipher(Cipher.DECRYPT_MODE, key);
    }

    public static Cipher newEncryptCipher(SecretKey key) {
        return newCipher(Cipher.ENCRYPT_MODE, key);
    }

    private static Cipher newCipher(int mode, SecretKey key) {
        try {
            Cipher cipher = Cipher.getInstance(STREAM_TRANSFORMATION);
            cipher.init(mode, key, new IvParameterSpec(key.getEncoded()));
            return cipher;
        } catch (Exception e) {
            throw new IllegalStateException("Unable to create AES/CFB8 cipher", e);
        }
    }
}
