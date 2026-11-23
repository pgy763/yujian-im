package com.yujian.im.netty.handler;

import com.yujian.im.netty.ImResponses;
import com.yujian.im.protocol.BodyCodec;
import com.yujian.im.protocol.Command;
import com.yujian.im.protocol.ImMessage;
import com.yujian.im.read.ReadReceiptService;
import com.yujian.im.session.SessionRegistry;
import io.netty.channel.ChannelHandlerContext;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 查询某条消息的已读回执。
 *
 * <p>走长连接而不是 HTTP 的原因：这个查询几乎总是跟着"我发了消息"或
 * "我打开会话"发生，客户端此时长连接必然是通的；走长连接省掉一次
 * TCP/TLS 握手+HTTP 头的往返，在弱网下差别明显。
 *
 * <p>不主动推送、只在客户端问的时候才答 —— 服务端"主动推回执"是另一条路径
 * （{@code ReadReceiptService.notifySenders} 在别人读了我的消息时触发）。
 * 两条路径共用 {@link ReadReceiptService#build}，保证结构一致。
 */
@Component
@RequiredArgsConstructor
public class ReadReceiptHandler implements CommandHandler {

    private final ReadReceiptService readReceiptService;

    @Override
    public Command command() {
        return Command.READ_RECEIPT_REQ;
    }

    @Override
    public void handle(ChannelHandlerContext ctx, ImMessage msg) {
        Long userId = ctx.channel().attr(SessionRegistry.USER_ID).get();
        @SuppressWarnings("unchecked")
        Map<String, Object> req = BodyCodec.fromJson(msg.getBody(), Map.class);
        if (req == null) {
            ImResponses.fail(ctx, msg.getRequestId(), 400, "请求体为空");
            return;
        }
        Long convId = toLong(req.get("conversationId"));
        Long msgId = toLong(req.get("msgId"));
        if (convId == null || msgId == null) {
            ImResponses.fail(ctx, msg.getRequestId(), 400, "缺少 conversationId 或 msgId");
            return;
        }
        var vo = readReceiptService.of(userId, convId, msgId);
        ImResponses.ok(ctx, msg.getRequestId(), Command.READ_RECEIPT_RESP, vo);
    }

    private Long toLong(Object o) {
        if (o == null) {
            return null;
        }
        if (o instanceof Number n) {
            return n.longValue();
        }
        return Long.valueOf(o.toString());
    }
}
