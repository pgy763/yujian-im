package com.yujian.im.controller;

import com.yujian.im.auth.CurrentUser;
import com.yujian.im.common.R;
import com.yujian.im.conversation.Conversation;
import com.yujian.im.conversation.ConversationService;
import com.yujian.im.conversation.ConversationService.ConversationVO;
import com.yujian.im.message.MessageService;
import com.yujian.im.protocol.dto.ConversationDetail;
import com.yujian.im.protocol.dto.MessageVO;
import com.yujian.im.session.SessionRegistry;
import com.yujian.im.user.User;
import com.yujian.im.user.UserService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 会话与历史消息接口。
 *
 * <p>这些操作全部是"一问一答"，所以走 HTTP 而不是长连接——
 * 它们能用上 HTTP 的缓存、压缩、网关限流，而且不会占用长连接的 EventLoop 线程。
 * 长连接只留给"服务端要主动推"的场景。
 */
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class ConversationController {

    private final ConversationService conversationService;
    private final MessageService messageService;
    private final UserService userService;
    private final SessionRegistry sessionRegistry;

    @Data
    public static class CreateSingleReq {
        @NotNull(message = "targetUserId 不能为空")
        private Long targetUserId;
    }

    @Data
    public static class CreateGroupReq {
        @NotBlank(message = "群名不能为空")
        @Size(max = 64, message = "群名最长 64 字符")
        private String name;
        private List<Long> memberIds;
    }

    /** 我的会话列表（按最后消息时间倒序，带未读数）。 */
    @GetMapping("/conversations")
    public R<List<ConversationVO>> list(@CurrentUser Long userId) {
        return R.ok(conversationService.listOf(userId));
    }

    /** 建（或取）单聊会话。幂等：重复调用返回同一个。 */
    @PostMapping("/conversations/single")
    public R<ConversationDetail> createSingle(@CurrentUser Long userId,
                                              @RequestBody @Valid CreateSingleReq req) {
        return R.ok(toDetail(conversationService.createOrGetSingle(userId, req.getTargetUserId())));
    }

    @PostMapping("/conversations/group")
    public R<ConversationDetail> createGroup(@CurrentUser Long userId,
                                             @RequestBody @Valid CreateGroupReq req) {
        return R.ok(toDetail(conversationService.createGroup(userId, req.getName(), req.getMemberIds())));
    }

    /**
     * 实体 → DTO。
     *
     * <p>不直接返回实体的原因见 {@link ConversationDetail} 的注释：
     * 既避免泄漏 pair_key 这类内部字段，也保证 seq 在所有接口里都是数字而不是字符串。
     */
    private ConversationDetail toDetail(Conversation c) {
        return new ConversationDetail(
                c.getId(), c.getType(), c.getName(),
                c.getSeq() == null ? 0L : c.getSeq(), c.getCreatedAt());
    }

    /**
     * 历史消息，向上翻页。
     *
     * <p>{@code beforeSeq} 传当前列表最旧一条的 seq；首次进入不传（默认查最新一页）。
     * 返回是正序（旧 → 新），前端直接 prepend。
     */
    @GetMapping("/conversations/{convId}/messages")
    public R<List<MessageVO>> history(@CurrentUser Long userId,
                                      @PathVariable Long convId,
                                      @RequestParam(required = false) Long beforeSeq,
                                      @RequestParam(defaultValue = "20") int limit) {
        return R.ok(messageService.history(userId, convId, beforeSeq, limit));
    }

    /** 标记已读。走 HTTP 是因为它不是"实时"操作，晚一秒没人在意。 */
    @PostMapping("/conversations/{convId}/read")
    public R<Long> markRead(@CurrentUser Long userId,
                            @PathVariable Long convId,
                            @RequestParam long seq) {
        return R.ok(conversationService.markRead(userId, convId, seq));
    }

    /** 成员 id 列表。轻量，给"改动最少"的地方用。 */
    @GetMapping("/conversations/{convId}/members")
    public R<List<Long>> members(@CurrentUser Long userId, @PathVariable Long convId) {
        conversationService.assertMember(convId, userId);
        return R.ok(conversationService.memberIds(convId));
    }

    /**
     * 成员详情（带头像色、昵称、在线状态）。群聊的"成员与连接信息"面板用它。
     *
     * <p>为什么不干脆让前端自己拿 id 再去 /api/users/search 查？
     * 因为 search 是按关键字模糊匹配，没有"按 id 批量查"的语义，
     * 前端要发 N 次请求；这里一次 IN 查询解决，也顺手把在线状态带出来。
     */
    @GetMapping("/conversations/{convId}/member-infos")
    public R<List<Map<String, Object>>> memberInfos(@CurrentUser Long userId, @PathVariable Long convId) {
        conversationService.assertMember(convId, userId);
        List<Long> ids = conversationService.memberIds(convId);
        Map<Long, User> users = userService.mapByIds(ids);
        List<Map<String, Object>> out = new ArrayList<>(ids.size());
        for (Long id : ids) {
            User u = users.get(id);
            Map<String, Object> m = new LinkedHashMap<>(8);
            m.put("id", id);
            m.put("username", u == null ? null : u.getUsername());
            m.put("nickname", u == null ? null : u.getNickname());
            m.put("avatar", u == null ? null : u.getAvatar());
            m.put("online", sessionRegistry.isOnline(id));
            out.add(m);
        }
        return R.ok(out);
    }
}
