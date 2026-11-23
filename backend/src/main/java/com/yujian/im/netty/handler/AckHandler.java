package com.yujian.im.netty.handler;

import com.yujian.im.message.MessageService;
import com.yujian.im.netty.ImResponses;
import com.yujian.im.protocol.BodyCodec;
import com.yujian.im.protocol.Command;
import com.yujian.im.protocol.ImMessage;
import com.yujian.im.protocol.dto.AckReq;
import com.yujian.im.protocol.dto.PullOfflineReq;
import com.yujian.im.protocol.dto.MessageVO;
import com.yujian.im.session.SessionRegistry;
import io.netty.channel.ChannelHandlerContext;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * ack 与离线补拉。
 *
 * <p>这两个命令放在一个类里，是因为它们是一对：<b>ack 推进的位点，正是补拉的起点</b>。
 * 分开写容易让人忘了它们的耦合关系，改一边忘了另一边。
 */
@Component
@RequiredArgsConstructor
public class AckHandler implements CommandHandler {

    private final MessageService messageService;

    @Override
    public Command command() {
        return Command.ACK_REQ;
    }

    @Override
    public void handle(ChannelHandlerContext ctx, ImMessage msg) {
        Long userId = ctx.channel().attr(SessionRegistry.USER_ID).get();
        AckReq req = BodyCodec.fromJson(msg.getBody(), AckReq.class);

        if (req.getConversationId() == null || req.getSeq() == null) {
            ImResponses.fail(ctx, msg.getRequestId(), 400, "ack 缺少 conversationId 或 seq");
            return;
        }
        // 只推进位点，不产生任何下行帧：
        // ack 是"客户端→服务端"的单向确认，服务端不需要回应。
        // 如果每次 ack 都回一帧，高峰期的出站流量会凭空翻倍。
        messageService.ack(userId, req.getConversationId(), req.getSeq());
    }
}
