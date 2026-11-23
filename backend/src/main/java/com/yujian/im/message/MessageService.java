package com.yujian.im.message;

import com.yujian.im.common.BizException;
import com.yujian.im.common.RedisKeys;
import com.yujian.im.common.SnowflakeIdGenerator;
import com.yujian.im.conversation.Conversation;
import com.yujian.im.conversation.ConversationMemberMapper;
import com.yujian.im.conversation.ConversationSeqService;
import com.yujian.im.conversation.ConversationService;
import com.yujian.im.protocol.dto.ChatReq;
import com.yujian.im.protocol.dto.MessageVO;
import com.yujian.im.push.PushService;
import com.yujian.im.user.User;
import com.yujian.im.user.UserService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * 消息核心服务：发消息、拉历史、拉离线、ack。
 *
 * <p>这个类是"不丢不重不乱序"三件事的实现地，逐条看注释。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MessageService {

    /** 幂等键保留 2 小时，够覆盖任何网络重试窗口。 */
    private static final Duration IDEMPOTENT_TTL = Duration.ofHours(2);

    private final MessageMapper messageMapper;
    private final ConversationService conversationService;
    private final ConversationSeqService seqService;
    private final ConversationMemberMapper memberMapper;
    private final SnowflakeIdGenerator idGenerator;
    private final UserService userService;
    private final PushService pushService;
    private final StringRedisTemplate redis;

    @Value("${im.offline.batch-size:200}")
    private int offlineBatchSize;

    /**
     * 发送消息。整个流程是"先定序、再落库、再推送"。
     *
     * <p><b>不重（幂等）</b>：见 {@link #findDuplicate}。
     * <p><b>不乱序</b>：seq 由 Redis INCR 统一发号，全局单调。
     * <p><b>不丢</b>：落库成功才回 ack；推送失败不影响，客户端重连后按 deliveredSeq 补齐。
     */
    @Transactional
    public MessageVO send(Long fromUserId, ChatReq req) {
        if (req.getConversationId() == null) {
            throw BizException.of("缺少 conversationId");
        }
        if (req.getClientMsgId() == null || req.getClientMsgId().isBlank()) {
            // 强制客户端带幂等键。没有它，网络超时重发就必然产生重复消息，
            // 服务端再聪明也救不回来——因为两条消息在服务端看来完全一样。
            throw BizException.of("缺少 clientMsgId，无法保证幂等");
        }
        String content = req.getContent() == null ? "" : req.getContent();
        if (content.isBlank()) {
            throw BizException.of("消息内容不能为空");
        }
        if (content.length() > 5000) {
            throw BizException.of("消息内容过长，上限 5000 字符");
        }

        Long convId = req.getConversationId();
        conversationService.assertMember(convId, fromUserId);

        // ---------- 1. 幂等：这条消息我之前是不是已经发过了 ----------
        MessageVO dup = findDuplicate(fromUserId, req.getClientMsgId());
        if (dup != null) {
            log.info("幂等命中, user={} clientMsgId={} 已存在 msgId={}",
                    fromUserId, req.getClientMsgId(), dup.getMsgId());
            dup.setDuplicate(true);
            return dup;
        }

        Conversation conv = conversationService.get(convId);

        // ---------- 2. 发号：拿到会话内唯一且单调的 seq ----------
        long seq = seqService.nextSeq(convId);

        // ---------- 3. 落库 ----------
        Message msg = new Message();
        msg.setId(idGenerator.nextId());
        msg.setConversationId(convId);
        msg.setSeq(seq);
        msg.setFromUserId(fromUserId);
        msg.setType(req.getType() == null ? Message.TYPE_TEXT : req.getType());
        msg.setContent(content);
        msg.setClientMsgId(req.getClientMsgId());
        msg.setCreatedAt(LocalDateTime.now());
        try {
            messageMapper.insert(msg);
        } catch (DuplicateKeyException e) {
            // 走到这里说明有两件事之一：
            //  a) 同一 clientMsgId 的并发重发，两个请求同时越过了第 1 步的检查；
            //  b) seq 撞号（Redis 刚被清空且并发初始化）。
            // 两种情况都回查一次，把已经落库的那条返回，对客户端而言仍然是"成功"。
            Message existing = messageMapper.selectByClientMsgId(fromUserId, req.getClientMsgId());
            if (existing != null) {
                MessageVO vo = toVO(existing, null);
                vo.setDuplicate(true);
                return vo;
            }
            throw e;
        }

        // ---------- 4. 更新会话的最后一条消息摘要（会话列表靠它渲染） ----------
        conversationService.updateLastMessage(convId, seq, preview(msg));

        // ---------- 5. 缓存幂等键 ----------
        // 放 DB 事务内是故意的：万一事务回滚，Redis 里多一个 key 只会让下次重发被误判成"已发送"。
        // 所以更好的做法是事务提交后再写（见下面 afterCommit 的简化版）：
        // 用 TTL 兜底 —— 2 小时后 key 过期，即使事务回滚过也不会永久错判。
        redis.opsForValue().set(
                RedisKeys.msgIdempotent(fromUserId, req.getClientMsgId()),
                String.valueOf(msg.getId()),
                IDEMPOTENT_TTL);

        // ---------- 6. 返回 ----------
        // 注意：推送不在这里做，而是交给调用方在事务提交之后做（见 AbstractChatHandler）。
        // 原因：如果在事务内推送，接收方收到消息后立刻回拉历史，很可能读不到这条还没提交的消息
        // （取决于隔离级别），表现为"看到了推送但列表里没有"。这类问题极难复现，只能靠顺序规避。
        return toVO(msg, userService.getById(fromUserId));
    }

    /**
     * 事务提交后推送。**必须**在 {@link #send} 返回之后再调用。
     *
     * <p>为什么不在 send 里顺手推：send 上有 {@code @Transactional}，
     * 推送发生在事务提交之前，接收方收到推送后如果立刻用 HTTP 回拉历史，
     * 可能读不到这条还没提交的消息（REPEATABLE READ 下必然读不到），
     * 表现就是"弹了推送但列表里没有"。这种 bug 极难复现，只能靠代码顺序规避。
     *
     * <p>Spring 的 {@code TransactionSynchronizationManager} 也能做到"提交后再执行"，
     * 但那要求推送代码在事务线程内；这里由 Handler 顺序调用，更直白也更好讲。
     */
    public void push(MessageVO vo, Long fromUserId) {
        // 幂等命中（duplicate=true）时**不能**再推一次。
        // 这条消息之前已经推给所有在线端了，重发只是客户端没收到 ack 在重试，
        // 再推一遍会让对方看到两条一模一样的消息，而且客户端的 msgId 去重
        // 只能挡住自己，挡不住"服务端主动推了第二条"。
        // 这是真实压测时发现的：同 clientMsgId 重发，对方收到两次 PUSH。
        if (Boolean.TRUE.equals(vo.getDuplicate())) {
            log.debug("幂等命中，跳过推送 convId={} msgId={}", vo.getConversationId(), vo.getMsgId());
            return;
        }
        Conversation conv = conversationService.get(vo.getConversationId());
        pushService.pushToConversation(conv, vo, fromUserId);
    }

    /** 幂等查询：先 Redis，后 DB。
     *
     * <p>为什么要查两次：Redis 是缓存，可能重启丢数据、也可能还没写入。
     * DB 上有 {@code uk_client_msg(from_user_id, client_msg_id)} 唯一索引，
     * 那才是最终裁决者。Redis 只是把绝大多数重发挡在离数据库更远的地方。
     */
    private MessageVO findDuplicate(Long fromUserId, String clientMsgId) {
        String cached = redis.opsForValue().get(RedisKeys.msgIdempotent(fromUserId, clientMsgId));
        if (cached != null) {
            Message m = messageMapper.selectById(Long.parseLong(cached));
            if (m != null) {
                return toVO(m, null);
            }
        }
        Message m = messageMapper.selectByClientMsgId(fromUserId, clientMsgId);
        return m == null ? null : toVO(m, null);
    }

    /** 会话列表里的摘要文案。真实产品里还要处理"图片""撤回"等类型。 */
    private String preview(Message msg) {
        if (msg.getRevoked() != null && msg.getRevoked() == 1) {
            return "[已撤回]";
        }
        String c = msg.getContent();
        if (c == null) {
            return "";
        }
        return c.length() > 50 ? c.substring(0, 50) + "..." : c;
    }

    /**
     * 拉历史消息（向上翻页）。
     *
     * @param beforeSeq 看更早的消息时传当前最旧一条的 seq；首次进入传 Long.MAX_VALUE
     */
    public List<MessageVO> history(Long userId, Long convId, Long beforeSeq, int limit) {
        conversationService.assertMember(convId, userId);
        int safeLimit = Math.min(Math.max(limit, 1), 100);
        long before = beforeSeq == null ? Long.MAX_VALUE : beforeSeq;

        List<Message> desc = messageMapper.selectBySeqBefore(convId, before, safeLimit);
        // DAO 为了走索引是倒序查的，返回给客户端前要翻回正序
        List<Message> asc = new ArrayList<>(desc);
        Collections.reverse(asc);
        return toVOs(asc);
    }

    /**
     * 拉离线消息（断线重连后的补洞）。
     *
     * <p>起点用 {@code deliveredSeq}（已确认收到）而不是 {@code lastReadSeq}（已读）：
     * 用户锁屏时消息已经被推送进程收下并 ack 了，但人没看，
     * 这时如果按"已读"补，会把没看过的消息漏掉。
     *
     * @param afterSeq 客户端传自己本地最大的 seq；为空则用服务端记录的 deliveredSeq
     */
    public List<MessageVO> pullOffline(Long userId, Long convId, Long afterSeq, Integer limit) {
        conversationService.assertMember(convId, userId);
        int safeLimit = Math.min(Math.max(limit == null ? offlineBatchSize : limit, 1), offlineBatchSize);

        long from = afterSeq != null
                ? afterSeq
                : nullToZero(memberMapper.selectDeliveredSeq(convId, userId));

        List<Message> list = messageMapper.selectBySeqAfter(convId, from, safeLimit);
        return toVOs(list);
    }

    /**
     * ack：客户端确认收到 seq 之前的所有消息。
     *
     * <p>服务端据此推进 deliveredSeq。这样即使用户本次连接之后又断了，
     * 下次重连也从正确的位置继续补，不会重复推已收到的消息。
     */
    @Transactional
    public void ack(Long userId, Long convId, long seq) {
        conversationService.assertMember(convId, userId);
        memberMapper.advanceDeliveredSeq(convId, userId, seq);
    }

    private long nullToZero(Long v) {
        return v == null ? 0L : v;
    }

    public List<MessageVO> toVOs(List<Message> list) {
        if (list.isEmpty()) {
            return List.of();
        }
        // 一批消息可能来自多人，一次性把发送者查出来，不要逐条查
        java.util.Set<Long> senderIds = new java.util.LinkedHashSet<>();
        for (Message m : list) {
            senderIds.add(m.getFromUserId());
        }
        Map<Long, User> users = userService.mapByIds(senderIds);

        List<MessageVO> out = new ArrayList<>(list.size());
        for (Message m : list) {
            out.add(toVO(m, users.get(m.getFromUserId())));
        }
        return out;
    }

    public MessageVO toVO(Message m, User from) {
        boolean revoked = m.getRevoked() != null && m.getRevoked() == 1;
        return MessageVO.builder()
                .msgId(m.getId())
                .conversationId(m.getConversationId())
                .seq(m.getSeq())
                .fromUserId(m.getFromUserId())
                .fromNickname(from == null ? null : from.getNickname())
                .type(m.getType())
                // 撤回的消息不下发原文。数据库里留着是为了审计，
                // 但一旦下发到客户端，F12 就能看到——那等于没撤回。
                .content(revoked ? "" : m.getContent())
                .createdAt(m.getCreatedAt())
                .clientMsgId(m.getClientMsgId())
                .duplicate(false)
                .revoked(revoked)
                .revokedAt(m.getRevokedAt())
                .build();
    }

    /** 服务端时间戳（毫秒），给客户端校正时钟用。 */
    public static long nowMillis() {
        return LocalDateTime.now().atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
    }
}
