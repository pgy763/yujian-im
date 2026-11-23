package com.yujian.im.protocol.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 已读回执查询结果。{@code READ_RECEIPT_RESP} 和 {@code READ_RECEIPT_PUSH} 共用。
 *
 * <p><b>为什么用"两个 id 列表"而不是"每条消息一个 {userId, readAt}"：</b>
 * 已读的真相是 {@code last_read_seq} 这个位点，不是"每条消息 × 每个人"的矩阵。
 * 位点语义下，"读完第 N 条"天然意味着 1..N 都读过了，
 * 所以回执只需要回答"读完这条的人有谁、还没读的有谁"。
 * 如果按矩阵存，100 人群 1 万条消息就是 100 万行——这是典型的过度建模。
 *
 * <p>客户端拿到 {@code readUserIds} 后去成员列表里查昵称头像；
 * 两者都不在列表里的（比如刚退群的人），直接忽略。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ReadReceiptVO {
    private Long conversationId;
    private Long msgId;
    private long seq;
    /** 已经读到这里的人（不含发送者自己）。 */
    private List<String> readUserIds;
    /** 还没读的人（不含发送者自己）。单聊时这个列表最多一个元素。 */
    private List<String> unreadUserIds;
    /** 群里除发送者外总人数，前端显示 "2/3 人已读" 用。 */
    private int totalOthers;
}
