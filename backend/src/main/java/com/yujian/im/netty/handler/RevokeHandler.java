package com.yujian.im.netty.handler;

import com.yujian.im.message.RevokeService;
import com.yujian.im.netty.ImResponses;
import com.yujian.im.protocol.BodyCodec;
import com.yujian.im.protocol.Command;
import com.yujian.im.protocol.ImMessage;
import com.yujian.im.protocol.dto.RevokeReq;
import com.yujian.im.session.SessionRegistry;
import io.netty.channel.ChannelHandlerContext;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 消息撤回。
 *
 * <p>处理顺序刻意是"先返回响应，再广播"——但其实 {@link RevokeService#revoke}
 * 内部已经完成了广播。这里只负责把结果回给发起的那个连接。
 *
 * <p>为什么响应和广播是两条独立的帧：
 * <ul>
 *   <li>{@code REVOKE_RESP} 带 requestId，只有发起连接收，用来让 UI 收掉 loading；</li>
 *   <li>{@code REVOKE_PUSH} 不带 requestId，发给会话里所有人（包括发起者其他端），
 *       用来改气泡渲染。发起者自己也会收到，这是有意的：他的另一台设备需要同步。</li>
 * </ul>
 * 客户端可能先收到 PUSH 再收到 RESP，也可能反过来（两个方向都在同一个 Channel 上，
 * 顺序由服务端写出顺序决定）。所以客户端不能假设"收到 RESP 时 PUSH 一定已经到了"，
 * 两边都要能独立处理同一个 msgId 的撤回。
 */
@Component
@RequiredArgsConstructor
public class RevokeHandler implements CommandHandler {

    private final RevokeService revokeService;

    @Override
    public Command command() {
        return Command.REVOKE_REQ;
    }

    @Override
    public void handle(ChannelHandlerContext ctx, ImMessage msg) throws Exception {
        Long userId = ctx.channel().attr(SessionRegistry.USER_ID).get();
        RevokeReq req = BodyCodec.fromJson(msg.getBody(), RevokeReq.class);
        if (req == null) {
            ImResponses.fail(ctx, msg.getRequestId(), 400, "请求体为空");
            return;
        }
        var vo = revokeService.revoke(userId, req.getConversationId(), req.getMsgId());
        ImResponses.ok(ctx, msg.getRequestId(), Command.REVOKE_RESP, vo);
    }
}
