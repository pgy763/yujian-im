package com.yujian.im.search;

import com.yujian.im.protocol.dto.MessageVO;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 聊天记录搜索的一条命中。
 *
 * <p>比 {@link MessageVO} 多两个字段：{@code conversationTitle}（全局搜索时要告诉用户
 * 这条消息属于哪个会话，否则一堆内容没法区分）和 {@code conversationType}。
 *
 * <p>{@code seq} 用原始 {@code long}：前端要用它定位"跳到这条消息"，并且要跟
 * 当前列表里的 seq 做数值比较，必须是 JSON 数字而不是字符串（原因见 common/JsonModules.java）。
 */
@Data
@Builder
public class SearchHit {

    private Long msgId;

    private Long conversationId;

    /** 会话标题：单聊是对方昵称，群聊是群名。 */
    private String conversationTitle;

    private Integer conversationType;

    private Long fromUserId;

    private String fromNickname;

    private int type;

    private String content;

    private long seq;

    private LocalDateTime createdAt;
}
