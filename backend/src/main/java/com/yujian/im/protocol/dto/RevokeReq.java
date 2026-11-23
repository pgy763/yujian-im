package com.yujian.im.protocol.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 撤回请求。上行 {@code REVOKE_REQ}。
 *
 * <p>只带 {@code conversationId + msgId}，不带"撤回理由"之类的字段：
 * 加字段很容易，但每加一个就要在服务端、客户端、数据库三处维护，
 * 而产品上根本没这个需求。等真的要做"撤回原因"再说。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class RevokeReq {
    private Long conversationId;
    private Long msgId;
}
