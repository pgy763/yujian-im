package com.yujian.im.netty.handler;

import com.yujian.im.message.MessageService;
import com.yujian.im.protocol.Command;
import com.yujian.im.session.SessionRegistry;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

/**
 * 群聊。
 *
 * <p>和单聊唯一的实现差异是<b>计时器的名字</b>——这样在 Prometheus 里
 * 能分别看到单聊和群聊的 P99，因为群聊的写扩散会明显更慢，
 * 混在一起看会把问题掩盖掉。
 *
 * <p>另一个隐含差异在 {@code PushService} 里：群成员超过阈值走读扩散。
 * 这条分界线决定了"群聊慢"到底慢在哪一环。
 */
@Component
public class GroupChatHandler extends AbstractChatHandler {

    public GroupChatHandler(MessageService messageService,
                            SessionRegistry sessionRegistry,
                            MeterRegistry meterRegistry) {
        super(messageService, sessionRegistry, meterRegistry, "im_group_message_send");
    }

    @Override
    protected Command requestCommand() {
        return Command.GROUP_CHAT_REQ;
    }

    @Override
    protected Command responseCommand() {
        return Command.GROUP_CHAT_RESP;
    }
}
