package com.yujian.im.conversation;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.yujian.im.common.BizException;
import com.yujian.im.common.SnowflakeIdGenerator;
import com.yujian.im.message.MessageMapper;
import com.yujian.im.user.User;
import com.yujian.im.user.UserService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 会话领域服务：建会话、查会话、成员管理。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ConversationService {

    private final ConversationMapper conversationMapper;
    private final ConversationMemberMapper memberMapper;
    private final MessageMapper messageMapper;
    private final SnowflakeIdGenerator idGenerator;
    private final UserService userService;

    /**
     * 建单聊会话。同一个 (a,b) 只会有一个会话，靠 pair_key 唯一索引保证。
     *
     * <p>并发场景：A 和 B 同时点"发消息"，两个请求都查不到会话，都去 insert，
     * 唯一索引会让其中一个抛 DuplicateKeyException。
     * 这里捕获它并回查一次，直接返回对方建好的那条——这是"先查后插"必然的兜底写法。
     */
    @Transactional
    public Conversation createOrGetSingle(Long userA, Long userB) {
        if (userA.equals(userB)) {
            throw BizException.of("不能和自己创建会话");
        }
        String pairKey = pairKeyOf(userA, userB);
        Conversation exist = conversationMapper.selectByPairKey(pairKey);
        if (exist != null) {
            return exist;
        }

        Conversation c = new Conversation();
        c.setId(idGenerator.nextId());
        c.setType(Conversation.TYPE_SINGLE);
        c.setSeq(0L);
        c.setPairKey(pairKey);
        c.setCreatedAt(LocalDateTime.now());
        try {
            conversationMapper.insert(c);
        } catch (DuplicateKeyException e) {
            Conversation other = conversationMapper.selectByPairKey(pairKey);
            if (other != null) {
                return other;
            }
            throw e;
        }

        addMember(c.getId(), userA, 0);
        addMember(c.getId(), userB, 0);
        log.info("创建单聊会话 {}: {} <-> {}", c.getId(), userA, userB);
        return c;
    }

    /** 建群聊。群主自动进成员表，并把被邀请的人一次性批量插入。 */
    @Transactional
    public Conversation createGroup(Long ownerId, String name, Collection<Long> memberIds) {
        Set<Long> all = new LinkedHashSet<>();
        all.add(ownerId);
        if (memberIds != null) {
            all.addAll(memberIds);
        }
        // 去重后再校验：允许前端传重复 ID，但不能因为重复报错
        all.removeIf(id -> id == null);
        if (all.size() < 2) {
            throw BizException.of("群聊至少需要 2 个成员");
        }
        if (all.size() > 200) {
            throw BizException.of("群成员上限 200 人");
        }

        Conversation c = new Conversation();
        c.setId(idGenerator.nextId());
        c.setType(Conversation.TYPE_GROUP);
        c.setName(name == null || name.isBlank() ? "未命名群聊" : name);
        c.setOwnerId(ownerId);
        c.setSeq(0L);
        c.setCreatedAt(LocalDateTime.now());
        conversationMapper.insert(c);

        for (Long uid : all) {
            addMember(c.getId(), uid, uid.equals(ownerId) ? 1 : 0);
        }
        log.info("创建群聊 {}: {} 人", c.getId(), all.size());
        return c;
    }

    private void addMember(Long convId, Long userId, int role) {
        ConversationMember m = new ConversationMember();
        m.setId(idGenerator.nextId());
        m.setConversationId(convId);
        m.setUserId(userId);
        m.setLastReadSeq(0L);
        m.setDeliveredSeq(0L);
        m.setRole(role);
        m.setJoinedAt(LocalDateTime.now());
        memberMapper.insert(m);
    }

    public Conversation get(Long convId) {
        Conversation c = conversationMapper.selectById(convId);
        if (c == null) {
            throw BizException.of("会话不存在: " + convId);
        }
        return c;
    }

    /** 校验"用户是否在这个会话里"。每个上行指令都要过这一关，否则能往任意会话发消息。 */
    public void assertMember(Long convId, Long userId) {
        Long readSeq = memberMapper.selectReadSeq(convId, userId);
        if (readSeq == null) {
            throw BizException.forbidden("你不在该会话中");
        }
    }

    public List<Long> memberIds(Long convId) {
        return memberMapper.selectUserIds(convId);
    }

    /**
     * 会话列表。按"最后消息时间"倒序，未读数从 Redis 取（Redis 没有就回 DB 算）。
     */
    public List<ConversationVO> listOf(Long userId) {
        List<Long> convIds = memberMapper.selectConvIdsByUser(userId);
        if (convIds.isEmpty()) {
            return List.of();
        }
        List<Conversation> convs = conversationMapper.selectList(
                new LambdaQueryWrapper<Conversation>()
                        .in(Conversation::getId, convIds)
                        .orderByDesc(Conversation::getLastMsgAt));

        // 批量把用户信息查出来，避免每个会话都查一次用户（N+1 查询）
        Set<Long> userIds = new LinkedHashSet<>();
        for (Conversation c : convs) {
            if (c.getType() == Conversation.TYPE_SINGLE) {
                userIds.addAll(memberMapper.selectUserIds(c.getId()));
            }
        }
        Map<Long, User> userMap = userService.mapByIds(userIds);

        List<ConversationVO> out = new ArrayList<>(convs.size());
        for (Conversation c : convs) {
            long unread = countUnread(userId, c);
            String title = c.getName();
            String avatar = null;
            if (c.getType() == Conversation.TYPE_SINGLE) {
                // 单聊不存群名，标题就是对方的昵称
                Long peerId = memberMapper.selectUserIds(c.getId()).stream()
                        .filter(id -> !id.equals(userId))
                        .findFirst().orElse(null);
                User peer = peerId == null ? null : userMap.get(peerId);
                title = peer == null ? "已注销用户" : peer.getNickname();
                avatar = peer == null ? null : peer.getAvatar();
            }
            out.add(new ConversationVO(
                    c.getId(), c.getType(), title, avatar,
                    c.getLastPreview(), c.getLastMsgAt(),
                    c.getSeq() == null ? 0L : c.getSeq(), unread));
        }
        return out;
    }

    /** 未读数 = 会话当前 seq - 我的已读位点。这是最便宜、最不会漂移的算法。 */
    public long countUnread(Long userId, Conversation c) {
        Long readSeq = memberMapper.selectReadSeq(c.getId(), userId);
        long read = readSeq == null ? 0 : readSeq;
        long max = c.getSeq() == null ? 0 : c.getSeq();
        return Math.max(0, max - read);
    }

    /** 推进已读位点，返回推进后的值。只前进不后退。 */
    @Transactional
    public long markRead(Long userId, Long convId, long seq) {
        assertMember(convId, userId);
        memberMapper.advanceReadSeq(convId, userId, seq);
        Long now = memberMapper.selectReadSeq(convId, userId);
        return now == null ? 0 : now;
    }

    /**
     * 推进已读位点，同时返回"推进前"的旧值。
     *
     * <p>为什么要旧值：已读回执需要知道<b>这次新读了哪一段</b>
     * （{@code oldSeq, newSeq]}），才能只给这段区间里的消息发送者推回执。
     * 没有旧值就只能每 5 分钟扫全表找"哪些人的消息我还没读"，代价完全不同。
     *
     * <p>读旧值和推进位点之间没有加锁，理论上两个并发请求可能读到同一个旧值，
     * 于是区间重叠、回执重复推一次。这个重复是<b>可接受</b>的：
     * 回执是幂等的（客户端按 msgId/seq 覆盖标记，重复收到只是重设同一个值），
     * 而加锁的代价（每次已读都要抢一次行锁）远大于这点重复。
     * 这是"宁可重复，不要加锁"的典型场景。
     */
    @Transactional
    public ReadAdvance advanceRead(Long userId, Long convId, long seq) {
        assertMember(convId, userId);
        Long before = memberMapper.selectReadSeq(convId, userId);
        long prev = before == null ? 0L : before;
        memberMapper.advanceReadSeq(convId, userId, seq);
        Long after = memberMapper.selectReadSeq(convId, userId);
        long now = after == null ? prev : after;
        return new ReadAdvance(prev, now);
    }

    /** "已读位点从 prev 前进到 now"。给已读回执算区间用。 */
    public record ReadAdvance(long prev, long now) {
        /** 这次真的读了新东西吗（而不是重复上报同一个位点）。 */
        public boolean advanced() {
            return now > prev;
        }
    }

    /** 推进"已收到"位点，供 ack 使用。 */
    @Transactional
    public void markDelivered(Long userId, Long convId, long seq) {
        memberMapper.advanceDeliveredSeq(convId, userId, seq);
    }

    /**
     * 消息落库后刷新会话的"最后一条"摘要，会话列表靠这两个字段渲染。
     *
     * <p>这里用 {@code seq = ?} 直接覆盖，而不是 {@code seq = seq + 1}：
     * 号已经在 {@link ConversationSeqService} 里发过了，这里只是把会话行同步成同号，
     * 保证 {@code im_conversation.seq} 与 {@code im_message.seq} 的最大值一致。
     * 万一并发下有两条消息乱序到达，后写的那条可能 seq 更小——
     * 所以 UPDATE 里加了 {@code seq < #{seq}} 条件，只允许前进。
     */
    @Transactional
    public void updateLastMessage(Long convId, long seq, String preview) {
        conversationMapper.updateLastMeta(convId, preview, LocalDateTime.now(), seq);
    }

    /** 单聊的 pair_key：两个 ID 排序后拼接，保证 (a,b) 和 (b,a) 得到同一个 key。 */
    private String pairKeyOf(Long a, Long b) {
        return a < b ? a + ":" + b : b + ":" + a;
    }

    /**
     * 会话列表项。
     *
     * <p>{@code seq} 和 {@code unread} 用原始 {@code long} 是有意的：它们的语义是
     * "计数器"，前端要做 {@code Math.max} 和排序，必须是 JSON 数字。
     * 而 {@code id} 是 {@code Long}（标识符），会被全局规则序列化成字符串，
     * 避免雪花 ID 超过 JS 的 2^53 精度上限（详见 {@code common/JsonModules.java}）。
     */
    public record ConversationVO(Long id, Integer type, String title, String avatar,
                                 String lastPreview, LocalDateTime lastMsgAt,
                                 long seq, long unread) {
    }
}
