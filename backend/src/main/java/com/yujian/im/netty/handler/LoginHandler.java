package com.yujian.im.netty.handler;

import com.yujian.im.auth.JwtService;
import com.yujian.im.auth.TokenBlacklist;
import com.yujian.im.protocol.BodyCodec;
import com.yujian.im.protocol.Command;
import com.yujian.im.protocol.ImMessage;
import com.yujian.im.protocol.dto.LoginReq;
import com.yujian.im.protocol.dto.LoginResp;
import com.yujian.im.netty.ImResponses;
import com.yujian.im.session.SessionRegistry;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.Channel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 登录（在长连接上绑定身份）。
 *
 * <p><b>为什么还要"登录"一次——HTTP 不是已经登录过了吗：</b>
 * HTTP 登录拿到的 token 只是在 HTTP 侧被验证过，而长连接是另一条独立通道。
 * 如果不在这条通道上验一次 token，任何人 TCP 连上来就能直接发消息，
 * 服务端无法知道他是谁。所以流程是：
 * <b>HTTP 登录 → 拿 token → 建立长连接 → 长连接上再提交一次 token</b>。
 *
 * <p>这也是主流 IM 的标准做法：Token 复用，连接级再做一次绑定。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LoginHandler implements CommandHandler {

    private final JwtService jwtService;
    private final TokenBlacklist blacklist;
    private final SessionRegistry sessionRegistry;

    @Override
    public Command command() {
        return Command.LOGIN_REQ;
    }

    @Override
    public void handle(ChannelHandlerContext ctx, ImMessage msg) {
        long requestId = msg.getRequestId();
        LoginReq req = BodyCodec.fromJson(msg.getBody(), LoginReq.class);

        if (req == null || req.getToken() == null || req.getToken().isBlank()) {
            reply(ctx, requestId, false, null, "缺少 token");
            return;
        }
        if (blacklist.isBlacklisted(req.getToken())) {
            reply(ctx, requestId, false, null, "token 已失效，请重新登录");
            return;
        }
        Long userId = jwtService.userIdOf(req.getToken());
        if (userId == null) {
            reply(ctx, requestId, false, null, "token 无效或已过期");
            return;
        }

        Channel ch = ctx.channel();

        // 同一个连接重复登录：把旧身份摘掉，避免一条连接挂在两个 userId 下
        Long previous = ch.attr(SessionRegistry.USER_ID).get();
        if (previous != null && !previous.equals(userId)) {
            sessionRegistry.unregister(ch);
        }
        // 同一账号在别的设备上登录时，默认允许多端并存。
        // 若要"单端登录"，这里改成 sessionRegistry.kickOthers(userId, ch) 即可。
        sessionRegistry.register(userId, ch);
        ch.attr(SessionRegistry.DEVICE).set(req.getDevice() == null ? "unknown" : req.getDevice());

        log.info("长连接登录成功: userId={}, device={}, channel={}",
                userId, req.getDevice(), ch.id().asShortText());
        reply(ctx, requestId, true, userId, "ok");
    }

    private void reply(ChannelHandlerContext ctx, long requestId, boolean success,
                       Long userId, String message) {
        LoginResp resp = new LoginResp();
        resp.setSuccess(success);
        resp.setUserId(userId);
        resp.setMessage(message);
        resp.setServerTime(System.currentTimeMillis());
        ImResponses.write(ctx.channel(), requestId, Command.LOGIN_RESP,
                success ? (byte) 0 : (byte) 1, BodyCodec.toJson(resp));
    }
}
