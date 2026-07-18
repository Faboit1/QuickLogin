package dev.quicklogin.login;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelPromise;

import javax.crypto.Cipher;

/**
 * A single duplex handler that reproduces vanilla Minecraft stream encryption:
 * inbound bytes are decrypted, outbound bytes are encrypted, both with an
 * {@code AES/CFB8} cipher that keeps state across chunks.
 *
 * <p>Installed at the very head of the pipeline (via {@code addFirst}) so it is
 * the first handler to touch inbound raw bytes from the socket and the last to
 * touch outbound bytes before the socket. This is mapping-independent: it never
 * references an obfuscated NMS class or pipeline handler name, only the stable
 * netty {@code Channel} and the JCE {@link Cipher}.
 */
public final class CipherCodec extends ChannelDuplexHandler {

    private final Cipher decrypt;
    private final Cipher encrypt;

    public CipherCodec(Cipher decrypt, Cipher encrypt) {
        this.decrypt = decrypt;
        this.encrypt = encrypt;
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        if (msg instanceof ByteBuf in) {
            try {
                out(in, decrypt, ctx).ifPresent(ctx::fireChannelRead);
            } finally {
                in.release();
            }
        } else {
            ctx.fireChannelRead(msg);
        }
    }

    @Override
    public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) throws Exception {
        if (msg instanceof ByteBuf in) {
            ByteBuf result = null;
            try {
                java.util.Optional<ByteBuf> processed = out(in, encrypt, ctx);
                if (processed.isPresent()) {
                    result = processed.get();
                    ctx.write(result, promise);
                } else {
                    promise.setSuccess();
                }
            } finally {
                in.release();
            }
        } else {
            ctx.write(msg, promise);
        }
    }

    /** Run {@code buf} through {@code cipher} and wrap the result, or empty if nothing to do. */
    private static java.util.Optional<ByteBuf> out(ByteBuf buf, Cipher cipher, ChannelHandlerContext ctx) {
        int len = buf.readableBytes();
        if (len == 0) {
            return java.util.Optional.empty();
        }
        byte[] input = new byte[len];
        buf.readBytes(input);
        byte[] output = cipher.update(input);
        if (output == null || output.length == 0) {
            return java.util.Optional.empty();
        }
        return java.util.Optional.of(Unpooled.wrappedBuffer(output));
    }
}
