package dev.quicklogin.premium;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.MessageToByteEncoder;

import javax.crypto.Cipher;

/**
 * Netty outbound handler that AES/CFB8-encrypts the byte stream to the client.
 * Inserted before the length-prepender so the length varint is inside the
 * encrypted stream, matching the Minecraft protocol.
 */
final class AesCfb8Encoder extends MessageToByteEncoder<ByteBuf> {

    private final Cipher cipher;

    AesCfb8Encoder(Cipher cipher) {
        this.cipher = cipher;
    }

    @Override
    protected void encode(ChannelHandlerContext ctx, ByteBuf in, ByteBuf out) {
        byte[] input = new byte[in.readableBytes()];
        in.readBytes(input);
        out.writeBytes(cipher.update(input));
    }
}
