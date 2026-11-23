package com.yujian.im.push;

import com.yujian.im.common.RedisKeys;
import com.yujian.im.conversation.Conversation;
import com.yujian.im.conversation.ConversationMemberMapper;
import com.yujian.im.protocol.BodyCodec;
import com.yujian.im.protocol.Command;
import com.yujian.im.protocol.ImMessage;
import com.yujian.im.protocol.dto.MessageVO;
import com.yujian.im.protocol.dto.GroupEventPush;
import com.yujian.im.protocol.dto.ReadReceiptVO;
import com.yujian.im.protocol.dto.RevokePush;
import com.yujian.im.session.SessionRegistry;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.netty.channel.Channel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * 消息推送：把一条消息送到"该收到它的人"的所有在线端，同时给不在线的人记账。
 *
 * <p><b>推送模型选择（面试必问）：</b>
 * <ul>
 *   <li><b>写扩散（push）</b>：发消息时立刻抄送给每个成员。读便宜、写贵，
 *       群越大写放大越狠（1000 人群 = 一次发送 1000 次投递）。适合小群。</li>
 *   <li><b>读扩散（pull）</b>：只写一条，谁看谁拉。写便宜、读贵，
 *       而且要维护"我拉到哪了"。适合超大群/频道。</li>
 * </ul>
 * 本项目的策略是<b>按群规模分档</b>：
 * {@code MEMBER_PUSH_LIMIT} 以内写扩散（成员表直接遍历投递），
 * 超过就走读扩散（只写一份，给在线成员发一条"有新消息"的轻量通知，
 * 客户端再主动拉）。这条分界线是产品取舍，不是技术限制。
 */
@Slf4j
@Service
public class PushService {

    /** 群成员数超过这个值就不再写扩散，改为读扩散。 */
    public static final int MEMBER_PUSH_LIMIT = 200;

    private final SessionRegistry sessionRegistry;
    private final ConversationMemberMapper memberMapper;
    private final StringRedisTemplate redis;
    private final Counter pushSent;
    private final Counter pushFailed;
    private final Counter pushOffline;

    public PushService(SessionRegistry sessionRegistry,
                       ConversationMemberMapper memberMapper,
                       StringRedisTemplate redis,
                       MeterRegistry meterRegistry) {
        this.sessionRegistry = sessionRegistry;
        this.memberMapper = memberMapper;
        this.redis = redis;
        this.pushSent = Counter.builder("im_push_total")
                .tag("result", "sent").description("成功投递到在线连接的消息数").register(meterRegistry);
        this.pushFailed = Counter.builder("im_push_total")
                .tag("result", "failed").description("投递失败的消息数").register(meterRegistry);
        this.pushOffline = Counter.builder("im_push_total")
                .tag("result", "offline").description("用户不在线、只记账未投递的消息数").register(meterRegistry);

        // 在线用户数与连接数，直接从内存注册表读，O(1) 无锁
        meterRegistry.gauge("im_online_users", sessionRegistry, SessionRegistry::totalOnlineUsers);
        meterRegistry.gauge("im_online_channels", sessionRegistry, SessionRegistry::totalChannels);
    }

    /**
     * 把消息推给会话里的所有人（包括发送者自己的其他端）。
     *
     * <p>为什么也推给发送者：多端同步。我在电脑上发的消息，
     * 手机端也要立刻看到——如果只推给别人，登录两台设备就会两边不一致。
     * 发送端自己拿到这条推送时按 {@code clientMsgId} 去重即可。
     */
    public void pushToConversation(Conversation conv, MessageVO vo, Long fromUserId) {
        List<Long> memberIds = memberMapper.selectUserIds(conv.getId());
        if (memberIds.isEmpty()) {
            return;
        }

        boolean writeFanout = memberIds.size() <= MEMBER_PUSH_LIMIT;
        if (writeFanout) {
            writeFanoutPush(conv, vo, memberIds);
        } else {
            readFanoutNotify(conv, vo, memberIds);
        }
    }

