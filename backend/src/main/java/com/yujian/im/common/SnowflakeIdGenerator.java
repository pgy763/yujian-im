package com.yujian.im.common;

/**
 * 雪花 ID 生成器。
 *
 * <p>为什么不用数据库自增：
 * <ul>
 *   <li>消息 ID 要能在"响应还没回来"之前就告诉客户端，自增必须等 insert 完才知道；</li>
 *   <li>将来分库分表，自增 ID 会冲突，雪花天然全局有序且不重复；</li>
 *   <li>雪花 ID 里带时间戳，客户端能直接看出消息先后。</li>
 * </ul>
 *
 * <p>64 位布局：1 位符号(0) + 41 位时间戳(毫秒) + 5 位机器号 + 5 位机房号 + 12 位序列号。
 * 12 位序列 = 单节点每毫秒 4096 个 ID，也就是单机理论 400 万 QPS，够用。
 *
 * <p>两个坑要处理：
 * <ol>
 *   <li>时钟回拨：机器时间被 NTP 往回拨，生成的 ID 可能重复。这里选择"等到时间追上"，
 *       回拨超过 5ms 直接抛异常，不让错误数据流进系统。</li>
 *   <li>序列号溢出：同一毫秒内超过 4096 个请求，就 spin 等下一毫秒。</li>
 * </ol>
 */
public class SnowflakeIdGenerator {

    /** 起始纪元：2024-01-01 00:00:00，41 位时间戳从这算起，能用约 69 年。 */
    private static final long EPOCH = 1704038400000L;

    private static final long WORKER_ID_BITS = 5L;
    private static final long DATACENTER_ID_BITS = 5L;
    private static final long SEQUENCE_BITS = 12L;

    private static final long MAX_WORKER_ID = ~(-1L << WORKER_ID_BITS);
    private static final long MAX_DATACENTER_ID = ~(-1L << DATACENTER_ID_BITS);
    private static final long SEQUENCE_MASK = ~(-1L << SEQUENCE_BITS);

    private static final long WORKER_ID_SHIFT = SEQUENCE_BITS;
    private static final long DATACENTER_ID_SHIFT = SEQUENCE_BITS + WORKER_ID_BITS;
    private static final long TIMESTAMP_SHIFT = SEQUENCE_BITS + WORKER_ID_BITS + DATACENTER_ID_BITS;

    private final long workerId;
    private final long datacenterId;

    private long sequence = 0L;
    private long lastTimestamp = -1L;

    public SnowflakeIdGenerator(long workerId, long datacenterId) {
        if (workerId > MAX_WORKER_ID || workerId < 0) {
            throw new IllegalArgumentException("workerId 必须在 0~" + MAX_WORKER_ID);
        }
        if (datacenterId > MAX_DATACENTER_ID || datacenterId < 0) {
            throw new IllegalArgumentException("datacenterId 必须在 0~" + MAX_DATACENTER_ID);
        }
        this.workerId = workerId;
        this.datacenterId = datacenterId;
    }

    public synchronized long nextId() {
        long timestamp = System.currentTimeMillis();

        if (timestamp < lastTimestamp) {
            long offset = lastTimestamp - timestamp;
            // 小幅回拨（<5ms）就等一等，等时钟追上来
            if (offset <= 5) {
                try {
                    Thread.sleep(offset + 1);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("雪花ID等待时钟回拨时被中断", e);
                }
                timestamp = System.currentTimeMillis();
                if (timestamp < lastTimestamp) {
                    throw new IllegalStateException("时钟回拨无法恢复: " + offset + "ms");
                }
            } else {
                // 回拨太多说明机器时间有问题，宁可报错也不能发重复 ID
                throw new IllegalStateException("时钟回拨过大，拒绝生成ID: " + offset + "ms");
            }
        }

        if (timestamp == lastTimestamp) {
            sequence = (sequence + 1) & SEQUENCE_MASK;
            if (sequence == 0) {
                // 这一毫秒的 4096 个号用完了，自旋到下一毫秒
                timestamp = waitNextMillis(lastTimestamp);
            }
        } else {
            sequence = 0L;
        }

        lastTimestamp = timestamp;

        return ((timestamp - EPOCH) << TIMESTAMP_SHIFT)
                | (datacenterId << DATACENTER_ID_SHIFT)
                | (workerId << WORKER_ID_SHIFT)
                | sequence;
    }

    private long waitNextMillis(long lastTimestamp) {
        long timestamp = System.currentTimeMillis();
        while (timestamp <= lastTimestamp) {
            timestamp = System.currentTimeMillis();
        }
        return timestamp;
    }
}
