package com.yujian.im.netty.handler;

import com.yujian.im.protocol.Command;
import com.yujian.im.protocol.ImMessage;
import io.netty.channel.ChannelHandlerContext;

/**
 * 一条上行指令的处理器。
 *
 * <p><b>为什么按命令字拆成一堆小类，而不是一个巨大的 switch：</b>
 * <ul>
 *   <li>Netty 的 IO 线程不能阻塞。单个 Handler 类里写多个分支，很容易有人在某个分支里
 *       加一次同步 DB 查询，然后整个 EventLoop（管着成百上千条连接）被拖住；</li>
 *   <li>拆开之后，需要异步化的那个命令可以单独挂线程池，不影响别的；</li>
 *   <li>加一个新命令只需要新增一个类 + 一个 Bean，不用改分发逻辑（对扩展开放）。</li>
 * </ul>
 *
 * <p>分发用 {@code Map<Command, CommandHandler>}，查找 O(1)，
 * 比 if-else 链在命令多时更清楚。
 */
public interface CommandHandler {

    /** 这个处理器负责哪个命令字。 */
    Command command();

    /** 处理一条完整的帧。实现里不要吞异常，交给分发器统一回 ERROR。 */
    void handle(ChannelHandlerContext ctx, ImMessage msg) throws Exception;
}