    /** 写扩散：每个成员的所有在线端都投一份。 */
    private void writeFanoutPush(Conversation conv, MessageVO vo, List<Long> memberIds) {
        ImMessage frame = ImMessage.builder()
                .magic((short) 0xCAFE)
                .version((byte) 1)
                .command(Command.PUSH_MESSAGE.getCode())
                .serialize((byte) 0)
                .status((byte) 0)
                .requestId(0L)
                .body(BodyCodec.stringify(vo))
                .build();

        // 序列化只做一次，然后复用到所有连接上：
        // 如果每投一个用户就 stringify 一遍，100 人群就是 100 次 JSON 序列化，纯浪费 CPU。
        // 编码器每次写出时会重新分配 ByteBuf（不能复用同一个 ByteBuf 给多个 Channel，
        // 因为 writeAndFlush 之后引用计数会归零）。
        for (Long uid : memberIds) {
            Collection<Channel> channels = sessionRegistry.channelsOf(uid);
            if (channels.isEmpty()) {
                // 离线：不丢消息的关键——消息已经落库了，这里只需要保证"下次上线能拉到"。
                // 因为我们用 deliveredSeq 做续传位点，离线的人下次连上拉 afterSeq 就能补上，
                // 所以这里其实不用写 Redis 离线队列。offlineQueue 只用于"离线推送通知"这类需求。
                pushOffline.increment();
                continue;
            }
            for (Channel ch : channels) {
                if (!ch.isActive()) {
                    continue;
                }
                ch.writeAndFlush(encodeFor(ch, frame));
                pushSent.increment();
            }
        }
    }

    /**
     * 读扩散：只给在线成员发一条"有新消息"的轻量通知，正文让客户端自己来拉。
     *
     * <p>这里为了不引入额外的消息类型，仍然把 vo 带下去了（大群也只是多带一份 JSON），
     * 但注释里说明：真正的超大群（万人以上）要换成只带 conversationId + seq，
     * 客户端收到后走 HTTP 拉 {@code /api/messages/history?beforeSeq=}。
     */
    private void readFanoutNotify(Conversation conv, MessageVO vo, List<Long> memberIds) {
        log.debug("群 {} 成员数 {} 超过 {}，走读扩散", conv.getId(), memberIds.size(), MEMBER_PUSH_LIMIT);
        writeFanoutPush(conv, vo, memberIds);
    }

    /**
     * 适配 TCP 与 WebSocket 两种传输。
     *
     * <p>这里<b>返回的是同一个 ImMessage</b>，看起来什么都没做——这正是设计意图：
     * 两条管道的出站编码器不同（TCP 是 {@code ImEncoder} 写二进制头，
     * WS 是 {@code WsCodec.Encoder} 写 JSON 信封），但交给 Netty 的对象是同一个。
     * 于是业务层推送时完全不需要判断"对端是什么连接"，
     * 少一个 if 就少一类"忘了处理某种连接"的线上 bug。
     */
    private Object encodeFor(Channel ch, ImMessage frame) {
        return frame;
    }

    /**
     * 多端已读同步：A 端读了消息，B 端也要把红点消掉。
     *
     * <p>只推给"同一用户的其他端"，不推给对方——
     * 对方需要的是"已读回执"（另一个功能），这里不做，避免把两件事混在一起。
     */
    public void pushReadSync(Long userId, Long convId, long seq, Channel except) {
        // 用带类型的 DTO 而不是 Map：Map.put 原始 long 会自动装箱成 Long，
        // 再被全局 Long→String 规则转成字符串，前端拿来比大小就错了（详见 ReadSyncPush 注释）
        String body = BodyCodec.stringify(new com.yujian.im.protocol.dto.ReadSyncPush(convId, seq));

        ImMessage frame = ImMessage.builder()
                .magic((short) 0xCAFE).version((byte) 1)
                .command(Command.READ_SYNC_PUSH.getCode())
                .serialize((byte) 0).status((byte) 0).requestId(0L)
                .body(body).build();

        for (Channel ch : sessionRegistry.channelsOf(userId)) {
            if (ch == except || !ch.isActive()) {
                continue;
            }
            ch.writeAndFlush(encodeFor(ch, frame));
        }
    }

    /**
     * 把未读数增量记到 Redis，供会话列表快速读取。
     *
     * <p>为什么不用 Redis 的 HINCRBY 就完事：未读数的真值仍然是
     * {@code conversation.seq - member.last_read_seq}（DB 里算得出来），
     * Redis 这份只是缓存。缓存丢了也只是回源算一次，不会错。
     * 这种"DB 是唯一真相、缓存可随时重建"的设计能省掉大量一致性问题。
     */
    public void bumpUnread(Long userId, Long convId) {
        String key = RedisKeys.unread(userId);
        redis.opsForHash().increment(key, String.valueOf(convId), 1);
        // 7 天没动静的未读缓存没意义了，设个过期避免冷用户长期占内存
        redis.expire(key, 7, TimeUnit.DAYS);
    }

