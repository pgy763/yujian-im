package com.yujian.im.netty.handler;

import com.yujian.im.conversation.ConversationService;
import com.yujian.im.netty.ImResponses;
import com.yujian.im.protocol.BodyCodec;
import com.yujian.im.protocol.Command;
import com.yujian.im.protocol.ImMessage;
import com.yujian.im.push.PushService;
import com.yujian.im.read.ReadReceiptService;
import com.yujian.im.session.SessionRegistry;
import io.netty.channel.ChannelHandlerContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 已读上报：一个指令干三件事。
 *
 * <p><b>1. 多端同步（原来的职责）</b>：A 端上报已读 → 推进 {@code last_read_seq}
 * → 给同账号的其他端推 {@code READ_SYNC_PUSH}，其他端清红点。
 *
 * <p><b>2. 已读回执（新增）</b>：这次新读的区间里，如果有别人发给我的消息，
 * 就分别给那些发送者推一条 {@code READ_RECEIPT_PUSH}，告诉他们"你的消息被读了"。
 * 只给发送者推、不广播全群，理由见 {@code PushService.pushReadReceipt}。
 *
 * <p><b>3. 清未读缓存</b>。
 *
 * <p><b>为什么这三件事不拆成三个指令：</b>它们由同一个用户动作（打开会话）触发，
 * 拆开就意味着客户端要多发两帧、服务端要多两次鉴权和成员校验。
 * 一个指令做完，客户端简单、服务端少一半校验开销。
 * 代价是这个 Handler 承担了三个语义 —— 但只要它们共享同一个前置条件
 * （"我是这个会话的成员"），合并就是合理的。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ReadSyncHandler implements CommandHandler {

    private final ConversationService conversationService;
    private final PushService pushService;
    private final ReadReceiptService readReceiptService;

    @Override
    public Command command() {
        return Command.READ_SYNC_REQ;
    }

    @Override
    public void handle(ChannelHandlerContext ctx, ImMessage msg) {
        Long userId = ctx.channel().attr(SessionRegistry.USER_ID).get();
        @SuppressWarnings("unchecked")
        Map<String, Object> req = BodyCodec.fromJson(msg.getBody(), Map.class);

        Long convId = toLong(req.get("conversationId"));
        Long seq = toLong(req.get("seq"));
        if (convId == null || seq == null) {
            ImResponses.fail(ctx, msg.getRequestId(), 400, "缺少 conversationId 或 seq");
            return;
        }

        // 用 advanceRead 而不是 markRead：需要知道推进前的旧位点，
        // 才能只给"这次新读的那段区间"的发送者推回执。
        var advance = conversationService.advanceRead(userId, convId, seq);
        pushService.clearUnread(userId, convId);

        // 1) 同账号其他端清红点（except = 当前连接，不用给自己推）
        pushService.pushReadSync(userId, convId, advance.now(), ctx.channel());

        // 2) 给发送者推已读回执。只有真的前进了才推——
        //    重复上报同一个位点（客户端每滚一下都可能上报）不该产生任何推送。
        if (advance.advanced()) {
            try {
                readReceiptService.notifySenders(convId, userId, advance.prev(), advance.now());
            } catch (Exception e) {
                // 回执失败不能影响已读本身：位点已经写进 DB 了，
                // 客户端下次进会话拉一次回执就能恢复。
                log.warn("推已读回执失败 convId={} user={}: {}", convId, userId, e.getMessage());
            }
        }
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
