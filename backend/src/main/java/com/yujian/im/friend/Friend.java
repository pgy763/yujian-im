package com.yujian.im.friend;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 好友关系（单行 = "owner 把 friend 加为好友"）。
 *
 * <p>为什么是单向一行、而不是一行存 (userA,userB) 两个人：
 * <ul>
 *   <li>"我的好友列表"就是 {@code where owner_id = me}，走 {@code uk_owner_friend} 覆盖索引，
 *       一次范围扫描拿完，不需要 {@code where a=? or b=?} 这种 or 条件（or 容易让索引失效）。</li>
 *   <li>以后要加"备注名""免打扰""置顶"这些**每个人都有自己一份**的属性，
 *       直接加在本表列上就行；如果一行存两个人，这些属性就没地方放，
 *       只能再拆一张关系属性表。</li>
 *   <li>以后要做单向关注（像微博）也不需要改表结构，只是少插一行而已。</li>
 * </ul>
 *
 * <p>本项目点"添加好友"即双向生效（同时插 a→b 和 b→a 两行），
 * 真实产品还需要"好友申请 → 对方同意"的流程，见 README 的已知局限。
 */
@Data
@TableName("im_friend")
public class Friend {

    @TableId(type = IdType.INPUT)
    private Long id;

    /** 关系属于谁（"我的"好友列表按这一列查）。 */
    private Long ownerId;

    /** 被加为好友的人。 */
    private Long friendId;

    private LocalDateTime createdAt;
}
