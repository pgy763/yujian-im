package com.yujian.im.message;

import com.yujian.im.common.BizException;
import com.yujian.im.conversation.Conversation;
import com.yujian.im.conversation.ConversationService;
import com.yujian.im.protocol.dto.MessageVO;
import com.yujian.im.protocol.dto.RevokePush;
import com.yujian.im.push.PushService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;

/**
 * 消息撤回。
 *
 * <p><b>三条规则（面试会逐条问）：</b>
 * <ol>
 *   <li><b>谁能撤</b>：只有发送者本人。判断写在 SQL 的 WHERE 里
 *       （{@code from_user_id = #{fromUserId}}），而不是"先查出来再 if"——
 *       查和改之间的窗口里，别人可能刚好改了状态，把权限判断放在 UPDATE 里
 *       让它和修改成为同一个原子操作。</li>
 *   <li><b>多久内能撤</b>：默认 2 分钟，可配。超时抛业务异常。
 *       时间用消息的 {@code createdAt} 算，不用"服务端当前时间减某个缓存值"——
 *       后者在服务器时钟漂移时会莫名其妙拒绝正常请求。</li>
 *   <li><b>撤了之后是什么</b>：软删。行保留、seq 保留，只是 {@code revoked=1}，
 *       对外下发的 content 变成空串。原始内容留在库里供审计。</li>
 * </ol>
 *
 * <p><b>为什么必须幂等：</b>用户点两次"撤回"、或者客户端超时重试，
 * 都会让这个接口被打两次。第一次 affected=1，第二次 affected=0。
 * 只有 affected=1 才广播，否则群里每个人都会收到两条"撤回"推送。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RevokeService {

    private final MessageMapper messageMapper;
    private final ConversationService conversationService;
    private final PushService pushService;

    /** 撤回时间窗。真实的微信是 2 分钟，这里保持一致，也方便面试时说"参照微信"。 */
    @Value("${im.revoke.window-seconds:120}")
    private long windowSeconds;

    /**
     * 撤回一条消息。
     *
     * @return 撤回后的消息视图（content 已置空）。调用方负责把它回给客户端。
     */
    @Transactional
    public MessageVO revoke(Long userId, Long convId, Long msgId) {
        if (convId == null || msgId == null) {
            throw BizException.of("缺少 conversationId 或 msgId");
        }
        conversationService.assertMember(convId, userId);

        Message msg = messageMapper.selectById(msgId);
        if (msg == null) {
            throw BizException.of("消息不存在");
        }
        // 消息必须属于这个会话。否则可以拿着 A 会话的 msgId、B 会话的 convId 来撤回，
        // assertMember 只校验了 B 会话的成员身份，消息本身跨了会话就是越权。
        if (!convId.equals(msg.getConversationId())) {
            throw BizException.of("消息不属于该会话");
        }
        if (!userId.equals(msg.getFromUserId())) {
            throw BizException.forbidden("只能撤回自己发的消息");
        }

        // 已经是撤回态：直接当成功返回，不重复广播。
        if (msg.getRevoked() != null && msg.getRevoked() == 1) {
            log.debug("重复撤回，忽略广播 msgId={}", msgId);
            return toRevokedVO(msg);
        }

        LocalDateTime createdAt = msg.getCreatedAt();
        if (createdAt != null
                && Duration.between(createdAt, LocalDateTime.now()).getSeconds() > windowSeconds) {
            throw BizException.of("超过 " + windowSeconds + " 秒，不能再撤回");
        }

        // 这个 UPDATE 同时做了三件事：校验身份、校验未撤回、置标志位。
        // 三者是一个原子操作，中间没有可以插队的窗口。
        int affected = messageMapper.markRevoked(msgId, convId, userId);
        if (affected == 0) {
            // 极端并发：两个请求同时通过了上面的检查，只有一个能 UPDATE 成功。
            // 没成功的这个也不报错——结果是一样的（消息已撤回），只是不该再广播。
            log.debug("撤回并发竞争，本次不广播 msgId={}", msgId);
            Message latest = messageMapper.selectById(msgId);
            return toRevokedVO(latest == null ? msg : latest);
        }

        // 如果撤回的正好是会话里最后一条，会话列表的摘要就成了"原文"，
        // 必须重算成上一条正常消息，否则列表里会出现一条已经不存在的消息文案。
        refreshPreview(convId);

        RevokePush push = new RevokePush(convId, msgId, msg.getSeq(), userId, LocalDateTime.now());
        Conversation conv = conversationService.get(convId);
        pushService.pushRevoke(conv, push);

        log.info("消息已撤回 convId={} msgId={} by={}", convId, msgId, userId);
        return toRevokedVO(msg);
    }

    /**
     * 重算会话摘要。
     *
     * <p>注意只改 {@code last_preview} 和 {@code last_msg_at}，<b>不动 seq</b>：
     * 撤回不回收序号。序号一旦被回收，客户端本地缓存的"我读到第 20 条"
     * 就有可能指向一条全新的消息，导致漏消息。
     */
    private void refreshPreview(Long convId) {
        Message last = messageMapper.selectLastNormal(convId);
        if (last == null) {
            // 会话里所有消息都被撤回了（或者本来就只有这一条）。
            // 第一版这里传了空字符串，会话列表就显示成一片空白 —— 用户会以为消息没了。
            // 显示 [已撤回] 才是诚实的状态：确实还有一条消息，只是内容被撤了。
            conversationService.updateLastMessage(convId, conversationService.get(convId).getSeq(), "[已撤回]");
            return;
        }
        String preview = last.getContent() == null ? "" : last.getContent();
        if (preview.length() > 50) {
            preview = preview.substring(0, 50) + "...";
        }
        conversationService.updateLastMessage(convId, conversationService.get(convId).getSeq(), preview);
    }

    /** 撤回态的消息视图：内容清空，但 seq / msgId / 发送者都保留，客户端据此定位到那个气泡。 */
    private MessageVO toRevokedVO(Message msg) {
        return MessageVO.builder()
                .msgId(msg.getId())
                .conversationId(msg.getConversationId())
                .seq(msg.getSeq() == null ? 0L : msg.getSeq())
                .fromUserId(msg.getFromUserId())
                .type(msg.getType())
                .content("")
                .createdAt(msg.getCreatedAt())
                .clientMsgId(msg.getClientMsgId())
                .duplicate(false)
                .revoked(true)
                .revokedAt(msg.getRevokedAt())
                .build();
    }
}
