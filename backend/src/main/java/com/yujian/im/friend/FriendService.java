package com.yujian.im.friend;

import com.yujian.im.common.BizException;
import com.yujian.im.common.SnowflakeIdGenerator;
import com.yujian.im.session.SessionRegistry;
import com.yujian.im.user.User;
import com.yujian.im.user.UserService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 好友服务：候选列表、加好友、好友列表、删好友。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FriendService {

    /** 候选列表一次最多列 200 人，够演示也够拦住"全表拉一次"的滥用。 */
    private static final int MAX_CANDIDATES = 200;

    private final FriendMapper friendMapper;
    private final UserService userService;
    private final SessionRegistry sessionRegistry;
    private final SnowflakeIdGenerator idGenerator;

    /**
     * 还没加的人。
     *
     * <p>SQL 层就排掉了"自己"和"已加的人"，前端拿到的就是要选的清单，
     * 不需要在内存里做差集（那样一旦好友上万就白拉一遍数据）。
     */
    public List<Map<String, Object>> candidates(Long userId, int limit) {
        int safeLimit = Math.min(Math.max(limit, 1), MAX_CANDIDATES);
        List<User> users = friendMapper.selectCandidates(userId, safeLimit);
        List<Map<String, Object>> out = new ArrayList<>(users.size());
        for (User u : users) {
            out.add(toCard(u));
        }
        return out;
    }

    /** 我的好友列表（带在线状态）。 */
    public List<Map<String, Object>> listOf(Long userId) {
        List<Long> ids = friendMapper.selectFriendIds(userId);
        if (ids.isEmpty()) {
            return List.of();
        }
        Map<Long, User> users = userService.mapByIds(ids);
        List<Map<String, Object>> out = new ArrayList<>(ids.size());
        for (Long id : ids) {
            User u = users.get(id);
            if (u != null) {
                out.add(toCard(u));
            }
        }
        return out;
    }

    /**
     * 加好友。当前实现是<b>双向立即生效</b>：同时插 (me→target) 和 (target→me) 两行。
     *
     * <p>并发下两个请求都插同一对关系时，唯一索引 {@code uk_owner_friend} 会让后者抛
     * {@link DuplicateKeyException}。这里直接吞掉——"已经是好友了"对调用方来说
     * 结果与"加成功"完全一样，属于天然幂等的操作。
     */
    @Transactional
    public void add(Long userId, Long targetId) {
        if (targetId == null) {
            throw BizException.of("targetId 不能为空");
        }
        if (userId.equals(targetId)) {
            throw BizException.of("不能添加自己为好友");
        }
        // 确认这个人真的存在，避免插一条指向不存在用户的孤儿关系
        userService.getById(targetId);

        insertRelation(userId, targetId);
        insertRelation(targetId, userId);
        log.info("加好友: {} <-> {}", userId, targetId);
    }

    /** 删好友：双向都删。删不存在的关系不报错（幂等）。 */
    @Transactional
    public void remove(Long userId, Long targetId) {
        if (targetId == null) {
            throw BizException.of("targetId 不能为空");
        }
        friendMapper.deleteRelation(userId, targetId);
        friendMapper.deleteRelation(targetId, userId);
        log.info("删好友: {} </-> {}", userId, targetId);
    }

    /** 是否已经是好友（前端用来决定按钮文案是"添加"还是"发消息"）。 */
    public boolean isFriend(Long userId, Long targetId) {
        return friendMapper.countRelation(userId, targetId) > 0;
    }

    /** 批量判断好友关系，避免前端逐个问。 */
    public Set<Long> friendIdSet(Long userId) {
        return new LinkedHashSet<>(friendMapper.selectFriendIds(userId));
    }

    private void insertRelation(Long ownerId, Long friendId) {
        if (friendMapper.countRelation(ownerId, friendId) > 0) {
            return; // 已经是好友，直接成功（幂等）
        }
        Friend f = new Friend();
        f.setId(idGenerator.nextId());
        f.setOwnerId(ownerId);
        f.setFriendId(friendId);
        f.setCreatedAt(LocalDateTime.now());
        try {
            friendMapper.insert(f);
        } catch (DuplicateKeyException e) {
            // 并发重复添加：唯一索引兜底，吞掉即可
            log.debug("好友关系已存在（并发添加）: {} -> {}", ownerId, friendId);
        }
    }

    private Map<String, Object> toCard(User u) {
        Map<String, Object> m = new LinkedHashMap<>(8);
        m.put("id", u.getId());
        m.put("username", u.getUsername());
        m.put("nickname", u.getNickname());
        m.put("avatar", u.getAvatar());
        m.put("online", sessionRegistry.isOnline(u.getId()));
        return m;
    }
}
