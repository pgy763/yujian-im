package com.yujian.im.netty.handler;

import com.yujian.im.message.MessageService;
import com.yujian.im.netty.ImResponses;
import com.yujian.im.protocol.BodyCodec;
import com.yujian.im.protocol.Command;
import com.yujian.im.protocol.ImMessage;
import com.yujian.im.protocol.dto.MessageVO;
import com.yujian.im.protocol.dto.PullOfflineReq;
import com.yujian.im.session.SessionRegistry;
import io.netty.channel.ChannelHandlerContext;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 离线补拉：断线重连后把断连期间漏掉的消息补齐。
 *
 * <p><b>"不丢消息"靠的就是这个命令。</b>完整链路：
 * <ol>
 *   <li>消息到达 → 先落库（DB 是唯一真相）→ 再推送；</li>
 *   <li>接收方在线：收到 PUSH_MESSAGE，处理后回 ACK，服务端推进 {@code delivered_seq}；</li>
 *   <li>接收方离线：推送时找不到 Channel，什么也不做——<b>但它已经在库里了</b>；</li>
 *   <li>重连后客户端发 PULL_OFFLINE_REQ(afterSeq = 本地最大 seq)，
 *       服务端 {@code SELECT ... WHERE seq > afterSeq ORDER BY seq LIMIT n}，
 *       把漏的补齐；</li>
 *   <li>返回不满一批（&lt; limit）说明补完了，客户端停止翻页。</li>
 * </ol>
 *
 * <p><b>为什么不是"服务端主动推离线消息"：</b>
 * 那样服务端要维护每个人的离线队列（Redis List 或 MQ），
 * 消息量大时内存吃不消，而且"推送"和"确认"之间又有一次可能失败的交互。
 * 用"客户端按位点拉"把状态收敛到客户端一个数字上，简单且不会丢。
 */
@Component
@RequiredArgsConstructor
public class PullOfflineHandler implements CommandHandler {

    private final MessageService messageService;

    @Override
    public Command command() {
        return Command.PULL_OFFLINE_REQ;
    }

    @Override
    public void handle(ChannelHandlerContext ctx, ImMessage msg) {
        Long userId = ctx.channel().attr(SessionRegistry.USER_ID).get();
        PullOfflineReq req = BodyCodec.fromJson(msg.getBody(), PullOfflineReq.class);

        if (req.getConversationId() == null) {
            ImResponses.fail(ctx, msg.getRequestId(), 400, "缺少 conversationId");
            return;
        }

        // afterSeq 允许为空：客户端首次安装/清缓存后不知道自己在哪，
        // 这时用服务端记录的 delivered_seq 作为起点，替它做决定。
        List<MessageVO> list = messageService.pullOffline(
                userId, req.getConversationId(), req.getAfterSeq(), req.getLimit());

        ImResponses.write(ctx.channel(), msg.getRequestId(), Command.PULL_OFFLINE_RESP,
                (byte) 0, BodyCodec.toJson(list));
    }
}
