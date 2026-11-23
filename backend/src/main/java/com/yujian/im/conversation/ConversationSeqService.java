package com.yujian.im.conversation;

import com.yujian.im.common.RedisKeys;
import com.yujian.im.message.MessageMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

/**
 * 会话内序号（seq）发号器 —— 这是"消息有序"的地基。
 *
 * <p><b>为什么不能用时间戳排序：</b>两台机器的时钟差几毫秒，同一毫秒内多条消息时间戳相同，
 * 客户端的消息顺序就会抖。seq 是每个会话内严格单调递增的整数，才是可靠的顺序依据。
 *
 * <p><b>为什么用 Redis INCR 而不是数据库自增：</b>
 * <ul>
 *   <li>INCR 是单线程原子的，一次 Redis 往返（同机房 &lt;1ms），比"UPDATE 再 SELECT"轻；</li>
 *   <li>数据库的 UPDATE + SELECT 需要行锁，同一个热门群聊的所有消息都在这行上排队；</li>
 *   <li>Redis 发号后，落库只是一次普通 INSERT，不需要事务里锁会话行。</li>
 * </ul>
 *
 * <p><b>代价（面试要主动说）：</b>Redis 发号成功但数据库插入失败，这个 seq 就成了"空号"，
 * 消息序列里出现洞。我们的处理是<b>允许洞</b>：
 * 客户端发现 seq 不连续时，按 seq 区间回拉一次补齐（见 README 的"补洞"），
 * 而不是要求 seq 绝对连续。这是所有主流 IM 的做法——
 * 要"绝对连续"就得在 Redis 和 MySQL 之间做两阶段提交，复杂度和收益完全不匹配。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ConversationSeqService {

    private final StringRedisTemplate redis;
    private final MessageMapper messageMapper;

    /**
     * 取下一个序号。
     *
     * <p>冷启动问题：Redis 重启后 key 没了，INCR 会从 1 开始，
     * 于是新消息的 seq 会和历史消息撞车，直接违反唯一索引。
     * 所以这里做"key 不存在就先从 DB 的 max(seq) 初始化"：
     * 用 SETNX 抢占初始化权，并发时只有一个线程真正去查库。
     */
    public long nextSeq(Long conversationId) {
        String key = RedisKeys.convSeq(conversationId);

        if (Boolean.FALSE.equals(redis.hasKey(key))) {
            initFromDb(conversationId, key);
        }

        Long seq = redis.opsForValue().increment(key);
        if (seq == null) {
            throw new IllegalStateException("Redis 发号失败, convId=" + conversationId);
        }
        return seq;
    }

    /** 从 MySQL 的 max(seq) 初始化计数器。历史消息的全部内容都以 DB 为准。 */
    private void initFromDb(Long conversationId, String key) {
        Long maxSeq = messageMapper.selectMaxSeq(conversationId);
        long init = maxSeq == null ? 0L : maxSeq;
        // SETNX：如果别的线程已经初始化过，这里返回 false，我们什么都不做，
        // 保证不会把已经涨上去的计数器覆盖回旧值
        Boolean set = redis.opsForValue().setIfAbsent(key, String.valueOf(init));
        if (Boolean.TRUE.equals(set)) {
            log.info("会话 {} 的 seq 计数器从 DB 初始化为 {}", conversationId, init);
        }
    }

    /** 当前已发到多少号（不改计数）。 */
    public long currentSeq(Long conversationId) {
        String v = redis.opsForValue().get(RedisKeys.convSeq(conversationId));
        if (v == null) {
            Long max = messageMapper.selectMaxSeq(conversationId);
            return max == null ? 0L : max;
        }
        return Long.parseLong(v);
    }
}
