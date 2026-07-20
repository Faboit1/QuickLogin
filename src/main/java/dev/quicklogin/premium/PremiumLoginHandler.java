package dev.quicklogin.premium;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;

import java.util.logging.Logger;

/**
 * Netty handler injected before {@code packet_handler} to physically hold the
 * NMS LOGIN_START packet while QuickLogin runs its async Mojang premium check.
 *
 * <p>PacketEvents' {@code event.setCancelled(true)} does <b>not</b> prevent
 * the vanilla {@code ServerLoginPacketListenerImpl} from processing the packet
 * on Paper 1.21.x. By holding the raw NMS object in the pipeline we guarantee
 * vanilla never sees it until we explicitly release it.
 */
final class PremiumLoginHandler extends ChannelInboundHandlerAdapter {

    static final String HANDLER_NAME = "quicklogin-premium";

    private final Logger logger;
    private final boolean debug;
    private volatile Object heldPacket;
    private volatile ChannelHandlerContext ctx;
    private volatile boolean released;

    PremiumLoginHandler(Logger logger, boolean debug) {
        this.logger = logger;
        this.debug = debug;
    }

    @Override
    public void handlerAdded(ChannelHandlerContext ctx) {
        this.ctx = ctx;
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        if (!released && heldPacket == null && isLoginStartPacket(msg)) {
            heldPacket = msg;
            if (debug) logger.info("[QuickLogin] Held LOGIN_START in Netty pipeline.");
            return;
        }
        super.channelRead(ctx, msg);
    }

    void releaseLogin() {
        if (released) return;
        released = true;
        ChannelHandlerContext c = this.ctx;
        if (c == null || !c.channel().isActive()) return;

        Object pkt = heldPacket;
        heldPacket = null;

        if (c.channel().eventLoop().inEventLoop()) {
            doRelease(c, pkt);
        } else {
            c.channel().eventLoop().execute(() -> doRelease(c, pkt));
        }
    }

    private void doRelease(ChannelHandlerContext c, Object pkt) {
        try {
            if (pkt != null) {
                c.fireChannelRead(pkt);
                if (debug) logger.info("[QuickLogin] Released LOGIN_START to vanilla handler.");
            }
        } finally {
            removeSelf(c);
        }
    }

    private void removeSelf(ChannelHandlerContext c) {
        try {
            if (c.pipeline().get(HANDLER_NAME) != null) {
                c.pipeline().remove(HANDLER_NAME);
            }
        } catch (Exception ignored) {
        }
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        heldPacket = null;
        super.channelInactive(ctx);
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
        heldPacket = null;
        super.exceptionCaught(ctx, cause);
    }

    private static boolean isLoginStartPacket(Object msg) {
        String name = msg.getClass().getSimpleName();
        return name.contains("Hello") || name.contains("LoginStart")
                || name.contains("ServerboundHelloPacket");
    }
}
