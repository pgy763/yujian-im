package com.yujian.im.conversation;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("im_conversation")
public class Conversation {

    /** 1=单聊。 */
    public static final int TYPE_SINGLE = 1;
    /** 2=群聊。 */
    public static final int TYPE_GROUP = 2;

    @TableId(type = IdType.INPUT)
    private Long id;

    private Integer type;

    private String name;

    private Long ownerId;

    /** 会话当前最大 seq。发消息时用它做"分布式发号"的结果落地。 */
    private Long seq;

    private String lastPreview;

    private LocalDateTime lastMsgAt;

    private String pairKey;

    private LocalDateTime createdAt;
}
