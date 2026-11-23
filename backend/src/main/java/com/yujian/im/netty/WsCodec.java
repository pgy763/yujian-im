package com.yujian.im.netty;

import com.fasterxml.jackson.databind.JsonNode;
import com.yujian.im.protocol.BodyCodec;
import com.yujian.im.protocol.Command;
import com.yujian.im.protocol.ImMessage;
import com.yujian.im.protocol.ProtocolConst;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.MessageToMessageDecoder;
import io.netty.handler.codec.MessageToMessageEncoder;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;

import java.util.List;

/**
 * WebSocket 通道上的"伪协议"编解码。
 *
 * <p><b>为什么要这一层（面试可以这么讲）：</b>
 * 原生 TCP 通道上，客户端能自己按 18 字节头拼二进制包，所以走 {@code ImDecoder/ImEncoder}。
 * 但浏览器的 WebSocket API 只有 {@code send(string)} / {@code onmessage}，
 * 想发二进制得用 ArrayBuffer 并自己按 DataView 写字节——代码丑陋且容易错位。
 *
 * <p>我们的选择是：<b>同一套业务 Handler，两种传输编码</b>。
 * WS 上发一个 JSON 信封 {@code {"cmd":9,"status":0,"requestId":1,"body":{...}}}，
 * 这两个 Handler 在进业务层之前把它还原成 {@link ImMessage}，
 * 出站时再折叠回文本。好处是业务代码（ChatHandler 等）完全不知道传输层是什么，
 * 坏处是 WS 上多了一层 JSON 嵌套、比 TCP 多几个字节——
 * 对一个练手项目来说，可维护性远比那几个字节重要。
 */
public final class WsCodec {

    private WsCodec() {
    }

    /** 入站：TextWebSocketFrame -> ImMessage。 */
    public static class Decoder extends MessageToMessageDecoder<TextWebSocketFrame> {
        @Override
        protected void decode(ChannelHandlerContext ctx, TextWebSocketFrame frame, List<Object> out) {
            String text = frame.text();
            try {
                JsonNode node = BodyCodec.mapper().readTree(text);
                int cmd = node.path("cmd").asInt(Command.ERROR.getCode());
                long requestId = node.path("requestId").asLong(0L);
                byte status = (byte) node.path("status").asInt(0);
                JsonNode bodyNode = node.get("body");
                String body = (bodyNode == null || bodyNode.isNull()) ? null : bodyNode.toString();

                out.add(ImMessage.builder()
                        .magic(ProtocolConst.MAGIC)
                        .version(ProtocolConst.VERSION)
                        .command((byte) cmd)
                        .serialize(ProtocolConst.SERIALIZE_JSON)
                        .status(status)
                        .requestId(requestId)
                        .body(body)
                        .build());
            } catch (Exception e) {
                // 格式非法就当协议错误处理，交给上层断开
                throw new IllegalArgumentException("WS 帧格式非法: " + text, e);
            }
        }
    }

    /** 出站：ImMessage -> TextWebSocketFrame。 */
    public static class Encoder extends MessageToMessageEncoder<ImMessage> {
        @Override
        protected void encode(ChannelHandlerContext ctx, ImMessage msg, List<Object> out) {
            out.add(new TextWebSocketFrame(BodyCodec.toWsText(
                    msg.getCommand(), msg.getRequestId(), msg.getStatus(), msg.getBody())));
        }
    }
}
