package com.yujian.im.netty.handler;

/**
 * 单连接令牌桶限流器。
 *
 * <p>为什么需要它：长连接是"一次握手、长期占用"的模型，
 * 一个写错的客户端（比如 while(true) 发消息）能在一秒内打出几万个请求，
 * 把 DB 连接池和 Redis 全部打满。HTTP 有网关层限流，长连接没有，必须自己兜。
 *
 * <p><b>算法选择：</b>令牌桶 vs 漏桶——
 * <ul>
 *   <li>漏桶：出口速率绝对恒定，不允许突发；</li>
 *   <li>令牌桶：允许攒一批令牌应对突发（IM 里"进群后一次拉多条"就是突发）。</li>
 * </ul>
 * 这里用令牌桶，{@code capacity = qps}，即最多攒 1 秒的量。
 *
 * <p><b>线程安全：</b>同一个 Channel 的事件一定在同一个 EventLoop 线程上串行执行，
 * 所以理论上不需要锁。但推送/超时回调可能从别的线程进来，这里仍然用
 * {@code synchronized} 保证正确性——成本可忽略（无竞争时是偏向锁/轻量级锁），
 * 换来的是"永远不会因为并发算错令牌数"。
 */
public class TokenBucket {

    private final int capacity;
    /** 每秒补充的令牌数，等于容量。 */
    private final double refillPerNano;
    private double tokens;
    private long lastRefillNanos;

    public TokenBucket(int qps) {
        this.capacity = Math.max(1, qps);
        this.tokens = capacity;
        this.refillPerNano = capacity / 1_000_000_000.0;
        this.lastRefillNanos = System.nanoTime();
    }

    public synchronized boolean tryAcquire() {
        long now = System.nanoTime();
        long elapsed = now - lastRefillNanos;
        if (elapsed > 0) {
            tokens = Math.min(capacity, tokens + elapsed * refillPerNano);
            lastRefillNanos = now;
        }
        if (tokens >= 1.0) {
            tokens -= 1.0;
            return true;
        }
        return false;
    }
}
