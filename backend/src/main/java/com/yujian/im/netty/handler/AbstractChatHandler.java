package com.yujian.im.netty.handler;

import com.yujian.im.message.MessageService;
import com.yujian.im.netty.ImResponses;
import com.yujian.im.protocol.BodyCodec;
import com.yujian.im.protocol.Command;
import com.yujian.im.protocol.ImMessage;
import com.yujian.im.protocol.dto.ChatReq;
import com.yujian.im.protocol.dto.MessageVO;
import com.yujian.im.session.SessionRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.MeterRegistry;
import io.netty.channel.ChannelHandlerContext;

/**
 * 单聊 / 群聊共用的处理逻辑。
 *
 * <p><b>单聊和群聊在服务端其实是同一件事：</b>都是"往一个会话里追加一条消息"，
 * 差别只在"该投给谁"——单聊投给对方，群聊投给全体成员，而这个差异
 * 已经被 {@code PushService} 按会话成员表统一处理掉了。
 * 所以这里只有一个 {@code MessageService.send}，没有 if (single) ... else ...。
 * 面试时这是个好讲的点：<b>不要把"业务形态"和"投递范围"耦合在一起</b>，
 * 前者是展示层，后者是数据模型。
 *
 * <p>子类只负责指定"我是哪个命令、回哪个响应"。
 */
public abstract class AbstractChatHandler implements CommandHandler {

    protected final MessageService messageService;
    protected final SessionRegistry sessionRegistry;
    private final Timer sendTimer;

    protected AbstractChatHandler(MessageService messageService,
                                  SessionRegistry sessionRegistry,
                                  MeterRegistry meterRegistry,
                                  String timerName) {
        this.messageService = messageService;
        this.sessionRegistry = sessionRegistry;
        this.sendTimer = Timer.builder(timerName)
                .description("消息发送端到端耗时（含落库与推送）")
                .publishPercentiles(0.5, 0.95, 0.99)
                .register(meterRegistry);
    }

    /** 请求命令字。 */
    protected abstract Command requestCommand();

    /** 响应命令字。 */
    protected abstract Command responseCommand();

    @Override
    public final Command command() {
        return requestCommand();
    }

    @Override
    public void handle(ChannelHandlerContext ctx, ImMessage msg) {
        Long fromUserId = ctx.channel().attr(SessionRegistry.USER_ID).get();
        ChatReq req = BodyCodec.fromJson(msg.getBody(), ChatReq.class);

        // 计时包住整个 send：落库 + 推送都算在内，这才是客户端感知的耗时。
        // send 是一个带事务的代理方法，返回时事务已经提交，
        // 所以紧接着的 push 一定能被接收方"立刻回拉"到（这是顺序上的硬约束，不能调换）。
        MessageVO vo = sendTimer.record(() -> messageService.send(fromUserId, req));
        messageService.push(vo, fromUserId);

        ImResponses.write(ctx.channel(), msg.getRequestId(), responseCommand(),
                (byte) 0, BodyCodec.toJson(vo));
    }
}
