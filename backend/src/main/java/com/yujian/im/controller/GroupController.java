package com.yujian.im.controller;

import com.yujian.im.auth.CurrentUser;
import com.yujian.im.common.R;
import com.yujian.im.conversation.GroupService;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 群成员管理接口。
 *
 * <p><b>为什么这些操作走 HTTP 而不是长连接：</b>
 * 拉人、踢人、退群、转让群主都是"低频 + 需要明确失败原因"的操作。
 * HTTP 天然有状态码和错误体，用户点错了能看到"群主不能直接退群"这种提示；
 * 而长连接上一个失败要自己定义错误码、自己回帧、客户端还要写一套错误渲染。
 * <b>长连接留给高频实时通信，低频管理操作走 HTTP</b> —— 这是选型原则不是随意划分。
 *
 * <p>真正的"通知"（谁进群了、谁被踢了）仍然走长连接的 {@code GROUP_EVENT_PUSH}，
 * 因为那是服务端主动推的，HTTP 做不到。
 */
@RestController
@RequestMapping("/api/conversations")
@RequiredArgsConstructor
public class GroupController {

    private final GroupService groupService;

    @Data
    public static class AddMembersReq {
        private List<Long> memberIds;
    }

    @Data
    public static class TargetReq {
        private Long targetId;
    }

    /** 拉人入群。返回实际新加入的人（已经在群里的不会重复算）。 */
    @PostMapping("/{convId}/members")
    public R<List<String>> addMembers(@CurrentUser Long userId,
                                      @PathVariable Long convId,
                                      @RequestBody AddMembersReq req) {
        return R.ok(groupService.addMembers(userId, convId,
                req == null ? null : req.getMemberIds()));
    }

    /** 群成员详情（含角色、入群时间）。比 /member-infos 多这两项，用于群管理面板。 */
    @GetMapping("/{convId}/group-members")
    public R<List<Map<String, Object>>> members(@CurrentUser Long userId, @PathVariable Long convId) {
        return R.ok(groupService.members(userId, convId));
    }

    /** 退群。群主调用会被拒绝并提示先转让。 */
    @DeleteMapping("/{convId}/members/me")
    public R<Void> leave(@CurrentUser Long userId, @PathVariable Long convId) {
        groupService.leave(userId, convId);
        return R.ok(null);
    }

    /** 踢人。只有群主。 */
    @DeleteMapping("/{convId}/members/{targetId}")
    public R<Void> kick(@CurrentUser Long userId,
                        @PathVariable Long convId,
                        @PathVariable Long targetId) {
        groupService.kick(userId, convId, targetId);
        return R.ok(null);
    }

    /** 转让群主。 */
    @PostMapping("/{convId}/transfer")
    public R<Void> transfer(@CurrentUser Long userId,
                            @PathVariable Long convId,
                            @RequestParam Long targetId) {
        groupService.transferOwner(userId, convId, targetId);
        return R.ok(null);
    }
}
