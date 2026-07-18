package dev.quicklogin.login;

import com.comphenix.protocol.injector.temporary.TemporaryPlayerFactory;
import com.comphenix.protocol.reflect.FuzzyReflection;
import com.comphenix.protocol.reflect.accessors.Accessors;
import com.comphenix.protocol.reflect.accessors.MethodAccessor;
import io.netty.channel.Channel;
import org.bukkit.entity.Player;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;

/**
 * Bridges ProtocolLib's login-phase (temporary) player to the underlying netty
 * {@link Channel}, and installs the login cipher on it.
 */
public final class ChannelAccess {

    private static final String CIPHER_HANDLER = "quicklogin_cipher";
    private static volatile MethodAccessor injectorAccessor;

    private ChannelAccess() {
    }

    /**
     * Resolve the netty channel backing a temporary login player, using the
     * same ProtocolLib entry points FastLogin relies on.
     *
     * @return the channel, or {@code null} if it could not be resolved
     */
    public static Channel getChannel(Player player) {
        try {
            MethodAccessor accessor = injectorAccessor;
            if (accessor == null) {
                accessor = Accessors.getMethodAccessorOrNull(
                        TemporaryPlayerFactory.class, "getInjectorFromPlayer", Player.class);
                injectorAccessor = accessor;
            }
            if (accessor == null) {
                return null;
            }
            Object injector = accessor.invoke(null, player);
            if (injector == null) {
                return null;
            }
            return (Channel) FuzzyReflection.getFieldValue(injector, Channel.class, true);
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * Install AES/CFB8 encryption on the channel using the negotiated shared
     * secret, then run {@code afterInstall} in the same event-loop task. Doing
     * both on the event loop keeps the cipher installation ordered before the
     * follow-up (re-injecting the login-start packet) and relative to in-flight
     * reads.
     */
    public static void enableEncryption(Channel channel, SecretKey sharedSecret, Runnable afterInstall) {
        Cipher decrypt = EncryptionUtil.newDecryptCipher(sharedSecret);
        Cipher encrypt = EncryptionUtil.newEncryptCipher(sharedSecret);
        Runnable task = () -> {
            if (channel.pipeline().get(CIPHER_HANDLER) == null) {
                channel.pipeline().addFirst(CIPHER_HANDLER, new CipherCodec(decrypt, encrypt));
            }
            if (afterInstall != null) {
                afterInstall.run();
            }
        };
        if (channel.eventLoop().inEventLoop()) {
            task.run();
        } else {
            channel.eventLoop().execute(task);
        }
    }
}
