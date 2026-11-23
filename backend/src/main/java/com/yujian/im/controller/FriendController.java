package com.yujian.im.controller;

import com.yujian.im.auth.CurrentUser;
import com.yujian.im.common.R;
import com.yujian.im.friend.FriendService;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 好友接口。
 *
 * <p>全部走 HTTP：加好友、看列表都是"一问一答"，没有服务端主动推的必要。
 * 真实产品里"收到好友申请"需要推送，那才该走长连接。
 */
@RestController
@RequestMapping("/api/friends")
@RequiredArgsConstructor
public class FriendController {

    private final FriendService friendService;

    @Data
    public static class AddReq {
        private Long targetId;
    }

    /** 我的好友列表。 */
    @GetMapping
    public R<List<Map<String, Object>>> list(@CurrentUser Long userId) {
        return R.ok(friendService.listOf(userId));
    }

    /**
     * 还没加的人（就是"添加好友"弹窗里要列的那份清单）。
     *
     * <p>SQL 里就排掉了自己和已加的人，前端直接渲染即可。
     */
    @GetMapping("/candidates")
    public R<List<Map<String, Object>>> candidates(@CurrentUser Long userId,
                                                    @RequestParam(defaultValue = "100") int limit) {
        return R.ok(friendService.candidates(userId, limit));
    }

    /** 加好友。重复调用不报错（幂等）。 */
    @PostMapping
    public R<Map<String, Object>> add(@CurrentUser Long userId, @RequestBody AddReq req) {
        friendService.add(userId, req.getTargetId());
        return R.ok(Map.of("targetId", req.getTargetId(), "friend", true));
    }

    /** 删好友。删不存在的关系也算成功（幂等）。 */
    @DeleteMapping
    public R<Map<String, Object>> remove(@CurrentUser Long userId, @RequestParam Long targetId) {
        friendService.remove(userId, targetId);
        return R.ok(Map.of("targetId", targetId, "friend", false));
    }
}
