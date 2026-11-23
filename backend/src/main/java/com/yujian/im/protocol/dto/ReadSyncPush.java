package com.yujian.im.protocol.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

/**
 * 下行：多端已读同步（READ_SYNC_PUSH）。
 *
 * <p>为什么单独建这个类而不是用 {@code Map<String, Object>} 拼：
 * 一开始就是用 Map 拼的，结果犯了两个错 ——
 * <ol>
 *   <li>{@code Map.put("lastReadSeq", seq)} 里 {@code seq} 是原始 {@code long}，
 *       自动装箱成 {@code Long}，然后被全局的 Long→String 规则序列化成了字符串
 *       {@code "19"}，前端拿它做 {@code Math.max(seq)} 就退化成字符串比较，
 *       "9" &gt; "19" 这种诡异结果会出现；</li>
 *   <li>Map 没有字段声明，协议文档无从生成，改字段时编译器一点忙都帮不上。</li>
 * </ol>
 * 用带类型的类就没这问题：{@code long} 永远是 JSON 数字，{@code Long}（conversationId）
 * 是 ID 语义，序列化成字符串正合适。
 */
@Data
@AllArgsConstructor
public class ReadSyncPush {
    /** 会话 ID（雪花，标识符语义 → 序列化成字符串）。 */
    private Long conversationId;
    /** 已读位点（计数器语义 → 原始 long，保持 JSON 数字）。 */
    private long lastReadSeq;
}
