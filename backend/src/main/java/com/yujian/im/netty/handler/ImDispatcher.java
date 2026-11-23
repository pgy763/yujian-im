package com.yujian.im.netty.handler;

import com.yujian.im.common.BizException;
import com.yujian.im.netty.ImResponses;
import com.yujian.im.protocol.Command;
import com.yujian.im.protocol.ImMessage;
import com.yujian.im.session.SessionRegistry;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.util.AttributeKey;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * 指令分发器 —— 整条长连接的"总入口"。
 *
 * <p>它做四件事，顺序很重要：
 * <ol>
 *   <li><b>鉴权</b>：除登录/心跳外，没绑过身份的连接一律拒掉；</li>
 *   <li><b>限流</b>：单连接令牌桶，防止一个客户端把服务端打满；</li>
 *   <li><b>分发</b>：按命令字找到对应的 {@link CommandHandler}；</li>
 *   <li><b>兜底</b>：任何异常都回一帧 ERROR，绝不让异常冒到 Netty 的
 *       exceptionCaught 直接把连接关掉——那样客户端只会看到"连接断了"，无从排查。</li>
 * </ol>
 *
 * <p><b>为什么继承 {@code SimpleChannelInboundHandler}：</b>
 * 它会自动 release 入站消息。我们解出来的是普通 POJO（不持有 ByteBuf），
 * 但保持这个习惯能避免将来换成 ByteBuf 载体时忘记 release 导致内存泄漏。
 */
@Slf4j
@Component
@ChannelHandler.Sharable
public class ImDispatcher extends SimpleChannelInboundHandler<ImMessage> {

    /** 每条连接自己的令牌桶，挂在 Channel 属性上。 */
    private static final AttributeKey<TokenBucket> BUCKET = AttributeKey.valueOf("bucket");

    private final Map<Command, CommandHandler> handlers = new EnumMap<>(Command.class);
    private final SessionRegistry sessionRegistry;
    private final Counter rejected;
    private final Counter dispatched;
    private final int maxQps;

    public ImDispatcher(List<CommandHandler> handlerList,
                        SessionRegistry sessionRegistry,
                        MeterRegistry meterRegistry,
                        @Value("${im.netty.max-qps-per-channel:200}") int maxQps) {
        for (CommandHandler h : handlerList) {
            handlers.put(h.command(), h);
        }
        this.sessionRegistry = sessionRegistry;
        this.maxQps = maxQps;
        this.rejected = Counter.builder("im_command_total").tag("result", "rejected").register(meterRegistry);
        this.dispatched = Counter.builder("im_command_total").tag("result", "ok").register(meterRegistry);
        log.info("指令分发器加载了 {} 个处理器: {}", handlers.size(), handlers.keySet());
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, ImMessage msg) {
        Command cmd = Command.of(msg.getCommand());
        if (cmd == null) {
            ImResponses.fail(ctx, msg.getRequestId(), 400, "未知命令字: " + msg.getCommand());
            return;
        }

        // ---------- 1. 限流 ----------
        TokenBucket bucket = bucketOf(ctx);
        if (!bucket.tryAcquire()) {
            rejected.increment();
            ImResponses.fail(ctx, msg.getRequestId(), 429, "请求过于频繁，请稍后再试");
            return;
        }

        // ---------- 2. 鉴权 ----------
        boolean authed = Boolean.TRUE.equals(ctx.channel().attr(SessionRegistry.AUTHED).get());
        if (!authed && needAuth(cmd)) {
            ImResponses.fail(ctx, msg.getRequestId(), 401, "请先登录");
            ctx.close();
            return;
        }

        // ---------- 3. 分发 ----------
        CommandHandler handler = handlers.get(cmd);
        if (handler == null) {
            ImResponses.fail(ctx, msg.getRequestId(), 400, "服务端未实现该命令: " + cmd);
            return;
        }
        try {
            handler.handle(ctx, msg);
            dispatched.increment();
        } catch (BizException e) {
            // 业务异常是"预期内"的失败，回一帧错误让客户端提示用户，连接保留
            ImResponses.fail(ctx, msg.getRequestId(), e.getCode(), e.getMessage());
        } catch (Exception e) {
            log.error("处理命令 {} 失败, channel={}", cmd, ctx.channel().id().asShortText(), e);
            ImResponses.fail(ctx, msg.getRequestId(), 500, "服务端异常");
        }
    }

    /** 登录和心跳是仅有的两个允许"匿名"访问的命令。 */
    private boolean needAuth(Command cmd) {
        return cmd != Command.LOGIN_REQ && cmd != Command.HEARTBEAT_REQ;
    }

    private TokenBucket bucketOf(ChannelHandlerContext ctx) {
        TokenBucket b = ctx.channel().attr(BUCKET).get();
        if (b == null) {
            TokenBucket created = new TokenBucket(maxQps);
            b = ctx.channel().attr(BUCKET).setIfAbsent(created);
            if (b == null) {
                b = created;
            }
        }
        return b;
    }

    /**
     * 连接断开时清理。{@link SessionRegistry#unregister} 里做了"最后一条连接才真正下线"的判断，
     * 所以多端用户关掉一个端不会被误判为离线。
     */
    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        boolean fullyOffline = sessionRegistry.unregister(ctx.channel());
        if (fullyOffline) {
            log.debug("连接 {} 关闭，用户已完全离线", ctx.channel().id().asShortText());
        }
        super.channelInactive(ctx);
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        // 解码失败（魔数错、超长帧）会走到这里。这时候字节流已经不可信，只能断开重连。
        log.warn("连接 {} 异常关闭: {}", ctx.channel().id().asShortText(), cause.toString());
        ctx.close();
    }
}
