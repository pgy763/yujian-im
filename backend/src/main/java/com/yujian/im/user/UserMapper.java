package com.yujian.im.user;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.Collection;
import java.util.List;

@Mapper
public interface UserMapper extends BaseMapper<User> {

    @Select("SELECT * FROM im_user WHERE username = #{username}")
    User selectByUsername(@Param("username") String username);

    /**
     * 批量查用户，用于把会话/消息里的 userId 一次性换成昵称头像，避免 N+1。
     *
     * <p>注意：MyBatis-Plus 3.5.9 的 BaseMapper 已经有 {@code selectByIds(Collection)}，
     * 这里再声明一个同名同参的方法会因为泛型擦除后签名相同而编译报错
     * （自定义的 Collection&lt;Long&gt; 与 BaseMapper 的 Collection&lt;? extends Serializable&gt; 冲突）。
     * 所以不要自己写，直接用 BaseMapper 提供的那个。
     */
}
