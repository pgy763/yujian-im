package com.yujian.im.netty;

import com.yujian.im.netty.handler.ImDispatcher;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.socket.SocketChannel;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.handler.codec.http.websocketx.WebSocketServerProtocolHandler;
import io.netty.handler.stream.ChunkedWriteHandler;
import io.netty.handler.timeout.IdleStateHandler;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

/**
 * WebSocket 通道的流水线。
 *
 * <p>WebSocket 的握手是"HTTP Upgrade"，所以整条流水线必须先是 HTTP 编解码器，
 * 握手成功之后这些 Handler 会被 {@code WebSocketServerProtocolHandler} 自动替换掉，
 * 之后就只剩 WebSocketFrame 在管道里流动。这是 Netty 处理 WS 的标准套路，顺序不能错：
 * <pre>
 * HttpServerCodec → HttpObjectAggregator → ChunkedWriteHandler
 *   → IdleStateHandler → WebSocketServerProtocolHandler → WsCodec.Decoder → WsCodec.Encoder → ImDispatcher
 * </pre>
 *
 * <p><b>为什么需要 {@code HttpObjectAggregator}：</b>
 * HTTP 解码器本身是"流式"的，一个请求可能被拆成 HttpRequest + 若干 HttpContent。
 * WebSocket 握手需要完整的 HttpRequest（带 headers），所以要把分片聚合起来，
 * 参数 65536 是单个 HTTP 消息的最大字节数（握手包远小于这个值）。
 *
 * <p><b>为什么需要 {@code ChunkedWriteHandler}：</b>
 * 它是给大文件/大帧做分块写用的。WebSocket 握手阶段用不到，
 * 但如果将来推送大图片（binary frame），没有它会一次性占满内存。
 */
@Component
public class WsChannelInitializer extends ChannelInitializer<SocketChannel> {

    private final ImDispatcher dispatcher;
    private final String wsPath;
    private final int readerIdleSeconds;

    public WsChannelInitializer(ImDispatcher dispatcher,
                                @Value("${im.netty.ws-path:/im}") String wsPath,
                                @Value("${im.netty.reader-idle-seconds:90}") int readerIdleSeconds) {
        this.dispatcher = dispatcher;
        this.wsPath = wsPath;
        this.readerIdleSeconds = readerIdleSeconds;
    }

    @Override
    protected void initChannel(SocketChannel ch) {
        ChannelPipeline p = ch.pipeline();
        p.addLast(new HttpServerCodec());
        p.addLast(new HttpObjectAggregator(65536));
        p.addLast(new ChunkedWriteHandler());
        p.addLast(new IdleStateHandler(readerIdleSeconds, 0, 0, TimeUnit.SECONDS));
        p.addLast(new IdleCloseHandler());
        // checkStartsWith=false：路径必须完全等于 /im，避免 /imxxx 也能连上来
        p.addLast(new WebSocketServerProtocolHandler(wsPath, null, true, 65536));
        // 握手完成后，把业务帧在 JSON 信封 <-> ImMessage 之间转换
        p.addLast(new WsCodec.Decoder());
        p.addLast(new WsCodec.Encoder());
        p.addLast(dispatcher);
    }
}
