package com.yujian.im.protocol;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.ByteToMessageDecoder;
import io.netty.handler.codec.CorruptedFrameException;
import io.netty.handler.codec.TooLongFrameException;
import lombok.extern.slf4j.Slf4j;

import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * 解码器 —— 解决 TCP 的粘包 / 半包。
 *
 * <p>TCP 是字节流，没有"消息边界"。客户端连发两条 100 字节的消息，服务端可能：
 * <ul>
 *   <li>粘包：一次 read 收到完整的 200 字节；</li>
 *   <li>半包：先收到 60 字节，剩下的 140 字节下一次才到（大数据包几乎必然发生）。</li>
 * </ul>
 *
 * <p>做法是"长度字段法"：读固定头拿到 bodyLen，只有
 * {@code 可读字节数 >= HEADER_LENGTH + bodyLen} 时才认为一条消息收全了，
 * 否则 {@code return} 保留 ByteBuf 里的残余字节，等下一次 read 再拼——
 * ByteToMessageDecoder 内部维护一个 cumulation 缓冲区做累加，半包天然被兜住。
 *
 * <p>反过来，一次 read 里如果有多条完整消息，while 循环要连续解出多条，这就是拆粘包。
 */
@Slf4j
public class ImDecoder extends ByteToMessageDecoder {

    @Override
    protected void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) {
        // 头都还没收全，直接等（半包场景 1）
        if (in.readableBytes() < ProtocolConst.HEADER_LENGTH) {
            return;
        }

        // readerIndex 是"下一条消息"的起点；mark 住，校验失败时能回退
        in.markReaderIndex();

        short magic = in.readShort();
        if (magic != ProtocolConst.MAGIC) {
            // 魔数不对说明字节流已经错位，没有恢复的可能，只能断开让它重连
            throw new CorruptedFrameException("非法魔数: 0x" + Integer.toHexString(magic & 0xFFFF));
        }

        byte version = in.readByte();
        byte command = in.readByte();
        byte serialize = in.readByte();
        byte status = in.readByte();
        long requestId = in.readLong();
        int bodyLength = in.readInt();

        // 防御：长连接是公网暴露面，不能让对端用一个巨大的 length 把内存撑爆
        if (bodyLength < 0 || bodyLength > ProtocolConst.MAX_BODY_LENGTH) {
            throw new TooLongFrameException("body 长度非法: " + bodyLength);
        }

        // 半包场景 2：头读完了，body 还没到齐
        if (in.readableBytes() < bodyLength) {
            // 把读指针退回到消息开头，等下一次 read 把剩余字节累积进来
            in.resetReaderIndex();
            return;
        }

        String body = null;
        if (bodyLength > 0) {
            body = in.readCharSequence(bodyLength, StandardCharsets.UTF_8).toString();
        }

        out.add(ImMessage.builder()
                .magic(magic)
                .version(version)
                .command(command)
                .serialize(serialize)
                .status(status)
                .requestId(requestId)
                .bodyLength(bodyLength)
                .body(body)
                .build());
    }
}
