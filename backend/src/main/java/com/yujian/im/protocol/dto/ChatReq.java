package com.yujian.im.protocol.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 上行：发消息请求。
 *
 * <p>clientMsgId 是关键字段：客户端在"发送前"生成它（比如 uuid），
 * 超时重发时带同一个 clientMsgId，服务端就能识别出这是同一条消息，
 * 直接返回上次落库的结果，不会重复入库、也不会重复推送。
 * 这就是"不重"的实现方式——不是靠客户端不重发，而是靠服务端能识别重发。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ChatReq {
    /** 目标会话 ID。单聊/群聊都用它。 */
    private Long conversationId;
    /** 消息类型，1=文本。 */
    private Integer type;
    /** 文本内容。 */
    private String content;
    /** 客户端幂等键，建议 uuid。 */
    private String clientMsgId;
}
