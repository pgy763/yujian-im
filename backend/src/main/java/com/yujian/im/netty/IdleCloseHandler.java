package com.yujian.im.netty;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.handler.timeout.IdleState;
import io.netty.handler.timeout.IdleStateEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 空闲检测：读超时就关连接。
 *
 * <p>和客户端的"主动发心跳"是互补的两件事：
 * <ul>
 *   <li>客户端发心跳 → 证明自己活着；</li>
 *   <li>服务端读超时 → 证明对方<b>没</b>活着（或者网络已经断了，只是 TCP 还没察觉）。</li>
 * </ul>
 * 只靠前者的话，一个崩溃的客户端不会发心跳，连接就永远挂着。
 * 一个挂着不动的连接占用：1 个 fd + 若干 KB 的 ByteBuf + Map 里的一个条目。
 * 十万个僵尸连接足以把一台机器拖垮，所以必须有服务端侧的兜底。
 */
@Slf4j
public class IdleCloseHandler extends ChannelInboundHandlerAdapter {

    @Override
    public void userEventTriggered(ChannelHandlerContext ctx, Object evt) throws Exception {
        if (evt instanceof IdleStateEvent e && e.state() == IdleState.READER_IDLE) {
            log.info("连接 {} 读空闲超时，主动关闭", ctx.channel().id().asShortText());
            ctx.close();
            return;
        }
        super.userEventTriggered(ctx, evt);
    }
}
