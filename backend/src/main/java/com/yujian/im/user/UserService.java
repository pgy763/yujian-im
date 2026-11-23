package com.yujian.im.user;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.yujian.im.common.BizException;
import com.yujian.im.common.SnowflakeIdGenerator;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 用户领域服务。
 */
@Service
@RequiredArgsConstructor
public class UserService {

    private final UserMapper userMapper;
    private final SnowflakeIdGenerator idGenerator;

    /**
     * BCrypt 而不是 MD5/SHA：
     * 每次哈希带随机盐（所以同一个密码两次结果不同），且 cost=10 让单次校验约 50~100ms，
     * 暴力破解的成本被强行拉高。存 MD5 的库一旦泄露，彩虹表直接反查。
     */
    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder(10);

    @Transactional
    public User register(String username, String rawPassword, String nickname) {
        if (userMapper.selectByUsername(username) != null) {
            throw BizException.of("用户名已存在");
        }
        User u = new User();
        u.setId(idGenerator.nextId());
        u.setUsername(username);
        u.setPasswordHash(encoder.encode(rawPassword));
        u.setNickname(nickname == null || nickname.isBlank() ? username : nickname);
        u.setOnline(0);
        u.setCreatedAt(LocalDateTime.now());
        userMapper.insert(u);
        return u;
    }

    public User login(String username, String rawPassword) {
        User u = userMapper.selectByUsername(username);
        if (u == null) {
            // 注意：这里也返回"用户名或密码错误"，不能提示"用户不存在"，
            // 否则等于给攻击者一个用户名枚举接口
            throw BizException.unauthorized("用户名或密码错误");
        }
        if (!encoder.matches(rawPassword, u.getPasswordHash())) {
            throw BizException.unauthorized("用户名或密码错误");
        }
        return u;
    }

    public User getById(Long id) {
        User u = userMapper.selectById(id);
        if (u == null) {
            throw BizException.of("用户不存在: " + id);
        }
        return u;
    }

    public Map<Long, User> mapByIds(Collection<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return Collections.emptyMap();
        }
        List<User> users = userMapper.selectByIds(ids);
        return users.stream().collect(Collectors.toMap(User::getId, Function.identity()));
    }

    public List<User> search(String keyword) {
        return userMapper.selectList(new LambdaQueryWrapper<User>()
                .like(User::getUsername, keyword)
                .or()
                .like(User::getNickname, keyword)
                .last("limit 20"));
    }

    public UserVO toVO(User u) {
        return new UserVO(u.getId(), u.getUsername(), u.getNickname(), u.getAvatar());
    }

    /** 对外 DTO：绝对不带 passwordHash。 */
    public record UserVO(Long id, String username, String nickname, String avatar) {
    }
}
