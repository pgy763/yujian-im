package com.yujian.im.protocol.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 下行：会话详情（建单聊 / 建群聊的返回值）。
 *
 * <p>为什么不让 Controller 直接返回 {@link com.yujian.im.conversation.Conversation} 实体：
 * <ol>
 *   <li>实体会把 {@code pair_key}、{@code owner_id} 这类内部字段一起吐给前端，
 *       既是信息泄漏也是"改表就改协议"的隐患；</li>
 *   <li>实体里 {@code seq} 是 {@code Long}，会被全局 Long→String 规则转成字符串，
 *       而前端要拿它做 {@code Math.max}，必须是数字 —— 于是同一个"seq"
 *       在会话列表接口是数字、在建会话接口是字符串，前端要写两套逻辑。</li>
 * </ol>
 * 用 DTO 之后："ID 用 Long（→字符串），计数器用 long（→数字）"这条规则就有了明确的落点。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ConversationDetail {
    /** 会话 ID，雪花，标识符语义 → 序列化成字符串。 */
    private Long id;
    /** 1=单聊，2=群聊。 */
    private Integer type;
    private String name;
    /** 会话当前最大 seq，计数器语义 → 保持 JSON 数字。 */
    private long seq;
    private LocalDateTime createdAt;
}
