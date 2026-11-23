package com.yujian.im.controller;

import com.yujian.im.auth.CurrentUser;
import com.yujian.im.common.R;
import com.yujian.im.search.SearchHit;
import com.yujian.im.search.SearchService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 聊天记录搜索。
 *
 * <p>为什么走 HTTP 而不是长连接：搜索是"一问一答"，用户输一个字就可能触发一次，
 * 放到长连接上会挤占真正需要实时性的消息推送；而且 HTTP 能直接吃到反向代理的 gzip 和缓存。
 */
@RestController
@RequestMapping("/api/search")
@RequiredArgsConstructor
public class SearchController {

    private final SearchService searchService;

    /**
     * 搜聊天记录。
     *
     * @param conversationId 传了就只在这个会话里搜，不传就是全局搜（跨我参与的所有会话）
     * @param keyword        关键字；只有 1 个字符时会退回 LIKE（ngram 分词器不索引单字）
     */
    @GetMapping("/messages")
    public R<List<SearchHit>> messages(@CurrentUser Long userId,
                                       @RequestParam(required = false) Long conversationId,
                                       @RequestParam String keyword,
                                       @RequestParam(defaultValue = "50") int limit) {
        return R.ok(searchService.search(userId, conversationId, keyword, limit));
    }
}
