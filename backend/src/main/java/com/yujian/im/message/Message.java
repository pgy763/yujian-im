package com.yujian.im.message;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("im_message")
public class Message {

    public static final int TYPE_TEXT = 1;
    public static final int TYPE_IMAGE = 2;
    public static final int TYPE_SYSTEM = 3;

    @TableId(type = IdType.INPUT)
    private Long id;

    private Long conversationId;

    /** 会话内单调递增。这是客户端排序、去重、补洞的唯一依据。 */
    private Long seq;

    private Long fromUserId;

    private Integer type;

    private String content;

    /** 客户端生成的幂等键。同一个人用同一个 clientMsgId 重发，只会落一条。 */
    private String clientMsgId;

    /**
     * 是否已撤回。0=正常 1=已撤回。
     *
     * <p>撤回走<b>软删</b>：行不删、seq 不释放，只翻这个标志位。
     * 原因见 {@code schema.sql} 里 im_message 表的注释——
     * 硬删会让会话 seq 出现空洞，未读数（seq - 已读位点）和客户端补洞逻辑都会错。
     */
    private Integer revoked;

    /** 撤回时间，前端可以显示"xx 撤回了一条消息"。 */
    private LocalDateTime revokedAt;

    private LocalDateTime createdAt;
}
