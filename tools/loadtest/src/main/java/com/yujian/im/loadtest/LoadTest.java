package com.yujian.im.loadtest;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.ByteToMessageDecoder;
import io.netty.handler.codec.MessageToByteEncoder;

import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 压测客户端：用 Netty 开 N 条长连接，按固定 QPS 打单聊消息，统计端到端延迟。
 *
 * <p><b>为什么单独写一个而不上 JMeter/wrk：</b>
 * <ol>
 *   <li>wrk 只测 HTTP，测不了我们这种自定义二进制长连接；</li>
 *   <li>JMeter 能用 TCP Sampler，但要写 18 字节头得靠 BeanShell 脚本，性能失真严重；</li>
 *   <li>自己写能把"发出去到收到响应"的时间戳精确卡在同一个 JVM 里，
 *       没有跨进程/跨机器的时钟误差，测出来的 RTT 才是服务端的真实处理时间。</li>
 * </ol>
 *
 * <p><b>用法：</b>
 * <pre>
 * java -jar im-loadtest.jar --conns 200 --qps 5 --duration 30
 * </pre>
 * 参数：
 * <ul>
 *   <li>{@code --host} 服务端地址，默认 127.0.0.1</li>
 *   <li>{@code --port} TCP 端口，默认 9000</li>
 *   <li>{@code --http} HTTP 端口，默认 8080（用来登录拿 token、建会话）</li>
 *   <li>{@code --conns} 并发连接数，默认 100</li>
 *   <li>{@code --qps} 每条连接每秒发多少条，默认 5</li>
 *   <li>{@code --duration} 压测秒数，默认 20</li>
 *   <li>{@code --users} 用几个不同账号（默认 2：alice 发、bob 收，真实走一遍写扩散）</li>
 *   <li>{@code --skip-login} 跳过建号，直接用已存在的账号</li>
 * </ul>
 *
 * <p><b>测的是什么：</b>发出 SINGLE_CHAT_REQ 到收到同 requestId 的 SINGLE_CHAT_RESP。
 * 这条路径完整覆盖：解码 → 分发 → 鉴权 → 消息落库（MySQL 事务）→ seq 发号（Redis INCR）
 * → 写扩散推送 → 响应编码。所以这个 RTT 就是"一条消息真正落库并回执"的耗时。
 */
