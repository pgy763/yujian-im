package com.yujian.im.controller;

import com.yujian.im.auth.CurrentUser;
import com.yujian.im.common.R;
import com.yujian.im.session.SessionRegistry;
import com.yujian.im.user.User;
import com.yujian.im.user.UserService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 用户查询：搜人、看在线状态。
 */
@RestController
@RequestMapping("/api/users")
@RequiredArgsConstructor
public class UserController {

    private final UserService userService;
    private final SessionRegistry sessionRegistry;

    /**
     * 按用户名/昵称搜人。前端"添加好友/发起会话"靠它。
     *
     * <p>搜人接口只返回 id/username/nickname/avatar，
     * 绝不返回 passwordHash —— DTO 在 Service 层就已经剪掉了这个字段。
     */
    @GetMapping("/search")
    public R<List<Map<String, Object>>> search(@CurrentUser Long userId,
                                               @RequestParam String keyword) {
        List<User> users = userService.search(keyword);
        List<Map<String, Object>> out = new ArrayList<>(users.size());
        for (User u : users) {
            if (u.getId().equals(userId)) {
                continue; // 搜不到自己，省得用户点自己发起会话
            }
            Map<String, Object> m = new LinkedHashMap<>(6);
            m.put("id", u.getId());
            m.put("username", u.getUsername());
            m.put("nickname", u.getNickname());
            m.put("avatar", u.getAvatar());
            // 在线状态来自本机内存注册表，O(1) 查询，不需要打 Redis
            m.put("online", sessionRegistry.isOnline(u.getId()));
            out.add(m);
        }
        return R.ok(out);
    }

    @GetMapping("/me")
    public R<UserService.UserVO> me(@CurrentUser Long userId) {
        return R.ok(userService.toVO(userService.getById(userId)));
    }

    /** 运维用：当前在线用户数/连接数。生产上这些指标会走 Prometheus，这里方便 curl 一眼看。 */
    @GetMapping("/online-stats")
    public R<Map<String, Object>> onlineStats() {
        Map<String, Object> m = new LinkedHashMap<>(4);
        m.put("onlineUsers", sessionRegistry.totalOnlineUsers());
        m.put("onlineChannels", sessionRegistry.totalChannels());
        return R.ok(m);
    }
}
