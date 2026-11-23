package com.yujian.im.protocol;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

/**
 * 协议 body 的 JSON 编解码。单独拎出来是为了让编解码器不依赖 Spring 容器，
 * 这样单元测试里可以直接 new 出来跑。
 *
 * <p><b>踩过的坑（面试可以当故事讲）：</b>
 * 这里的 ObjectMapper 是 {@code new} 出来的，不是 Spring 注入的那个，
 * 所以 Spring Boot 自动注册的 JavaTimeModule 在这里<b>不存在</b>。
 * 第一版没注册它，发消息时 {@code MessageVO.createdAt}({@code LocalDateTime}) 一序列化就抛
 * {@code InvalidDefinitionException: Java 8 date/time type LocalDateTime not supported by default}。
 * 表现是"消息已经入库、seq 也已经消耗了，但对端一直没收到推送"——
 * 因为异常发生在入库之后、推送之前，属于典型的"部分成功"。
 * 教训：凡是自己 new 的 ObjectMapper，{@code jar} 里能用的模块都要手动补上。
 */
public final class BodyCodec {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
            // 支持 LocalDateTime/Instant 等 java.time 类型
            .registerModule(new JavaTimeModule())
            // 雪花 ID 超出 JS 安全整数范围（2^53），必须序列化成字符串，
            // 否则浏览器会把 383197437298343936 读成 383197437298343940
            .registerModule(com.yujian.im.common.JsonModules.LONG_TO_STRING)
            // 时间写成 "2026-11-23T09:54:59.159" 而不是时间戳数字，前后端和抓包都好读
            .configure(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS, false);

    private BodyCodec() {
    }

    public static String toJson(Object o) {
        try {
            return MAPPER.writeValueAsString(o);
        } catch (Exception e) {
            throw new IllegalStateException("序列化失败", e);
        }
    }

    public static <T> T fromJson(String json, Class<T> type) {
        try {
            return MAPPER.readValue(json, type);
        } catch (Exception e) {
            throw new IllegalStateException("反序列化失败: " + json, e);
        }
    }

    public static ObjectMapper mapper() {
        return MAPPER;
    }

    /**
     * 同 {@link #toJson(Object)} 的别名。推送路径上每投一个连接都要序列化一次，
     * 名字短一点读起来更清楚（这个类里它是热路径）。
     */
    public static String stringify(Object o) {
        return toJson(o);
    }

    /**
     * 给 WebSocket 连接拼一帧文本。
     *
     * <p>TCP 连接走二进制头 + JSON body，浏览器上要实现那 18 字节的头很啰嗦；
     * 所以 WS 通道上直接发一个 JSON 信封：
     * {@code {"cmd":9,"status":0,"requestId":0,"body":{...}}}。
     * 服务端两套传输共用同一个 {@link ImMessage}，只是"出站编码"不同，
     * 入站则由 {@code WsCodec.Decoder} 把文本信封还原成 ImMessage —— 业务层完全无感。
     *
     * <p><b>requestId 必须原样回传</b>：客户端靠它把响应和请求对上号（请求响应关联）。
     * 第一版这里写死 0，结果浏览器端发什么回来都是 requestId=0，
     * 分不清哪条响应属于哪次请求——TCP 通道因为头里有 requestId 反而没暴露这个问题。
     */
    public static String toWsText(byte command, long requestId, byte status, String bodyJson) {
        StringBuilder sb = new StringBuilder(64 + (bodyJson == null ? 0 : bodyJson.length()));
        sb.append("{\"cmd\":").append(command & 0xFF)
                .append(",\"status\":").append(status)
                .append(",\"requestId\":").append(requestId)
                .append(",\"body\":")
                .append(bodyJson == null || bodyJson.isEmpty() ? "null" : bodyJson)
                .append('}');
        return sb.toString();
    }
}
