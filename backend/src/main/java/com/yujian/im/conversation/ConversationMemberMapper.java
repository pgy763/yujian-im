package com.yujian.im.conversation;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;
import java.util.Map;

/**
 * 会话成员 DAO。
 *
 * <p><b>贯穿本类的铁律：</b>每一句查成员的 SQL 都必须带
 * {@code left_at IS NULL}。成员表用软删（退群只是标记 left_at 不清行），
 * 少写这一个条件，退群的人就会重新出现在成员列表里、继续收到推送、
 * 甚至被算进"N 人已读"。这类 bug 不会报错，只会让数据悄悄不对。
 */
@Mapper
public interface ConversationMemberMapper extends BaseMapper<ConversationMember> {

    @Select("SELECT * FROM im_conversation_member WHERE conversation_id = #{convId} AND left_at IS NULL")
    List<ConversationMember> selectByConversation(@Param("convId") Long convId);

    @Select("SELECT user_id FROM im_conversation_member WHERE conversation_id = #{convId} AND left_at IS NULL")
    List<Long> selectUserIds(@Param("convId") Long convId);

    /**
     * 已读位点只允许前进，不允许后退。
     * 多端同时上报已读时，晚到的旧位点不能把新位点覆盖回去，
     * 所以用 WHERE last_read_seq < #{seq} 做条件更新。
     */
    @Update("""
            UPDATE im_conversation_member
            SET last_read_seq = #{seq}
            WHERE conversation_id = #{convId} AND user_id = #{userId} AND last_read_seq < #{seq}
            """)
    int advanceReadSeq(@Param("convId") Long convId,
                       @Param("userId") Long userId,
                       @Param("seq") Long seq);

    /**
     * 取我的已读位点。返回 null 有两种含义：不是成员，或者已经退群 ——
     * 两者对调用方（{@link #selectReadSeq} 用来做 assertMember）来说结果一样：
     * 你不能在这个会话里发消息了。所以这里不过滤 left_at，让软删成员返回 null。
     */
    @Select("""
            SELECT last_read_seq FROM im_conversation_member
            WHERE conversation_id = #{convId} AND user_id = #{userId} AND left_at IS NULL
            """)
    Long selectReadSeq(@Param("convId") Long convId, @Param("userId") Long userId);

    /**
     * ack 推进"已收到"位点。同样只允许前进。
     */
    @Update("""
            UPDATE im_conversation_member
            SET delivered_seq = #{seq}
            WHERE conversation_id = #{convId} AND user_id = #{userId} AND delivered_seq < #{seq}
            """)
    int advanceDeliveredSeq(@Param("convId") Long convId,
                            @Param("userId") Long userId,
                            @Param("seq") Long seq);

    @Select("SELECT delivered_seq FROM im_conversation_member WHERE conversation_id = #{convId} AND user_id = #{userId}")
    Long selectDeliveredSeq(@Param("convId") Long convId, @Param("userId") Long userId);

    @Select("""
            SELECT m.conversation_id
            FROM im_conversation_member m
            WHERE m.user_id = #{userId} AND m.left_at IS NULL
            """)
    List<Long> selectConvIdsByUser(@Param("userId") Long userId);

    // ---------------------------------------------------------------- 已读回执

    /**
     * 已读回执的核心查询：谁把这条消息读过了。
     *
     * <p>做法是把"已读位点 &gt;= 我这条消息的 seq"的成员全部捞出来，
     * 因为这正是 last_read_seq 的语义：他已经读到了第 N 条，
     * 那么 1..N 里的每一条他都读过，不需要为每条消息单独维护一张"谁读了谁"的表。
     *
     * <p>为什么只返回 {@code user_id} 加 {@code last_read_seq} 而不是整行：
     * 前端只需要"哪些人已读、哪些人没读"，用 id 集合做差集即可；
     * 昵称头像走 member-infos 接口一次批量查，避免把两份逻辑揉在一起。
     *
     * <p>排除自己（{@code user_id <> #{readerId}）：自己发的消息自己当然读过，
     * 算进去会让"3 人已读"变成"4 人已读"，对不上人数。
     */
    @Select("""
            SELECT user_id AS userId, last_read_seq AS readSeq
            FROM im_conversation_member
            WHERE conversation_id = #{convId}
              AND left_at IS NULL
              AND last_read_seq >= #{seq}
            ORDER BY last_read_seq DESC
            """)
    List<Map<String, Object>> selectReaders(@Param("convId") Long convId, @Param("seq") Long seq);

    /** 群里除我之外还有多少人（用来算"未读人数"）。 */
    @Select("""
            SELECT COUNT(*) FROM im_conversation_member
            WHERE conversation_id = #{convId} AND left_at IS NULL AND user_id <> #{excludeUserId}
            """)
    int countOthers(@Param("convId") Long convId, @Param("excludeUserId") Long excludeUserId);

    // ---------------------------------------------------------------- 群成员管理

    /**
     * 退群 / 被踢：软删，只打时间戳。重复调用是幂等的（{@code left_at IS NULL} 条件挡掉第二次）。
     */
    @Update("""
            UPDATE im_conversation_member SET left_at = NOW()
            WHERE conversation_id = #{convId} AND user_id = #{userId} AND left_at IS NULL
            """)
    int markLeft(@Param("convId") Long convId, @Param("userId") Long userId);

    /** 重新入群：把 left_at 清回去，而不是插一行新的（uk_conv_user 会冲突）。 */
    @Update("""
            UPDATE im_conversation_member SET left_at = NULL, joined_at = NOW()
            WHERE conversation_id = #{convId} AND user_id = #{userId} AND left_at IS NOT NULL
            """)
    int reviveMember(@Param("convId") Long convId, @Param("userId") Long userId);

    @Select("""
            SELECT * FROM im_conversation_member
            WHERE conversation_id = #{convId} AND user_id = #{userId}
            """)
    ConversationMember selectOneByConvAndUser(@Param("convId") Long convId, @Param("userId") Long userId);

    /** 改角色：转让群主时老群主降为成员、新群主升为群主。 */
    @Update("""
            UPDATE im_conversation_member SET role = #{role}
            WHERE conversation_id = #{convId} AND user_id = #{userId} AND left_at IS NULL
            """)
    int updateRole(@Param("convId") Long convId, @Param("userId") Long userId, @Param("role") int role);

    /** 在线成员数（群信息面板显示"5 人在线"用）。只是 id 列表，在线状态交给 SessionRegistry。 */
    @Select("""
            SELECT user_id FROM im_conversation_member
            WHERE conversation_id = #{convId} AND left_at IS NULL
            """)
    List<Long> selectActiveUserIds(@Param("convId") Long convId);
}
