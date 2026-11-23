package com.yujian.im.conversation;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("im_conversation_member")
public class ConversationMember {

    @TableId(type = IdType.INPUT)
    private Long id;

    private Long conversationId;

    private Long userId;

    /** 该成员读到哪了。未读数 = 会话 seq - lastReadSeq。 */
    private Long lastReadSeq;

    /**
     * 该成员确认"已收到"到哪了。断线重连时从这里往后补。
     *
     * <p>为什么和 lastReadSeq 分开：手机锁屏时推送能到（已收到），
     * 但用户没点开（未读）。两者混用会导致"锁屏后消息全变已读"。
     */
    private Long deliveredSeq;

    private Integer role;

    /**
     * 退群/被踢的时间。非 null 表示这个成员已经不在会话里了。
     *
     * <p>软删而不是 DELETE 的理由见 {@code schema.sql}。
     * 代价是：<b>所有"查成员"的 SQL 都必须带 {@code left_at IS NULL}</b>，
     * 漏一个就会把退群的人算进推送列表 / 成员列表 / 已读统计里。
     * 这正是它比硬删更容易出错的地方，写代码时要格外小心。
     */
    private LocalDateTime leftAt;

    private LocalDateTime joinedAt;
}
