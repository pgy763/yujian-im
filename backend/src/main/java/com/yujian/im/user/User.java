package com.yujian.im.user;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("im_user")
public class User {

    @TableId(type = IdType.INPUT)
    private Long id;

    private String username;

    /** BCrypt 哈希。永久不返回给前端，所以下发给前端时用 DTO 而不是这个实体。 */
    private String passwordHash;

    private String nickname;

    private String avatar;

    private Integer online;

    private LocalDateTime createdAt;
}
