package com.yujian.im.netty.handler;

import com.yujian.im.message.MessageService;
import com.yujian.im.protocol.Command;
import com.yujian.im.session.SessionRegistry;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

/**
 * 单聊。
 */
@Component
public class SingleChatHandler extends AbstractChatHandler {

    public SingleChatHandler(MessageService messageService,
                             SessionRegistry sessionRegistry,
                             MeterRegistry meterRegistry) {
        super(messageService, sessionRegistry, meterRegistry, "im_message_send");
    }

    @Override
    protected Command requestCommand() {
        return Command.SINGLE_CHAT_REQ;
    }

    @Override
    protected Command responseCommand() {
        return Command.SINGLE_CHAT_RESP;
    }
}
