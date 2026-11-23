package com.yujian.im.session;

import io.netty.channel.Channel;
import io.netty.util.AttributeKey;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 本机连接注册表：userId -> 该用户的所有连接。
 *
 * <p>为什么是"一对多"而不是"一对一"：
 * 同一个账号可以在手机、电脑、网页同时在线（多端同步的前提），
 * 所以一个 userId 会挂多条 Channel。
 *
 * <p><b>并发设计（面试重点）：</b>
 * <ul>
 *   <li>外层用 {@link ConcurrentHashMap}：读多写少，且需要"按用户 O(1) 取连接"；</li>
 *   <li>内层用 {@code ConcurrentHashMap.newKeySet()}（一个基于 ConcurrentHashMap 的并发 Set），
 *       天然线程安全，不需要自己加锁；</li>
 *   <li>绝不在这里用 {@code Collections.synchronizedMap}：
 *       它的读也要抢同一把锁，而推送是"读多"路径，会被拖死。</li>
 * </ul>
 *
 * <p>写时用 {@code computeIfAbsent} 而不是 "if (get == null) put"，
 * 后者在并发下会互相覆盖，丢掉别人的连接（经典 check-then-act 竞态）。
 *
 * <p><b>局限（要主动说）：</b>这个 Map 在单进程内存里。
 * 多实例部署时，用户 A 连在节点 1、用户 B 连在节点 2，
 * 节点 2 处理 B 的消息时找不到 A 的 Channel，就要靠 Redis 记录"用户在哪个节点"
 * 再用 MQ 转投（见 README 的"多机扩展"），本项目的单机版本用本地 Map 就够。
 */
@Slf4j
@Component
public class SessionRegistry {

    /** Channel 上挂的 userId 属性。用 AttributeKey 而不是 Map<Channel,userId>，省一份内存也免去清理。 */
    public static final AttributeKey<Long> USER_ID = AttributeKey.valueOf("userId");
    /** Channel 上挂的设备标识。 */
    public static final AttributeKey<String> DEVICE = AttributeKey.valueOf("device");
    /** Channel 上挂的"已通过登录鉴权"标记。 */
    public static final AttributeKey<Boolean> AUTHED = AttributeKey.valueOf("authed");

    private final Map<Long, Set<Channel>> userChannels = new ConcurrentHashMap<>();

    /** 绑定时登记。 */
    public void register(Long userId, Channel channel) {
        userChannels.computeIfAbsent(userId, k -> ConcurrentHashMap.newKeySet()).add(channel);
        channel.attr(USER_ID).set(userId);
        channel.attr(AUTHED).set(true);
        log.info("用户 {} 上线, channel={}, 当前在线端数={}", userId, channel.id().asShortText(), onlineCount(userId));
    }

    /** 断开时清理。返回该用户是否已完全离线（最后一条连接也断了）。 */
    public boolean unregister(Channel channel) {
        Long userId = channel.attr(USER_ID).get();
        if (userId == null) {
            return false;
        }
        Set<Channel> set = userChannels.get(userId);
        if (set == null) {
            return false;
        }
        set.remove(channel);
        if (set.isEmpty()) {
            // 用 remove(key, value) 而不是 remove(key)：
            // 如果在这两步之间有新连接进来，computeIfAbsent 建了新 Set，这里就不该删
            userChannels.remove(userId, set);
            log.info("用户 {} 完全下线", userId);
            return true;
        }
        log.info("用户 {} 下线一个端, 剩余={}", userId, set.size());
        return false;
    }

    /** 取该用户本机的全部连接。没有则返回空集合（不是 null，避免调用方判空翻车）。 */
    public Collection<Channel> channelsOf(Long userId) {
        Set<Channel> set = userChannels.get(userId);
        return set == null ? Collections.emptyList() : set;
    }

    public boolean isOnline(Long userId) {
        Set<Channel> set = userChannels.get(userId);
        return set != null && !set.isEmpty();
    }

    public int onlineCount(Long userId) {
        Set<Channel> set = userChannels.get(userId);
        return set == null ? 0 : set.size();
    }

    /** 全站在线用户数，给 Prometheus 的 gauge 用。 */
    public int totalOnlineUsers() {
        return userChannels.size();
    }

    /** 总连接数。 */
    public int totalChannels() {
        int sum = 0;
        for (Set<Channel> set : userChannels.values()) {
            sum += set.size();
        }
        return sum;
    }

    /**
     * 同账号新连接挤掉旧连接（单端登录场景）。
     *
     * <p>本项目默认允许多端同时在线，这个方法留给"踢下线"做演示：
     * 如果产品要求"手机登录踢掉电脑"，就在这里关掉旧 Channel 并发 KICK_PUSH。
     */
    public void kickOthers(Long userId, Channel keep) {
        for (Channel ch : channelsOf(userId)) {
            if (ch != keep && ch.isActive()) {
                ch.close();
            }
        }
    }
}
