package com.yujian.im.protocol;

import lombok.Getter;

/**
 * 指令枚举。命令字是 1 字节，最多 255 种，客户端和服务端各占一段，便于网关按命令字路由。
 */
@Getter
public enum Command {

    /** 登录：客户端带上 HTTP 登录拿到的 token，在长连接上做一次身份绑定。 */
    LOGIN_REQ((byte) 0x01),
    LOGIN_RESP((byte) 0x02),

    /** 心跳：IdleStateHandler 空闲触发，兼做 RTT 探测。 */
    HEARTBEAT_REQ((byte) 0x03),
    HEARTBEAT_RESP((byte) 0x04),

    /** 单聊。 */
    SINGLE_CHAT_REQ((byte) 0x05),
    SINGLE_CHAT_RESP((byte) 0x06),

    /** 群聊。 */
    GROUP_CHAT_REQ((byte) 0x07),
    GROUP_CHAT_RESP((byte) 0x08),

    /** 服务端主动推送（新消息到达）。 */
    PUSH_MESSAGE((byte) 0x09),

    /** 客户端 ack，表示"这条我已经落库/展示了"。 */
    ACK_REQ((byte) 0x0A),
    ACK_RESP((byte) 0x0B),

    /** 拉取离线消息。 */
    PULL_OFFLINE_REQ((byte) 0x0C),
    PULL_OFFLINE_RESP((byte) 0x0D),

    /** 多端已读同步。 */
    READ_SYNC_PUSH((byte) 0x0E),

    /** 客户端上报已读位点（多端同步的入口）。 */
    READ_SYNC_REQ((byte) 0x10),

    /** 被踢下线（同账号新连接挤掉旧连接，或异地登录）。 */
    KICK_PUSH((byte) 0x0F),

    /**
     * 消息撤回。客户端发 {@code REVOKE_REQ}（带 conversationId + msgId），
     * 服务端校验时间窗和身份后软删，回 {@code REVOKE_RESP}，
     * 并给会话里所有人（含自己其他端）推 {@code REVOKE_PUSH}。
     *
     * <p>为什么不复用 {@code PUSH_MESSAGE} 带一个 revoked=true：
     * 因为"新消息到达"和"老消息状态变了"是两类事件。混在一起，客户端
     * 收到后要先判断"这是新消息还是更新"，判断错了就会出现
     * "撤回的消息被当成新消息弹了个通知"。分开的命令字让客户端路由逻辑无歧义。
     */
    REVOKE_REQ((byte) 0x11),
    REVOKE_RESP((byte) 0x12),
    REVOKE_PUSH((byte) 0x13),

    /**
     * 已读回执：{@code READ_RECEIPT_REQ} 查"这条消息被谁读了"，
     * 服务端回 {@code READ_RECEIPT_RESP}；当别人读过你的消息时，
     * 服务端主动推 {@code READ_RECEIPT_PUSH} 给你。
     */
    READ_RECEIPT_REQ((byte) 0x14),
    READ_RECEIPT_RESP((byte) 0x15),
    READ_RECEIPT_PUSH((byte) 0x16),

    /**
     * 群成员变动通知（有人入群 / 退群 / 被踢 / 群主转让）。
     *
     * <p>只做"通知"，不做"同步数据源"：客户端收到后要重新拉一次
     * {@code /api/conversations/{id}/member-infos}，而不是拿推送里的
     * 增量去改本地列表。原因：推送可能丢（离线），重连后没有补偿机制；
     * 而拉接口是幂等的，永远拿到全量真值。
     */
    GROUP_EVENT_PUSH((byte) 0x17),

    /** 通用错误。 */
    ERROR((byte) 0x7F);

    private final byte code;

    Command(byte code) {
        this.code = code;
    }

    public static Command of(byte code) {
        for (Command c : values()) {
            if (c.code == code) {
                return c;
            }
        }
        return null;
    }
}
