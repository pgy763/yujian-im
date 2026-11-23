package com.yujian.im.netty;

import com.yujian.im.netty.handler.ImDispatcher;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.socket.SocketChannel;
import io.netty.handler.timeout.IdleStateHandler;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

/**
 * 原生 TCP 通道的流水线。
 *
 * <p><b>Pipeline 顺序是有讲究的，面试常考：</b>
 * <pre>
 * 入站（客户端 → 服务端）：IdleStateHandler → ImDecoder → ImDispatcher
 * 出站（服务端 → 客户端）：ImEncoder ← ImDispatcher
 * </pre>
 * <ul>
 *   <li>{@code IdleStateHandler} 放在最前面：它只关心"有没有字节到达"，
 *       需要看到最原始的读事件，放在解码器后面反而会被"半包时已经消费了字节"搞乱；</li>
 *   <li>{@code ImDecoder} 必须在业务 Handler 之前，把字节流变成 POJO；</li>
 *   <li>{@code ImEncoder} 是出站，Netty 会从 pipeline 尾部往前找，所以写在业务 Handler 后面。</li>
 * </ul>
 */
@Component
public class TcpChannelInitializer extends ChannelInitializer<SocketChannel> {

    private final ImDispatcher dispatcher;
    private final int readerIdleSeconds;

    public TcpChannelInitializer(ImDispatcher dispatcher,
                                 @Value("${im.netty.reader-idle-seconds:90}") int readerIdleSeconds) {
        this.dispatcher = dispatcher;
        this.readerIdleSeconds = readerIdleSeconds;
    }

    @Override
    protected void initChannel(SocketChannel ch) {
        ChannelPipeline p = ch.pipeline();
        // 读空闲检测：90s 没收到任何字节 → 触发 IdleStateEvent → IdleCloseHandler 关连接
        p.addLast(new IdleStateHandler(readerIdleSeconds, 0, 0, TimeUnit.SECONDS));
        // 注意：必须每条连接 new 一个，不能把 Handler 存成字段复用一个实例。
        // 没有 @Sharable 的 Handler 被重复加入 pipeline 会抛 ChannelPipelineException
        // （日志里表现为 "Failed to initialize a channel"）。
        p.addLast(new IdleCloseHandler());
        // 拆包/粘包
        p.addLast(new com.yujian.im.protocol.ImDecoder());
        // 业务分发（出站编码也由它内部的 ImMessage 触发）
        p.addLast(new com.yujian.im.protocol.ImEncoder());
        p.addLast(dispatcher);
    }
}
