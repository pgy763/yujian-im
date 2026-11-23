package com.yujian.im.protocol.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 撤回广播。下行 {@code REVOKE_PUSH}，发给会话里所有人（包括撤回者自己的其他端）。
 *
 * <p>只带 {@code msgId + seq + operatorId}，<b>不带被撤回的原文</b>。
 * 客户端拿到后按 msgId 找到本地那条气泡，把内容替换成"xx 撤回了一条消息"。
 * 如果客户端本地没有这条（比如刚重装），它下次拉历史时服务端下发的那条
 * 本来就带 revoked=true 且 content 为空 —— 两条路径汇合到同一个渲染结果。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class RevokePush {
    private Long conversationId;
    private Long msgId;
    /** 被撤回消息的会话内序号，客户端可以用它做定位，省一次按 msgId 的遍历。 */
    private long seq;
    /** 谁撤的。群聊里显示"群主撤回了 xx 的消息"时要区分操作者。 */
    private Long operatorId;
    private LocalDateTime revokedAt;
}
