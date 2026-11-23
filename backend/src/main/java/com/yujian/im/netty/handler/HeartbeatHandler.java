package com.yujian.im.netty.handler;

import com.yujian.im.netty.ImResponses;
import com.yujian.im.protocol.Command;
import com.yujian.im.protocol.ImMessage;
import io.netty.channel.ChannelHandlerContext;
import org.springframework.stereotype.Component;

/**
 * 心跳。
 *
 * <p><b>为什么长连接一定要有心跳（面试高频）：</b>
 * <ul>
 *   <li>TCP 连接在不发包时是"静默"的。中间的任何一环——家用路由器的 NAT 表、
 *       运营商网关、云厂商的 SLB——都有一个<b>空闲超时</b>（常见 60s ~ 5min），
 *       超时后连接被悄悄回收，两端都不知情，这就是所谓的"死连接"（half-open）；</li>
 *   <li>移动端切后台、地铁进隧道，网络说断就断，TCP 自己发现要很久（重传退避可能几分钟）；</li>
 *   <li>心跳还能测 RTT，客户端据此判断"网络很差"还是"服务端挂了"。</li>
 * </ul>
 *
 * <p>所以心跳不是"为了防止连接断开"，而是<b>让各方都能尽早知道连接已经断了</b>。
 * 本项目的双重保障：
 * <ol>
 *   <li>客户端每 30s 发一次 HEARTBEAT_REQ；</li>
 *   <li>服务端挂 {@code IdleStateHandler(readerIdle=90s)}，读数超时直接关连接——
 *       不依赖客户端"自觉"，防止半死连接长期占着内存和 fd。</li>
 * </ol>
 */
@Component
public class HeartbeatHandler implements CommandHandler {

    @Override
    public Command command() {
        return Command.HEARTBEAT_REQ;
    }

    @Override
    public void handle(ChannelHandlerContext ctx, ImMessage msg) {
        // 原样带回 requestId，客户端用"发出时间 → 收到时间"算 RTT
        ImResponses.write(ctx.channel(), msg.getRequestId(), Command.HEARTBEAT_RESP,
                (byte) 0, String.valueOf(System.currentTimeMillis()));
    }
}
