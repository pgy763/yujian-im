package com.yujian.im.protocol.dto;

import lombok.Data;

/**
 * 下行：登录结果。
 */
@Data
public class LoginResp {
    private boolean success;
    private Long userId;
    private String message;
    /** 服务端时间，客户端用来校正本地时钟，避免"消息时间显示成未来"。 */
    private long serverTime;
}
