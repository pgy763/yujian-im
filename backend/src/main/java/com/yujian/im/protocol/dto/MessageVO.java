package com.yujian.im.protocol.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 下行：消息体。单聊响应、群聊响应、推送、离线消息拉取，全部复用这一个结构，
 * 客户端只需要一套解析逻辑。这是"协议设计要收敛"的体现。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MessageVO {
    /** 服务端消息 ID（雪花），全局唯一，客户端用它做已读回执和去重。 */
    private Long msgId;
    private Long conversationId;
    /**
     * 会话内序号，客户端按它排序。
     *
     * <p>这里刻意用原始类型 {@code long} 而不是 {@code Long}：seq 是"计数器"语义，
     * 前端要拿它做 {@code Math.max} 和排序，必须是 JSON 数字；
     * 而 ID 是"标识符"语义，被全局 Long→String 规则转成字符串才安全
     * （见 {@code com.yujian.im.common.JsonModules}）。
     */
    private long seq;
    private Long fromUserId;
    private String fromNickname;
    private Integer type;
    private String content;
    private LocalDateTime createdAt;
    /** 客户端回带的幂等键，方便客户端把"本地待发消息"换成服务端消息。 */
    private String clientMsgId;
    /** true 表示这是重发命中了幂等，服务端没有重新入库。 */
    private Boolean duplicate;
    /**
     * 是否已撤回。true 时 {@link #content} 已经被服务端替换成空串，
     * 前端渲染成"xx 撤回了一条消息"。
     *
     * <p>为什么服务端要把 content 置空而不是让前端自己藏起来：
     * 前端藏起来只是"看不见"，F12 一开原文还在 —— 撤回的语义是
     * "这条内容不该再传播了"，所以敏感数据根本不能下发。
     * 数据库里保留原文只是为了审计和误撤恢复，那是服务端内部的事。
     */
    private Boolean revoked;
    /** 撤回时间，前端显示"xx 撤回了一条消息"没有具体时间也行，留着给详情面板。 */
    private LocalDateTime revokedAt;
}
