package com.yujian.im.friend;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface FriendMapper extends BaseMapper<Friend> {

    /** 我的好友 id 列表。where owner_id=? 走 uk_owner_friend，一次范围扫描。 */
    @Select("SELECT friend_id FROM im_friend WHERE owner_id = #{ownerId}")
    List<Long> selectFriendIds(@Param("ownerId") Long ownerId);

    @Select("SELECT COUNT(*) FROM im_friend WHERE owner_id = #{ownerId} AND friend_id = #{friendId}")
    int countRelation(@Param("ownerId") Long ownerId, @Param("friendId") Long friendId);

    /**
     * 候选好友：除了我自己、以及我已经加过的人，其余全部列出来。
     *
     * <p>用 {@code NOT EXISTS} 而不是 {@code NOT IN (子查询)}：
     * NOT IN 遇到子查询里出现 NULL 会整体返回空集（三值逻辑陷阱），
     * 而且 MySQL 对 NOT EXISTS 能做成半连接（semi-join）优化。
     */
    @Select("""
            SELECT u.* FROM im_user u
            WHERE u.id <> #{ownerId}
              AND NOT EXISTS (
                  SELECT 1 FROM im_friend f
                  WHERE f.owner_id = #{ownerId} AND f.friend_id = u.id
              )
            ORDER BY u.created_at DESC
            LIMIT #{limit}
            """)
    List<com.yujian.im.user.User> selectCandidates(@Param("ownerId") Long ownerId,
                                                   @Param("limit") int limit);

    @Delete("DELETE FROM im_friend WHERE owner_id = #{ownerId} AND friend_id = #{friendId}")
    int deleteRelation(@Param("ownerId") Long ownerId, @Param("friendId") Long friendId);
}
