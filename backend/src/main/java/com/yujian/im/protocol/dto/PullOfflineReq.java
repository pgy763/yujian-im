package com.yujian.im.protocol.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 上行：拉取离线消息。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class PullOfflineReq {
    /** 从这个 seq 之后开始拉。客户端传自己本地最大的 seq。 */
    private Long conversationId;
    private Long afterSeq;
    /** 期望条数，服务端会 clamp 到配置上限。 */
    private Integer limit;
}
