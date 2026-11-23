package com.yujian.im.conversation;

import com.yujian.im.common.BizException;
import com.yujian.im.common.SnowflakeIdGenerator;
import com.yujian.im.protocol.dto.GroupEventPush;
import com.yujian.im.push.PushService;
import com.yujian.im.user.User;
import com.yujian.im.user.UserService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 群成员管理：拉人、退群、踢人、转让群主。
 *
 * <p><b>退群用软删（{@code left_at}）而不是删行，这是本类最重要的决策。</b>
 * 好处：审计可查、重新入群能恢复历史、已读位点不丢。
 * 代价：<b>每一句查成员的 SQL 都必须带 {@code left_at IS NULL}</b> ——
 * 这是软删最容易翻车的地方，漏一处就会有"退群的人还在群里"的幽灵 bug。
 * 我在 {@link ConversationMemberMapper} 的类注释里也重复强调了这一条。
 *
 * <p><b>权限模型（刻意做简单）：</b>
 * <ul>
 *   <li>拉人：群主可以拉，普通成员也可以拉（和微信群一致）；</li>
 *   <li>退群：谁都能退自己；</li>
 *   <li>踢人：只有群主；</li>
 *   <li>转让群主：只有群主。转让后老群主降为普通成员，不是退群。</li>
 * </ul>
 * 真实的 QQ/钉钉有管理员、禁言、审批入群等一整套，这里只做最小可用集，
 * 面试时说明"知道完整模型长什么样，但这个项目刻意只做核心路径"。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GroupService {

    /** 和 ConversationService 里保持一致的上限。 */
    private static final int MAX_MEMBERS = 200;

    private final ConversationMapper conversationMapper;
    private final ConversationMemberMapper memberMapper;
    private final ConversationService conversationService;
    private final UserService userService;
    private final PushService pushService;
    private final SnowflakeIdGenerator idGenerator;

    /**
     * 拉人入群。
     *
     * <p>已经被踢过的人重新拉，走 {@code reviveMember} 把 {@code left_at} 清空，
     * 而不是插一行新的 —— {@code uk_conv_user} 唯一索引会让新插入直接冲突。
     */
    @Transactional
    public List<String> addMembers(Long operatorId, Long convId, Collection<Long> userIds) {
        Conversation conv = requireGroup(convId);
        conversationService.assertMember(convId, operatorId);

        if (userIds == null || userIds.isEmpty()) {
            throw BizException.of("请选择要拉入的人");
        }
        Set<Long> targets = new LinkedHashSet<>(userIds);
        targets.remove(null);

        List<Long> current = memberMapper.selectUserIds(convId);
        Set<Long> currentSet = new LinkedHashSet<>(current);

        List<Long> toAdd = new ArrayList<>();
        for (Long id : targets) {
            if (!currentSet.contains(id)) {
                toAdd.add(id);
            }
        }
        if (toAdd.isEmpty()) {
            return List.of(); // 全都是已在群里的，幂等返回
        }
        if (current.size() + toAdd.size() > MAX_MEMBERS) {
            throw BizException.of("群成员上限 " + MAX_MEMBERS + " 人");
        }

        // 被拉的人必须真实存在，否则会造出指向不存在用户的成员行
        var users = userService.mapByIds(toAdd);
        List<String> added = new ArrayList<>();
        for (Long uid : toAdd) {
            if (!users.containsKey(uid)) {
                continue; // 用户不存在就跳过，不整体失败——批量操作里一个坏 id 不该拖垮其他
            }
            ConversationMember exist = memberMapper.selectOneByConvAndUser(convId, uid);
            if (exist != null) {
                // 曾经退过群：复活那一行，历史消息和已读位点都还在
                memberMapper.reviveMember(convId, uid);
            } else {
                addMemberRow(convId, uid, 0);
            }
            added.add(String.valueOf(uid));
        }

        if (!added.isEmpty()) {
            // 注意这里传的是"群里现在一共多少人"，不是"这次加了几个"。
            // 第一版写成 added.size()，测试里断言 memberCount==4 直接挂了 ——
            // 客户端提示"XX 加入了群聊（4 人）"用的就是这个数，传错会显示成"（1 人）"。
            pushEvent(conv, "join", null, operatorId, memberMapper.selectUserIds(convId).size());
        }
        log.info("群 {} 拉入 {} 人 by {}", convId, added.size(), operatorId);
        return added;
    }

    /**
     * 退群。自己退。
     *
     * <p>退群后历史消息还看不看得见？<b>看不见</b>。
     * 因为 {@code assertMember} 会挡住所有"拉历史/拉离线"的请求。
     * 这和微信一致（退群后聊天记录还在本地，但拉不到新的，也搜不到）。
     * 如果产品要求"退群后还能看历史"，那就得把 assertMember 拆成
     * "能读"和"能写"两种权限 —— 那是另一套模型，本项目不做。
     */
    @Transactional
    public void leave(Long userId, Long convId) {
        Conversation conv = requireGroup(convId);
        conversationService.assertMember(convId, userId);

        ConversationMember me = memberMapper.selectOneByConvAndUser(convId, userId);
        if (me != null && me.getRole() != null && me.getRole() == 1) {
            // 群主不能直接退出，必须先把群主转让给别人。
            // 否则群就变成没有 owner 的孤儿，以后谁也删不掉、谁也管不了。
            throw BizException.of("群主不能直接退群，请先转让群主");
        }

        memberMapper.markLeft(convId, userId);
        pushEvent(conv, "leave", userId, userId, memberMapper.selectUserIds(convId).size());
        log.info("用户 {} 退出群 {} by self", userId, convId);
    }

    /**
     * 踢人。只有群主能踢。
     *
     * <p>踢完之后被踢的人不会收到 {@code GROUP_EVENT_PUSH} —— 他已经不是成员，
     * 按"成员列表"投递的推送自然轮不到他。这是正确行为：
     * 他下次发消息时才会遇到"你不在该会话中"。
     */
    @Transactional
    public void kick(Long operatorId, Long convId, Long targetId) {
        Conversation conv = requireGroup(convId);
        requireOwner(conv, operatorId);

        if (operatorId.equals(targetId)) {
            throw BizException.of("不能踢自己，请用退群");
        }
        if (memberMapper.selectReadSeq(convId, targetId) == null) {
            throw BizException.of("这个人不在群里");
        }

        memberMapper.markLeft(convId, targetId);
        pushEvent(conv, "kick", targetId, operatorId, memberMapper.selectUserIds(convId).size());
        log.info("群 {} 踢出 {} by {}", convId, targetId, operatorId);
    }

    /**
     * 转让群主。
     *
     * <p>两行 UPDATE 必须在一个事务里：老群主降级 + 新群主升级。
     * 中间如果断了，群就同时没有群主（两行都降级）或者有两个群主
     * （老群主没降级成功但新人已升级）——两者都会让后续的权限判断出错。
     *
     * <p>{@code im_conversation.owner_id} 也要同步改，因为 {@code requireOwner}
     * 读的是会话表上那个字段，成员表的 role 只是用来展示"谁是群主"。
     * <b>两份数据存同一件事是设计缺陷</b>，正常应该只有一个真相来源。
     * 这里保留是因为 {@code owner_id} 在会话列表查询里要用，跨表查代价更高。
     * 面试时要主动说这是"为了读性能做的冗余，代价是必须在一个事务里同步更新"。
     */
    @Transactional
    public void transferOwner(Long operatorId, Long convId, Long newOwnerId) {
        Conversation conv = requireGroup(convId);
        requireOwner(conv, operatorId);

        if (operatorId.equals(newOwnerId)) {
            throw BizException.of("你已经是群主了");
        }
        if (memberMapper.selectReadSeq(convId, newOwnerId) == null) {
            throw BizException.of("这个人不在群里");
        }

        conversationMapper.updateOwner(convId, newOwnerId);
        memberMapper.updateRole(convId, newOwnerId, 1);
        memberMapper.updateRole(convId, operatorId, 0);

        pushEvent(conv, "transfer", newOwnerId, operatorId, memberMapper.selectUserIds(convId).size());
        log.info("群 {} 群主由 {} 转让给 {}", convId, operatorId, newOwnerId);
    }

    /** 群成员详情：包含角色、入群时间，比 member-infos 多这两项。 */
    public List<java.util.Map<String, Object>> members(Long userId, Long convId) {
        conversationService.assertMember(convId, userId);
        List<ConversationMember> rows = memberMapper.selectByConversation(convId);
        List<Long> ids = new ArrayList<>(rows.size());
        for (ConversationMember m : rows) {
            ids.add(m.getUserId());
        }
        var users = userService.mapByIds(ids);

        List<java.util.Map<String, Object>> out = new ArrayList<>(rows.size());
        for (ConversationMember m : rows) {
            User u = users.get(m.getUserId());
            java.util.Map<String, Object> item = new java.util.LinkedHashMap<>(8);
            item.put("id", String.valueOf(m.getUserId()));
            item.put("username", u == null ? null : u.getUsername());
            item.put("nickname", u == null ? null : u.getNickname());
            item.put("role", m.getRole());
            item.put("joinedAt", m.getJoinedAt());
            out.add(item);
        }
        return out;
    }

    // ---------------------------------------------------------------- 内部

    private Conversation requireGroup(Long convId) {
        Conversation conv = conversationService.get(convId);
        if (conv.getType() == null || conv.getType() != Conversation.TYPE_GROUP) {
            throw BizException.of("只有群聊支持这个操作");
        }
        return conv;
    }

    private void requireOwner(Conversation conv, Long userId) {
        if (conv.getOwnerId() == null || !conv.getOwnerId().equals(userId)) {
            throw BizException.forbidden("只有群主能执行这个操作");
        }
    }

    private void addMemberRow(Long convId, Long userId, int role) {
        ConversationMember m = new ConversationMember();
        m.setId(idGenerator.nextId());
        m.setConversationId(convId);
        m.setUserId(userId);
        m.setLastReadSeq(0L);
        m.setDeliveredSeq(0L);
        m.setRole(role);
        m.setJoinedAt(java.time.LocalDateTime.now());
        memberMapper.insert(m);
    }

    /** 推一条成员变动通知给群里剩下的人。 */
    private void pushEvent(Conversation conv, String event, Long targetId, Long operatorId, int memberCount) {
        var users = userService.mapByIds(java.util.Arrays.asList(
                targetId == null ? operatorId : targetId, operatorId));
        User target = targetId == null ? null : users.get(targetId);
        User operator = users.get(operatorId);

        GroupEventPush push = new GroupEventPush(
                conv.getId(), event, targetId,
                target == null ? null : target.getNickname(),
                operatorId, operator == null ? null : operator.getNickname(),
                memberCount);
        pushService.pushGroupEvent(conv, push);
    }
}
