package com.yujian.im.protocol.dto;

import lombok.Data;

/**
 * 上行：登录请求。客户端先用 HTTP /api/auth/login 拿到 token，再在长连接上发这一帧完成绑定。
 */
@Data
public class LoginReq {
    private String token;
    /** 设备标识：ios / android / web / pc，用于多端在线时区分。 */
    private String device;
}
