package com.yujian.im.protocol;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.MessageToByteEncoder;
import lombok.extern.slf4j.Slf4j;

import java.nio.charset.StandardCharsets;

/**
 * 编码器 —— 把 {@link ImMessage} 写成字节流。
 *
 * <p>注意两点：
 * <ol>
 *   <li>必须写成 MessageToByteEncoder&lt;ImMessage&gt;，泛型决定了哪些出站对象归它管，
 *       否则 Netty 会用 Object 匹配，把别的消息也塞进来。</li>
 *   <li>这里直接往 Netty 给的 ByteBuf 上写，不要自己 new ByteBuf 再 copy，
 *       否则每次发送多一次内存拷贝，压测时 GC 会很可观。</li>
 * </ol>
 */
@Slf4j
public class ImEncoder extends MessageToByteEncoder<ImMessage> {

    @Override
    protected void encode(ChannelHandlerContext ctx, ImMessage msg, ByteBuf out) {
        byte[] bodyBytes = msg.getBody() == null
                ? new byte[0]
                : msg.getBody().getBytes(StandardCharsets.UTF_8);

        out.writeShort(ProtocolConst.MAGIC);
        out.writeByte(ProtocolConst.VERSION);
        out.writeByte(msg.getCommand());
        out.writeByte(msg.getSerialize());
        out.writeByte(msg.getStatus());
        out.writeLong(msg.getRequestId());
        out.writeInt(bodyBytes.length);
        out.writeBytes(bodyBytes);
    }
}
