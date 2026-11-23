package com.yujian.im.auth;

import com.yujian.im.common.BizException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * HTTP 侧鉴权拦截器。
 *
 * <p>只保护 /api/** 里"需要知道我是谁"的接口，登录注册放行。
 * 把 userId 塞进 request attribute，Controller 用 {@link CurrentUser} 取。
 */
@Component
@RequiredArgsConstructor
public class AuthInterceptor implements HandlerInterceptor {

    public static final String ATTR_USER_ID = "userId";

    private final JwtService jwtService;
    private final TokenBlacklist tokenBlacklist;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        // 预检请求直接放行，否则浏览器跨域时会先撞 401
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
            return true;
        }

        String auth = request.getHeader("Authorization");
        if (auth == null || !auth.startsWith("Bearer ")) {
            throw BizException.unauthorized("缺少 Authorization 头");
        }
        String token = auth.substring(7);

        if (tokenBlacklist.isRevoked(token)) {
            throw BizException.unauthorized("登录已失效，请重新登录");
        }
        Long userId = jwtService.userIdOf(token);
        if (userId == null) {
            throw BizException.unauthorized("token 无效或已过期");
        }

        request.setAttribute(ATTR_USER_ID, userId);
        return true;
    }
}
