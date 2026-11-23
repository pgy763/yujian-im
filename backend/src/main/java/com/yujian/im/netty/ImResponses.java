package com.yujian.im.netty;

import com.yujian.im.protocol.BodyCodec;
import com.yujian.im.protocol.Command;
import com.yujian.im.protocol.ImMessage;
import com.yujian.im.protocol.ProtocolConst;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 统一的应答工具。
 *
 * <p>为什么单独抽出来：一个请求如果散落在各处 {@code ctx.writeAndFlush(...)}，
 * 很容易漏掉 {@code requestId} 的透传——而 requestId 是客户端把异步响应
 * 对上"这是哪次请求"的唯一依据，漏了就会出现"点了没反应"。
 */
public final class ImResponses {

    private ImResponses() {
    }

    /** 成功应答。body 可以是任意对象，取决于命令。 */
    public static void ok(ChannelHandlerContext ctx, long requestId, Command cmd, Object body) {
        write(ctx.channel(), requestId, cmd, ProtocolConst.STATUS_OK,
                body == null ? null : BodyCodec.toJson(body));
    }

    /** 失败应答。业务异常走这里，而不是直接断连接——断连接对客户端来说太"重"了。 */
    public static void fail(ChannelHandlerContext ctx, long requestId, int code, String msg) {
        Map<String, Object> body = new LinkedHashMap<>(2);
        body.put("code", code);
        body.put("msg", msg);
        write(ctx.channel(), requestId, Command.ERROR, ProtocolConst.STATUS_FAIL, BodyCodec.toJson(body));
    }

    public static void write(Channel ch, long requestId, Command cmd, byte status, String bodyJson) {
        if (!ch.isActive()) {
            return;
        }
        ch.writeAndFlush(ImMessage.builder()
                .magic(ProtocolConst.MAGIC)
                .version(ProtocolConst.VERSION)
                .command(cmd.getCode())
                .serialize(ProtocolConst.SERIALIZE_JSON)
                .status(status)
                .requestId(requestId)
                .body(bodyJson)
                .build());
    }
}
