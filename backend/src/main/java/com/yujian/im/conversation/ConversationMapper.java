package com.yujian.im.conversation;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

@Mapper
public interface ConversationMapper extends BaseMapper<Conversation> {

    @Select("SELECT * FROM im_conversation WHERE pair_key = #{pairKey}")
    Conversation selectByPairKey(@Param("pairKey") String pairKey);

    /**
     * 原子地把会话 seq 推进到 seq+1 并返回新值。
     *
     * <p>为什么不用 "select seq then update"：两个线程同时读到 10，都会写 11，
     * 于是两条消息拿到同一个 seq，唯一索引 uk_conv_seq 会直接冲突。
     * 用 UPDATE ... SET seq = seq + 1 让数据库行锁来串行化，
     * 再立刻 SELECT 自己刚写的值（同一事务内，别的线程进不来），就得到唯一序号。
     *
     * <p>更彻底的做法是用 Redis INCR 发号（见 ConversationSeqService），
     * 这里是"数据库兜底"的那条路。
     */
    @Update("UPDATE im_conversation SET seq = seq + 1 WHERE id = #{id}")
    int incrSeq(@Param("id") Long id);

    @Select("SELECT seq FROM im_conversation WHERE id = #{id}")
    Long selectSeq(@Param("id") Long id);

    @Update("""
            UPDATE im_conversation
            SET last_preview = #{preview}, last_msg_at = #{lastMsgAt}, seq = #{seq}
            WHERE id = #{id}
            """)
    int updateLastMeta(@Param("id") Long id,
                       @Param("preview") String preview,
                       @Param("lastMsgAt") java.time.LocalDateTime lastMsgAt,
                       @Param("seq") Long seq);

    /**
     * 转让群主：只改会话表上的 owner_id。
     *
     * <p>成员表里的 {@code role} 由 {@code GroupService.transferOwner} 在同一个事务里
     * 一起更新。这两份数据描述同一件事（谁是群主），是刻意的冗余：
     * {@code owner_id} 让"查我的会话列表"不用再 join 成员表判断角色，
     * 代价就是每次转让必须两处同时改、且必须在同一个事务里。
     */
    @Update("UPDATE im_conversation SET owner_id = #{ownerId} WHERE id = #{id}")
    int updateOwner(@Param("id") Long id, @Param("ownerId") Long ownerId);
}