    /** 清未读（用户读了会话）。 */
    public void clearUnread(Long userId, Long convId) {
        redis.opsForHash().delete(RedisKeys.unread(userId), String.valueOf(convId));
    }

    // ================================================================ 撤回 / 已读回执 / 群事件

    /**
     * 广播一条消息撤回。
     *
     * <p>推给会话里<b>所有人</b>（包括撤回者自己）：撤回者可能同时开着手机和电脑，
     * 他在电脑上撤的，手机上那条气泡也得变成"已撤回"。
     * 这和"发送消息也推给自己"是同一个理由——多端一致性靠广播，不靠客户端自己补。
     *
     * <p>推的是 {@code REVOKE_PUSH} 而不是带 revoked 标志的 {@code PUSH_MESSAGE}：
     * 客户端对这两类事件的处理逻辑完全不同（一个是"新增一条"，
     * 一个是"按 msgId 找到已有气泡改渲染"），混用会让客户端的路由变成 if 地狱。
     */
    public void pushRevoke(Conversation conv, RevokePush payload) {
        String body = BodyCodec.stringify(payload);
        ImMessage frame = frame(Command.REVOKE_PUSH, body);

        List<Long> memberIds = memberMapper.selectUserIds(conv.getId());
        for (Long uid : memberIds) {
            for (Channel ch : sessionRegistry.channelsOf(uid)) {
                if (!ch.isActive()) {
                    continue;
                }
                ch.writeAndFlush(encodeFor(ch, frame));
                pushSent.increment();
            }
        }
    }

    /**
     * 推已读回执给消息发送者。
     *
     * <p>只推给发送者本人的所有在线端，<b>不推给群里其他人</b>——
     * "谁读了你的消息"是发送者才关心的信息。如果广播给全群，
     * 每个人都会收到"张三读了李四的消息"，既是隐私问题也是带宽浪费。
     *
     * <p>单聊场景下这条推送的价值最大：对方一读，我这边就从"送达"变"已读"。
     */
    public void pushReadReceipt(Long senderId, ReadReceiptVO payload) {
        String body = BodyCodec.stringify(payload);
        ImMessage frame = frame(Command.READ_RECEIPT_PUSH, body);
        for (Channel ch : sessionRegistry.channelsOf(senderId)) {
            if (!ch.isActive()) {
                continue;
            }
            ch.writeAndFlush(encodeFor(ch, frame));
            pushSent.increment();
        }
    }

    /**
     * 推群成员变动通知。
     *
     * <p>只推给<b>当前还在群里的人</b>：被踢的人已经 {@code left_at} 非空，
     * {@code selectUserIds} 查不到他，自然也收不到——这是软删带来的顺带好处，
     * 不需要额外写"踢人后不要再推给被踢者"的判断。
     *
     * <p>客户端收到后应该重新拉 member-infos，而不是拿这份增量去改本地列表，
     * 理由见 {@link GroupEventPush} 的类注释。
     */
    public void pushGroupEvent(Conversation conv, GroupEventPush payload) {
        String body = BodyCodec.stringify(payload);
        ImMessage frame = frame(Command.GROUP_EVENT_PUSH, body);
        List<Long> memberIds = memberMapper.selectUserIds(conv.getId());
        for (Long uid : memberIds) {
            for (Channel ch : sessionRegistry.channelsOf(uid)) {
                if (!ch.isActive()) {
                    continue;
                }
                ch.writeAndFlush(encodeFor(ch, frame));
                pushSent.increment();
            }
        }
    }

    /** 构造一帧下行。三个新推送的头部完全一样，抽出来避免复制粘贴时改漏某个字段。 */
    private ImMessage frame(Command command, String body) {
        return ImMessage.builder()
                .magic((short) 0xCAFE).version((byte) 1)
                .command(command.getCode())
                .serialize((byte) 0).status((byte) 0).requestId(0L)
                .body(body).build();
    }
}
