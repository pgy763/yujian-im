package com.yujian.im.search;

import com.yujian.im.common.BizException;
import com.yujian.im.conversation.Conversation;
import com.yujian.im.conversation.ConversationMapper;
import com.yujian.im.conversation.ConversationMemberMapper;
import com.yujian.im.conversation.ConversationService;
import com.yujian.im.message.Message;
import com.yujian.im.message.MessageMapper;
import com.yujian.im.user.User;
import com.yujian.im.user.UserService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 聊天记录搜索。
 *
 * <p>核心决策：<b>能用全文索引就不用 LIKE</b>。
 * <ul>
 *   <li>{@code LIKE '%x%'} 的前导通配符让 B+ 树索引完全失效，只能全表扫描；</li>
 *   <li>本表上建了 {@code FULLTEXT(content) WITH PARSER ngram}，中文靠 ngram 二元组切词，
 *       不需要额外装 IK 分词器；</li>
 *   <li>但 {@code ngram_token_size=2} 决定了<b>单字查询永远不会命中</b>，
 *       所以长度 &lt; 2 的关键字退回 LIKE —— 这不是妥协，是分词器的固有语义。</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SearchService {

    /** 一次最多返回 100 条。搜索结果不是"翻页列表"，而是"跳到那一条"的入口。 */
    private static final int MAX_LIMIT = 100;

    private final MessageMapper messageMapper;
    private final ConversationMapper conversationMapper;
    private final ConversationMemberMapper memberMapper;
    private final ConversationService conversationService;
    private final UserService userService;

    /**
     * 会话内搜索。
     *
     * @param convId 目标会话；为 null 表示全局搜（跨我参与的所有会话）
     */
    public List<SearchHit> search(Long userId, Long convId, String keyword, int limit) {
        if (keyword == null || keyword.isBlank()) {
            throw BizException.of("搜索关键字不能为空");
        }
        String kw = keyword.trim();
        int safeLimit = Math.min(Math.max(limit, 1), MAX_LIMIT);

        // 权限边界：会话内搜索必须先证明"你在这个会话里"，
        // 否则给个别人的 convId 就能读别人的聊天记录。
        if (convId != null) {
            conversationService.assertMember(convId, userId);
        }

        List<Message> msgs;
        boolean fulltext = kw.length() >= 2;

        if (convId != null) {
            msgs = fulltext
                    ? messageMapper.searchByFulltextInConv(convId, phrase(kw), safeLimit)
                    : messageMapper.searchByLikeInConv(convId, kw, safeLimit);
        } else {
            msgs = fulltext
                    ? messageMapper.searchByFulltextGlobal(userId, phrase(kw), safeLimit)
                    : messageMapper.searchByLikeGlobal(userId, kw, safeLimit);
        }

        log.debug("搜索 userId={} convId={} kw={} fulltext={} 命中={}",
                userId, convId, kw, fulltext, msgs.size());
        return toHits(msgs, userId);
    }

    /**
     * 把用户输入包装成 BOOLEAN MODE 的短语。
     *
     * <p>两个必须处理的点：
     * <ol>
     *   <li><b>加双引号</b>变成短语匹配，否则"粘包问题"会命中只含"问题"的消息；</li>
     *   <li><b>转义双引号</b>，否则用户输入一个 {@code "} 就能把语法搞坏，
     *       直接抛 SQL 语法错误（这不是注入，因为走的是预编译参数，但会让接口 500）。</li>
     * </ol>
     * 另外 BOOLEAN MODE 里有特殊含义的 {@code + - * ( ) ~ < > @ "} 在短语内部会被当成普通字符，
     * 所以包进引号后不需要逐个转义。
     */
    private String phrase(String kw) {
        return "\"" + kw.replace("\"", " ") + "\"";
    }

    /** 批量把发送者和会话标题查出来，避免逐条查（N+1）。 */
    private List<SearchHit> toHits(List<Message> msgs, Long meId) {
        if (msgs.isEmpty()) {
            return List.of();
        }
        Set<Long> senderIds = new LinkedHashSet<>();
        Set<Long> convIds = new LinkedHashSet<>();
        for (Message m : msgs) {
            senderIds.add(m.getFromUserId());
            convIds.add(m.getConversationId());
        }
        Map<Long, User> users = userService.mapByIds(senderIds);

        // 会话标题需要知道"单聊里的对方是谁"，所以每个单聊会话的成员都要查
        List<Conversation> convs = conversationMapper.selectBatchIds(convIds);
        Set<Long> peerIds = new LinkedHashSet<>();
        for (Conversation c : convs) {
            if (c.getType() == Conversation.TYPE_SINGLE) {
                peerIds.addAll(memberMapper.selectUserIds(c.getId()));
            }
        }
        Map<Long, User> peers = userService.mapByIds(peerIds);
        Map<Long, List<Long>> memberCache = new java.util.HashMap<>();

        List<SearchHit> out = new ArrayList<>(msgs.size());
        for (Message m : msgs) {
            User from = users.get(m.getFromUserId());
            out.add(SearchHit.builder()
                    .msgId(m.getId())
                    .conversationId(m.getConversationId())
                    .conversationTitle(titleOf(m.getConversationId(), convs, peers, meId, memberCache))
                    .conversationType(typeOf(m.getConversationId(), convs))
                    .fromUserId(m.getFromUserId())
                    .fromNickname(from == null ? null : from.getNickname())
                    .type(m.getType())
                    .content(m.getContent())
                    .seq(m.getSeq())
                    .createdAt(m.getCreatedAt())
                    .build());
        }
        return out;
    }

    /**
     * 会话标题：群聊用群名，单聊用<b>对方</b>的昵称。
     *
     * <p>单聊里的"对方"必须排除自己 —— 否则搜索结果里会显示成我自己的名字，
     * 用户完全看不出这条消息是在哪个对话里。
     */
    private String titleOf(Long convId, List<Conversation> convs, Map<Long, User> peers,
                           Long meId, Map<Long, List<Long>> memberCache) {
        for (Conversation c : convs) {
            if (!c.getId().equals(convId)) {
                continue;
            }
            if (c.getType() == Conversation.TYPE_GROUP) {
                return c.getName() == null ? "群聊" : c.getName();
            }
            List<Long> ids = memberCache.computeIfAbsent(convId, memberMapper::selectUserIds);
            for (Long uid : ids) {
                if (uid.equals(meId)) {
                    continue;
                }
                User u = peers.get(uid);
                if (u != null) {
                    return u.getNickname();
                }
            }
            return "单聊";
        }
        return "未知会话";
    }

    private Integer typeOf(Long convId, List<Conversation> convs) {
        for (Conversation c : convs) {
            if (c.getId().equals(convId)) {
                return c.getType();
            }
        }
        return null;
    }
}
