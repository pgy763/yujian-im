package com.yujian.im.protocol.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 群成员变动通知。下行 {@code GROUP_EVENT_PUSH}。
 *
 * <p><b>这是"通知"不是"数据同步"。</b>客户端收到后不该拿它去增量改本地成员列表，
 * 而应该重新拉一次 {@code /api/conversations/{id}/member-infos}。原因：
 * <ul>
 *   <li>推送会丢 —— 用户离线时这条就没了，重连后没有任何补发机制；</li>
 *   <li>推送会乱序 —— 网络抖动下"张三入群"可能晚于"张三退群"到达；</li>
 *   <li>拉接口是幂等的，永远拿到当前全量真值，天然抗丢抗乱序。</li>
 * </ul>
 * 一句话：<b>推送负责"叫醒你"，HTTP 负责"告诉你真相"</b>。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class GroupEventPush {
    private Long conversationId;
    /** 事件类型：join / leave / kick / transfer。用字符串而不是枚举数字，
     *  是因为这条消息的可读性远大于带宽（总共几十字节），
     *  而数字 enum 一旦要加新类型就得两边同时升级，字符串能平滑扩展。 */
    private String event;
    /** 被操作的人（入群/退群/被踢/被转让的人）。 */
    private Long targetUserId;
    private String targetNickname;
    /** 操作者（自己退群时 operatorId == targetUserId）。 */
    private Long operatorId;
    private String operatorNickname;
    /** 变动后剩余成员数，前端可以直接显示"当前 5 人"。 */
    private int memberCount;
}
