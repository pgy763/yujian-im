package com.yujian.im.auth;

import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * token 黑名单。
 *
 * <p>JWT 是无状态的，签出去就收不回来。但"退出登录"、"改密码"、"管理员封号"
 * 这几个场景必须能让旧 token 立刻失效，所以补一个 Redis 黑名单：
 * key = im:jwt:blacklist:{token 的 sha256}，value = 1，TTL = token 剩余有效期。
 *
 * <p>为什么不直接存 token 原文：token 本身就是凭证，落 Redis 等于在缓存里放了一堆密码；
 * 存哈希既够用（查得到就行），泄露了也不能直接拿去登录。
 */
@Component
@RequiredArgsConstructor
public class TokenBlacklist {

    private static final String KEY_PREFIX = "im:jwt:blacklist:";

    private final StringRedisTemplate redis;

    public void revoke(String token, long remainingMillis) {
        if (remainingMillis <= 0) {
            // 已经过期的 token 不需要进黑名单，校验时自然失败
            return;
        }
        String digest = Digest.sha256Hex(token);
        redis.opsForValue().set(KEY_PREFIX + digest, "1", Duration.ofMillis(remainingMillis));
    }

    public boolean isRevoked(String token) {
        return Boolean.TRUE.equals(redis.hasKey(KEY_PREFIX + Digest.sha256Hex(token)));
    }

    public boolean isBlacklisted(String token) {
        return isRevoked(token);
    }

    /** 简单的 SHA-256 十六进制摘要，避免为了一个哈希引入额外依赖。 */
    static final class Digest {
        private Digest() {
        }

        static String sha256Hex(String s) {
            try {
                java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
                byte[] d = md.digest(s.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                StringBuilder sb = new StringBuilder(d.length * 2);
                for (byte b : d) {
                    sb.append(Character.forDigit((b >> 4) & 0xF, 16));
                    sb.append(Character.forDigit(b & 0xF, 16));
                }
                return sb.toString();
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }
    }
}
