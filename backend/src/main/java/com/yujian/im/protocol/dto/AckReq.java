package com.yujian.im.protocol.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 上行：ack。客户端确认"这条消息我收到了"，服务端据此把它从离线队列里清掉。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class AckReq {
    private Long conversationId;
    /** 已收到的最大 seq。 */
    private Long seq;
    /** 已收到的最大 msgId，冗余一份方便对账。 */
    private Long msgId;
}
