package com.yujian.im.protocol;

/**
 * 协议常量。
 *
 * 帧结构（大端）：
 * <pre>
 * |  magic  | version | command | serialize | status |   requestId   | bodyLen |      body      |
 * | 2 bytes |  1 byte |  1 byte |   1 byte  | 1 byte |    8 bytes    | 4 bytes | bodyLen bytes  |
 * | 0xCAFE  |    1    |   见枚举 |    0=JSON |  0/1   |   雪花ID/自增  |         |   UTF-8 JSON   |
 * </pre>
 *
 * 固定头 18 字节。bodyLen 让接收方知道"这条消息还差多少字节"，
 * 所以半包可以等、粘包可以拆——这是自研协议必须自己解决的第一个问题。
 */
public final class ProtocolConst {

    private ProtocolConst() {
    }

    /** 魔数，用来快速识别"这不是我的客户端"。 */
    public static final short MAGIC = (short) 0xCAFE;

    /** 协议版本，为后续字段扩展留位置。 */
    public static final byte VERSION = 1;

    /** 固定头长度：2+1+1+1+1+8+4。 */
    public static final int HEADER_LENGTH = 18;

    /** 单帧 body 上限 8MB，防止恶意的超长 length 把内存打爆。 */
    public static final int MAX_BODY_LENGTH = 8 * 1024 * 1024;

    /** 序列化方式：0 = JSON（可换 Protobuf，command 层不用动）。 */
    public static final byte SERIALIZE_JSON = 0;

    /** 响应状态：成功。 */
    public static final byte STATUS_OK = 0;

    /** 响应状态：业务失败，body 里是错误描述。 */
    public static final byte STATUS_FAIL = 1;
}
