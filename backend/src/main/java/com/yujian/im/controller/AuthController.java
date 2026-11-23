package com.yujian.im.controller;

import com.yujian.im.auth.JwtService;
import com.yujian.im.auth.TokenBlacklist;
import com.yujian.im.common.R;
import com.yujian.im.user.User;
import com.yujian.im.user.UserService;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 认证接口。HTTP 只负责"发 token"，之后的消息收发全走长连接。
 *
 * <p><b>为什么分两条通道（面试常问）：</b>
 * <ul>
 *   <li>HTTP：登录、注册、拉会话列表、翻历史记录——这些是"一问一答、无状态、可以缓存"的操作，
 *       走 HTTP 能白嫖现成的鉴权/网关/限流/CDN 体系；</li>
 *   <li>长连接：消息收发、推送——需要"服务端主动推"，HTTP 做不到（轮询太费）。</li>
 * </ul>
 * 硬把所有东西塞进长连接，会让"翻历史"这种本来能加缓存的请求也占着一条连接，
 * 是常见的过度设计。
 */
@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final UserService userService;
    private final JwtService jwtService;
    private final TokenBlacklist blacklist;

    @Data
    public static class RegisterReq {
        @NotBlank(message = "用户名不能为空")
        @Size(min = 3, max = 32, message = "用户名长度 3~32")
        private String username;

        @NotBlank(message = "密码不能为空")
        @Size(min = 6, max = 64, message = "密码长度 6~64")
        private String password;

        private String nickname;
    }

    @Data
    public static class LoginReq {
        @NotBlank(message = "用户名不能为空")
        private String username;
        @NotBlank(message = "密码不能为空")
        private String password;
    }

    @PostMapping("/register")
    public R<Map<String, Object>> register(@RequestBody @jakarta.validation.Valid RegisterReq req) {
        User u = userService.register(req.getUsername(), req.getPassword(), req.getNickname());
        Map<String, Object> data = new LinkedHashMap<>(4);
        data.put("userId", u.getId());
        data.put("username", u.getUsername());
        data.put("nickname", u.getNickname());
        // 注册后直接给 token，省一次登录往返（前端体验更好）
        data.put("token", jwtService.issue(u.getId(), u.getUsername()));
        return R.ok(data);
    }

    @PostMapping("/login")
    public R<Map<String, Object>> login(@RequestBody @jakarta.validation.Valid LoginReq req) {
        User u = userService.login(req.getUsername(), req.getPassword());
        Map<String, Object> data = new LinkedHashMap<>(8);
        data.put("token", jwtService.issue(u.getId(), u.getUsername()));
        data.put("userId", u.getId());
        data.put("username", u.getUsername());
        data.put("nickname", u.getNickname());
        data.put("avatar", u.getAvatar());
        // 客户端用它决定长连接端口/路径（将来做灰度时特别有用）
        data.put("wsPath", "/im");
        data.put("wsPort", 9001);
        return R.ok(data);
    }

    /**
     * 登出：把当前 token 拉黑。
     *
     * <p>JWT 是无状态的，"登出"本来无处可写。这里的做法是把 token 的哈希塞进 Redis，
     * TTL 设为 token 剩余有效期——过期之后 key 自动消失，不会无限增长。
     */
    @PostMapping("/logout")
    public R<Void> logout(@RequestHeader(value = "Authorization", required = false) String authorization) {
        if (authorization != null && authorization.startsWith("Bearer ")) {
            String token = authorization.substring(7);
            java.time.Instant exp = jwtService.expireAt(token);
            long remain = exp == null
                    ? 0
                    : exp.toEpochMilli() - System.currentTimeMillis();
            blacklist.revoke(token, remain);
        }
        return R.ok();
    }
}
