package com.yujian.im.read;

import com.yujian.im.common.BizException;
import com.yujian.im.conversation.ConversationService;
import com.yujian.im.conversation.ConversationMemberMapper;
import com.yujian.im.message.Message;
import com.yujian.im.message.MessageMapper;
import com.yujian.im.protocol.dto.ReadReceiptVO;
import com.yujian.im.push.PushService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 已读回执：回答"我这条消息，谁读了、谁没读"。
 *
 * <p><b>核心建模决策：不建"每人每条"的已读表。</b>
 * 已读的真相是每个成员的 {@code last_read_seq} 位点 —— "他读到了第 N 条"。
 * 位点语义下，"读完第 N 条"天然推出 1..N 全读过，所以：
 * <pre>
 *   已读的人 = select user_id where last_read_seq >= 我的 seq
 *   未读的人 = 全部成员 - 已读的人
 * </pre>
 * 如果按"消息 × 人"存一张 im_message_read 表，一个 100 人群聊 1 万条消息
 * 就是 100 万行，而且每读一条都要写一行，写放大不可接受。
 *
 * <p><b>代价（必须主动说出来）：</b>位点法只能回答"读了没"，
 * 答不了"谁在什么时候读的"。真要做"已读时间"或"已读人数随时间变化曲线"，
 * 还是得落一张明细表。这是一个典型的"用查询能力换存储/写性能"的取舍。
 *
 * <p><b>单聊 vs 群聊：</b>单聊的 {@code totalOthers} 恒为 1，
 * 客户端就能显示"已读/未读"；群聊返回真实的 N-1，显示"2/3 人已读"。
 * 服务端不为这两种场景写两套代码，前端自己根据总数决定文案。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReadReceiptService {

    private final MessageMapper messageMapper;
    private final ConversationMemberMapper memberMapper;
    private final ConversationService conversationService;
    private final PushService pushService;

    /**
     * 查某条消息的已读回执。
     *
     * <p>权限：必须是会话成员。非成员连"谁读了"都不该知道。
     */
    public ReadReceiptVO of(Long userId, Long convId, Long msgId) {
        if (convId == null || msgId == null) {
            throw BizException.of("缺少 conversationId 或 msgId");
        }
        conversationService.assertMember(convId, userId);

        Message msg = messageMapper.selectById(msgId);
        if (msg == null) {
            throw BizException.of("消息不存在");
        }
        if (!convId.equals(msg.getConversationId())) {
            throw BizException.of("消息不属于该会话");
        }
        return build(convId, msg);
    }

    /**
     * 组装回执。被 {@link #of} 和"有人读了我的消息 → 主动推给我"共用，
     * 保证 HTTP 查到的和推送过来的结构完全一致，前端只需要一套渲染逻辑。
     */
    public ReadReceiptVO build(Long convId, Message msg) {
        long seq = msg.getSeq() == null ? 0L : msg.getSeq();
        Long senderId = msg.getFromUserId();

        // 已读集合：last_read_seq >= 这条消息的 seq
        List<Map<String, Object>> rows = memberMapper.selectReaders(convId, seq);
        Set<Long> readIds = new LinkedHashSet<>();
        for (Map<String, Object> row : rows) {
            Object idObj = row.get("userId");
            if (idObj == null) {
                continue;
            }
            Long id = idObj instanceof Number n ? n.longValue() : Long.valueOf(idObj.toString());
            // 发送者自己不算"已读者"：自己发的消息自己当然看过，
            // 算进去会让单聊显示成"2/2 已读"，人数对不上。
            if (!id.equals(senderId)) {
                readIds.add(id);
            }
        }

        List<Long> members = memberMapper.selectUserIds(convId);
        List<String> read = new ArrayList<>(readIds.size());
        List<String> unread = new ArrayList<>();
        for (Long id : members) {
            if (id.equals(senderId)) {
                continue;
            }
            if (readIds.contains(id)) {
                read.add(String.valueOf(id));
            } else {
                unread.add(String.valueOf(id));
            }
        }

        // ID 转字符串下发，和全局 Long→String 规则保持一致（见 common/JsonModules）。
        return new ReadReceiptVO(convId, msg.getId(), seq,
                read, unread, read.size() + unread.size());
    }

    /**
     * 某人的已读位点前进了之后，给"被他读到消息的那些发送者"各推一条回执。
     *
     * <p><b>为什么要按发送者聚合：</b>用户打开会话一口气读了 20 条，
     * 这 20 条可能来自 3 个人。如果按消息逐条推，会瞬间发出 20 帧推送，
     * 而发送者真正关心的只是"我的消息被读了"，一条就够。
     * 所以这里对每个发送者取"他在这个区间里最新的一条"，
     * 回执带上那条的 msgId/seq —— 客户端据此把该发送者之前的消息全部标成已读。
     *
     * <p><b>为什么要传 afterSeq：</b>不知道上一次读到位点在哪，
     * 就只能扫全表找发送者。传了区间后只扫这次新读的部分，
     * 常见情况（一次读几条）区间极小，扫描量可以忽略。
     *
     * @param fromSeq 本次已读之前的位点（不含）
     * @param toSeq   本次已读之后的位点（含）
     */
    public void notifySenders(Long convId, Long readerId, long fromSeq, long toSeq) {
        if (toSeq <= fromSeq) {
            return;
        }
        List<Message> latest = messageMapper.selectLatestPerSenderInRange(convId, fromSeq, toSeq, readerId);
        if (latest.isEmpty()) {
            return;
        }
        for (Message m : latest) {
            // 撤回的消息不推回执：发送者都不该再看到这条了，
            // 告诉他"你的这条被读了"没有意义，反而会暴露撤回前的存在。
            if (m.getRevoked() != null && m.getRevoked() == 1) {
                continue;
            }
            try {
                ReadReceiptVO vo = build(convId, m);
                pushService.pushReadReceipt(m.getFromUserId(), vo);
            } catch (Exception e) {
                // 回执是"锦上添花"的功能，失败不能影响已读位点推进本身。
                // 位点已经写进 DB 了，客户端下次进会话时拉一次回执就能恢复。
                log.warn("推已读回执失败 convId={} msgId={}: {}", convId, m.getId(), e.getMessage());
            }
        }
    }
}
