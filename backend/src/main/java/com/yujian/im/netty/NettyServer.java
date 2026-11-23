package com.yujian.im.netty;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.util.concurrent.DefaultThreadFactory;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Netty 服务端：在同一进程里开两个端口。
 *
 * <p><b>为什么是一个进程两个端口，而不是两个进程：</b>
 * 两个端口只是"传输协议不同"，业务逻辑、Spring 容器、DB/Redis 连接池完全一样。
 * 分两个进程就要多付一份 JVM 内存 + 多一份连接池，而且登录态（本地 SessionRegistry）
 * 还得分两处同步——纯粹给自己找麻烦。真实的 IM 网关确实是独立进程，
 * 但那是为了独立扩缩容，不是在练手项目里该操心的事。
 *
 * <p><b>为什么要在 {@code ApplicationReadyEvent} 里启动而不是 {@code @PostConstruct}：</b>
 * 如果放在 Bean 初始化阶段，Netty 已经开始 accept 连接，但 Spring 容器还没完全就绪
 * （比如 Mapper 代理、事务管理器还没装配好），第一批请求会踩到 {@code NullPointerException}。
 * 等到 ApplicationReadyEvent 才是"整个应用真的可以干活了"的信号。
 *
 * <p><b>线程模型（面试必答）：</b>
 * <pre>
 * bossGroup  ：1 个线程，只负责 accept 新连接，然后把 Channel 注册给 worker
 * workerGroup：默认 CPU 核数 * 2 个线程，每个 Channel 固定绑在其中一个上，之后它的所有事件
 *              都在这个线程里串行执行 —— 所以业务 Handler 里不需要加锁，但也绝不能阻塞
 * </pre>
 */
@Slf4j
@Component
public class NettyServer {

    private final TcpChannelInitializer tcpInitializer;
    private final WsChannelInitializer wsInitializer;
    private final int tcpPort;
    private final int wsPort;
    private final int bossThreads;
    private final int workerThreads;

    private EventLoopGroup bossGroup;
    private EventLoopGroup workerGroup;
    private final List<Channel> serverChannels = new ArrayList<>();

    public NettyServer(TcpChannelInitializer tcpInitializer,
                       WsChannelInitializer wsInitializer,
                       @Value("${im.netty.tcp-port:9000}") int tcpPort,
                       @Value("${im.netty.ws-port:9001}") int wsPort,
                       @Value("${im.netty.boss-threads:1}") int bossThreads,
                       @Value("${im.netty.worker-threads:0}") int workerThreads) {
        this.tcpInitializer = tcpInitializer;
        this.wsInitializer = wsInitializer;
        this.tcpPort = tcpPort;
        this.wsPort = wsPort;
        this.bossThreads = bossThreads;
        this.workerThreads = workerThreads;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void start() {
        int workers = workerThreads > 0 ? workerThreads : Runtime.getRuntime().availableProcessors() * 2;
        bossGroup = new NioEventLoopGroup(bossThreads, new DefaultThreadFactory("im-boss"));
        workerGroup = new NioEventLoopGroup(workers, new DefaultThreadFactory("im-worker"));

        try {
            bind("tcp", tcpPort, tcpInitializer);
            bind("websocket", wsPort, wsInitializer);
            log.info("Netty 启动完成: TCP={}, WebSocket={}, worker 线程数={}", tcpPort, wsPort, workers);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Netty 启动被中断", e);
        }
    }

    private void bind(String name, int port, io.netty.channel.ChannelInitializer<io.netty.channel.socket.SocketChannel> init)
            throws InterruptedException {
        ServerBootstrap b = new ServerBootstrap();
        b.group(bossGroup, workerGroup)
                .channel(NioServerSocketChannel.class)
                .childHandler(init)
                // SO_BACKLOG：已完成三次握手但还没被 accept 的连接队列长度。
                // 太小会在突发流量时直接拒绝连接（客户端看到 connect timeout）
                .option(ChannelOption.SO_BACKLOG, 1024)
                // TCP_NODELAY：禁用 Nagle 算法。IM 的消息都是小包，
                // Nagle 会把小包攒起来再发（最多等 40ms），对实时聊天是灾难
                .childOption(ChannelOption.TCP_NODELAY, true)
                // SO_KEEPALIVE：让操作系统也帮忙探测死连接（默认 2 小时，聊胜于无）
                .childOption(ChannelOption.SO_KEEPALIVE, true);

        Channel ch = b.bind(port).sync().channel();
        serverChannels.add(ch);
        log.info("{} 端口 {} 绑定成功", name, port);
    }

    /**
     * 优雅关闭。顺序很重要：先关监听（不再接新连接），再关 worker（处理完手头的活），
     * 最后关主线程组。反过来的话，正在处理的请求会被硬切。
     */
    @PreDestroy
    public void stop() {
        log.info("Netty 正在关闭...");
        for (Channel ch : serverChannels) {
            ch.close();
        }
        if (workerGroup != null) {
            // quietPeriod=0，超时 3s，超时后强制关
            workerGroup.shutdownGracefully(0, 3, java.util.concurrent.TimeUnit.SECONDS);
        }
        if (bossGroup != null) {
            bossGroup.shutdownGracefully(0, 3, java.util.concurrent.TimeUnit.SECONDS);
        }
        log.info("Netty 已关闭");
    }
}