public final class LoadTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final byte CMD_LOGIN_REQ = 0x01;
    private static final byte CMD_LOGIN_RESP = 0x02;
    private static final byte CMD_SINGLE_CHAT_REQ = 0x05;
    private static final byte CMD_SINGLE_CHAT_RESP = 0x06;

    // ---- 统计量，全进程共享 ----
    private static final AtomicLong SENT = new AtomicLong();
    private static final AtomicLong RECEIVED = new AtomicLong();
    private static final AtomicLong FAILED = new AtomicLong();
    private static final Map<Long, Long> SEND_NANOS = new ConcurrentHashMap<>();
    private static final List<Long> LATENCIES_NANOS = Collections.synchronizedList(new ArrayList<>());

    public static void main(String[] args) throws Exception {
        Config cfg = Config.parse(args);

        System.out.printf("== 鱼见 IM 压测 ==%n");
        System.out.printf("目标 %s:%d  连接数=%d  每连接QPS=%d  时长=%ds%n",
                cfg.host, cfg.port, cfg.conns, cfg.qps, cfg.duration);

        // 1) 先走 HTTP 把账号和会话准备好——长连接只管发消息，不重复做这些事
        HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
        String base = "http://" + cfg.host + ":" + cfg.httpPort;
        String senderToken = loginOrRegister(http, base, "loadtest_a");
        String receiverToken = loginOrRegister(http, base, "loadtest_b");
        long senderId = userIdOf(http, base, senderToken);
        long receiverId = userIdOf(http, base, receiverToken);
        long convId = ensureSingleConversation(http, base, senderToken, receiverId);
        System.out.printf("会话已就绪: convId=%d  sender=%d  receiver=%d%n", convId, senderId, receiverId);

        // 收方挂一条连接，保证推送真的有人收（不挂的话服务端会走离线写入分支，测的不是热路径）
        EventLoopGroup group = new NioEventLoopGroup(Math.min(cfg.conns, 8));
        Channel receiver = connect(group, cfg, receiverToken, cfg.host, cfg.port);
        Thread.sleep(500);

        // 2) 开 N 条发送连接（connect() 内部已等登录完成，所以这里循环结束就是全部就绪）
        List<Channel> channels = new ArrayList<>(cfg.conns);
        for (int i = 0; i < cfg.conns; i++) {
            channels.add(connect(group, cfg, senderToken, cfg.host, cfg.port));
        }
        System.out.println("全部连接登录完成，开始压测…");

        // 3) 每条连接一个独立定时器，按 qps 发
        ScheduledExecutorService sender = Executors.newScheduledThreadPool(
                Math.min(cfg.conns, Runtime.getRuntime().availableProcessors() * 2));
        long intervalMs = Math.max(1, 1000 / cfg.qps);
        AtomicLong requestIdGen = new AtomicLong(1);
        AtomicBoolean stop = new AtomicBoolean();

        for (Channel ch : channels) {
            sender.scheduleAtFixedRate(() -> {
                if (stop.get() || !ch.isActive()) {
                    return;
                }
                long rid = requestIdGen.getAndIncrement();
                SEND_NANOS.put(rid, System.nanoTime());
                String body = "{\"conversationId\":" + convId
                        + ",\"type\":1,\"content\":\"load-" + rid + "\""
                        + ",\"clientMsgId\":\"" + java.util.UUID.randomUUID() + "\"}";
                ch.writeAndFlush(frame(CMD_SINGLE_CHAT_REQ, rid, body));
                SENT.incrementAndGet();
            }, 0, intervalMs, TimeUnit.MILLISECONDS);
        }

        Thread.sleep(cfg.duration * 1000L);
        stop.set(true);
        sender.shutdown();
        Thread.sleep(2000); // 等在途响应回来

        // 4) 出报告
        report(cfg);

        channels.forEach(Channel::close);
        if (receiver != null) {
            receiver.close();
        }
        group.shutdownGracefully(0, 2, TimeUnit.SECONDS);
        System.exit(0);
    }

    // ------------------------------------------------------------------ 报告

    private static void report(Config cfg) {
        long sent = SENT.get();
        long recv = RECEIVED.get();
        long fail = FAILED.get();
        List<Long> lat;
        synchronized (LATENCIES_NANOS) {
            lat = new ArrayList<>(LATENCIES_NANOS);
        }
        Collections.sort(lat);

        System.out.println();
        System.out.println("================ 结果 ================");
        System.out.printf("发出请求      : %d%n", sent);
        System.out.printf("收到响应      : %d%n", recv);
        System.out.printf("失败/超时     : %d%n", fail);
        if (sent > 0) {
            System.out.printf("成功率        : %.2f%%%n", recv * 100.0 / sent);
            System.out.printf("实际吞吐      : %.1f msg/s%n", recv / (double) cfg.duration);
        }
        if (lat.isEmpty()) {
            System.out.println("没有采到延迟样本（可能全部失败）");
            return;
        }
        System.out.println("---------------- 延迟 ----------------");
        System.out.printf("P50           : %s ms%n", ms(pct(lat, 0.50)));
        System.out.printf("P95           : %s ms%n", ms(pct(lat, 0.95)));
        System.out.printf("P99           : %s ms%n", ms(pct(lat, 0.99)));
        System.out.printf("P999          : %s ms%n", ms(pct(lat, 0.999)));
        System.out.printf("Max           : %s ms%n", ms(lat.get(lat.size() - 1)));
        System.out.printf("Avg           : %s ms%n",
                ms((long) lat.stream().mapToLong(Long::longValue).average().orElse(0)));
        System.out.println("=====================================");
    }

    /** 取分位数。lat 必须已升序。索引用最近秩法，样本少时也不会越界。 */
    private static long pct(List<Long> lat, double p) {
        int idx = (int) Math.ceil(p * lat.size()) - 1;
        return lat.get(Math.max(0, Math.min(lat.size() - 1, idx)));
    }

    private static String ms(long nanos) {
        return BigDecimal.valueOf(nanos / 1_000_000.0).setScale(2, java.math.RoundingMode.HALF_UP).toPlainString();
    }

    // ------------------------------------------------------------------ HTTP 准备

    private static String loginOrRegister(HttpClient http, String base, String username) throws Exception {
        String body = "{\"username\":\"" + username + "\",\"password\":\"123456\"}";
        JsonNode reg = post(http, base + "/api/auth/register", body, null);
        if (reg.path("code").asInt(-1) == 0) {
            return reg.path("data").path("token").asText();
        }
        JsonNode login = post(http, base + "/api/auth/login", body, null);
        if (login.path("code").asInt(-1) != 0) {
            throw new IllegalStateException("登录失败: " + login);
        }
        return login.path("data").path("token").asText();
    }

    private static long userIdOf(HttpClient http, String base, String token) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create(base + "/api/users/me"))
                .header("Authorization", "Bearer " + token).GET().build();
        String resp = http.send(req, HttpResponse.BodyHandlers.ofString()).body();
        return MAPPER.readTree(resp).path("data").path("id").asLong();
    }

    private static long ensureSingleConversation(HttpClient http, String base, String token, long peerId)
            throws Exception {
        JsonNode r = post(http, base + "/api/conversations/single",
                "{\"targetUserId\":" + peerId + "}", token);
        if (r.path("code").asInt(-1) != 0) {
            throw new IllegalStateException("建会话失败: " + r);
        }
        return r.path("data").path("id").asLong();
    }

    private static JsonNode post(HttpClient http, String url, String body, String token) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
        if (token != null) {
            b.header("Authorization", "Bearer " + token);
        }
        String resp = http.send(b.build(), HttpResponse.BodyHandlers.ofString()).body();
        return MAPPER.readTree(resp);
    }

    // ------------------------------------------------------------------ Netty 连接

    private static Channel connect(EventLoopGroup group, Config cfg, String token,
                                   String host, int port) throws Exception {
        CountDownLatch loginLatch = new CountDownLatch(1);
        Bootstrap b = new Bootstrap();
        b.group(group)
                .channel(NioSocketChannel.class)
                .option(ChannelOption.TCP_NODELAY, true)
                .option(ChannelOption.SO_KEEPALIVE, true)
                .handler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel ch) {
                        ch.pipeline()
                                .addLast(new ImDecoder())
                                .addLast(new ImEncoder())
                                .addLast(new ClientHandler(loginLatch));
                    }
                });
        Channel ch = b.connect(host, port).sync().channel();
        // 登录帧
        long rid = 900000 + System.nanoTime() % 100000;
        ch.writeAndFlush(frame(CMD_LOGIN_REQ, rid,
                "{\"token\":\"" + token + "\",\"device\":\"loadtest\"}"));
        if (!loginLatch.await(10, TimeUnit.SECONDS)) {
            throw new IllegalStateException("登录超时");
        }
        return ch;
    }

    /** 出站编码：18 字节头 + JSON body。刻意不复用服务端的类，等于用第二份实现校验协议。 */
    private static ByteBuf frame(byte command, long requestId, String bodyJson) {
        byte[] body = bodyJson.getBytes(StandardCharsets.UTF_8);
        ByteBuf buf = io.netty.buffer.Unpooled.buffer(18 + body.length);
        buf.writeShort(0xCAFE);           // magic
        buf.writeByte(1);                 // version
        buf.writeByte(command);
        buf.writeByte(0);                 // serialize=JSON
        buf.writeByte(0);                 // status
        buf.writeLong(requestId);
        buf.writeInt(body.length);
        buf.writeBytes(body);
        return buf;
    }

    private static final class ClientHandler extends SimpleChannelInboundHandler<Frame> {
        private final CountDownLatch loginLatch;

        ClientHandler(CountDownLatch loginLatch) {
            this.loginLatch = loginLatch;
        }

        @Override
        protected void channelRead0(ChannelHandlerContext ctx, Frame f) {
            if (f.command == CMD_LOGIN_RESP) {
                loginLatch.countDown();
                return;
            }
            if (f.command == CMD_SINGLE_CHAT_RESP) {
                Long start = SEND_NANOS.remove(f.requestId);
                if (start != null) {
                    LATENCIES_NANOS.add(System.nanoTime() - start);
                }
                if (f.status == 0) {
                    RECEIVED.incrementAndGet();
                } else {
                    FAILED.incrementAndGet();
                }
            }
            // PUSH_MESSAGE / HEARTBEAT 之类直接忽略
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            FAILED.incrementAndGet();
            ctx.close();
        }
    }

    // ------------------------------------------------------------------ 极简协议编解码（压测侧）

    record Frame(byte command, long requestId, byte status, String body) {
    }

    /** 入站解码：逻辑和服务端 ImDecoder 一致（半包回退读指针、超长拒绝）。 */
    private static final class ImDecoder extends ByteToMessageDecoder {
        @Override
        protected void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) {
            if (in.readableBytes() < 18) {
                return;
            }
            in.markReaderIndex();
            in.readShort();               // magic
            in.readByte();                // version
            byte command = in.readByte();
            in.readByte();                // serialize
            byte status = in.readByte();
            long requestId = in.readLong();
            int len = in.readInt();
            if (in.readableBytes() < len) {
                in.resetReaderIndex();    // 半包，等下次再拼
                return;
            }
            byte[] body = new byte[len];
            in.readBytes(body);
            out.add(new Frame(command, requestId, status, new String(body, StandardCharsets.UTF_8)));
        }
    }

    private static final class ImEncoder extends MessageToByteEncoder<ByteBuf> {
        @Override
        protected void encode(ChannelHandlerContext ctx, ByteBuf msg, ByteBuf out) {
            // MessageToByteEncoder 会在 encode 之后自动 release 入参，这里不能再手工 release
            out.writeBytes(msg);
        }
    }

    // ------------------------------------------------------------------ 配置

    private static final class Config {
        String host = "127.0.0.1";
        int port = 9000;
        int httpPort = 8080;
        int conns = 100;
        int qps = 5;
        int duration = 20;

        static Config parse(String[] args) {
            Config c = new Config();
            for (int i = 0; i < args.length; i++) {
                String k = args[i];
                String v = i + 1 < args.length ? args[i + 1] : "";
                switch (k) {
                    case "--host" -> c.host = v;
                    case "--port" -> c.port = Integer.parseInt(v);
                    case "--http" -> c.httpPort = Integer.parseInt(v);
                    case "--conns" -> c.conns = Integer.parseInt(v);
                    case "--qps" -> c.qps = Integer.parseInt(v);
                    case "--duration" -> c.duration = Integer.parseInt(v);
                    default -> {
                        if (k.startsWith("--")) {
                            System.out.println("忽略未知参数: " + k);
                        }
                    }
                }
            }
            return c;
        }
    }
}
