package com.yujian.im.auth;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;

/**
 * JWT 签发与校验。
 *
 * <p>为什么用 JWT 而不是 Session：
 * <ul>
 *   <li>长连接场景下，Netty 不在 Servlet 容器里，拿不到 HttpSession，校验 Session 得回查一次存储；</li>
 *   <li>JWT 自带签名，服务端只需要一把密钥就能验，天然适合"HTTP 登录 → 拿 token → 长连接上带 token"的流程；</li>
 *   <li>水平扩容时不需要会话粘滞。</li>
 * </ul>
 *
 * <p>代价：token 签发后无法单方面作废（除非引入黑名单）。
 * 这里用 Redis 黑名单解决"踢下线/改密码后旧 token 失效"——见 {@link TokenBlacklist}。
 */
@Slf4j
@Component
public class JwtService {

    private final SecretKey key;
    private final long expireMillis;

    public JwtService(@Value("${im.jwt.secret}") String secret,
                      @Value("${im.jwt.expire-hours}") long expireHours) {
        byte[] bytes = secret.getBytes(StandardCharsets.UTF_8);
        if (bytes.length < 32) {
            // jjwt 0.12 对 HS256 强制要求密钥 >= 256 bit，不够会抛 WeakKeyException
            throw new IllegalArgumentException("im.jwt.secret 至少需要 32 字节");
        }
        this.key = Keys.hmacShaKeyFor(bytes);
        this.expireMillis = expireHours * 3600_000L;
    }

    public String issue(Long userId, String username) {
        long now = System.currentTimeMillis();
        return Jwts.builder()
                .subject(String.valueOf(userId))
                .claim("username", username)
                .issuedAt(new Date(now))
                .expiration(new Date(now + expireMillis))
                .signWith(key)
                .compact();
    }

    /**
     * 校验并解析。失败一律返回 null，由调用方决定回 401 还是直接断连接。
     */
    public Claims parse(String token) {
        try {
            return Jwts.parser()
                    .verifyWith(key)
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();
        } catch (JwtException | IllegalArgumentException e) {
            log.debug("token 校验失败: {}", e.getMessage());
            return null;
        }
    }

    public Long userIdOf(String token) {
        Claims c = parse(token);
        return c == null ? null : Long.valueOf(c.getSubject());
    }

    /**
     * 取 token 的过期时间。登出时要把黑名单 TTL 设成"剩余有效期"，
     * 这样过期后 key 自动消失，Redis 不会无限增长。
     */
    public java.time.Instant expireAt(String token) {
        Claims c = parse(token);
        return c == null || c.getExpiration() == null ? null : c.getExpiration().toInstant();
    }
}
