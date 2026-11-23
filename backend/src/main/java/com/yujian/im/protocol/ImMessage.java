package com.yujian.im.protocol;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 线上传输的帧对象。编码器/解码器只认这个类，业务层不碰 ByteBuf。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ImMessage {

    /** 魔数 0xCAFE。 */
    private short magic;

    /** 协议版本。 */
    private byte version;

    /** 命令字，见 {@link Command}。 */
    private byte command;

    /** 序列化方式，0 = JSON。 */
    private byte serialize;

    /** 状态，请求固定 0，响应 0=成功 1=失败。 */
    private byte status;

    /** 请求 ID：客户端发请求时生成，响应原样带回，用于把"异步的响应"对上是哪次请求。 */
    private long requestId;

    /** body 字节数，编码时回填。 */
    private int bodyLength;

    /** body 原文（JSON 字符串）。 */
    private String body;
}
