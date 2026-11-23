package com.yujian.im.common;

/**
 * Redis key 统一在这里定义。
 *
 * <p>散落在各个 Service 里手写字符串是维护灾难：改个前缀要全文搜，
 * 还容易出现 "im:user:" 和 "im:user" 两种写法各自占一份内存。
 */
public final class RedisKeys {

    private RedisKeys() {
    }

    /** 会话 seq 发号器：im:conv:seq:{conversationId} —— 每个会话一个自增计数器。 */
    public static String convSeq(Long conversationId) {
        return "im:conv:seq:" + conversationId;
    }

    /** 幂等去重：im:msg:idem:{fromUserId}:{clientMsgId} —— value 是已经落库的 msgId。 */
    public static String msgIdempotent(Long fromUserId, String clientMsgId) {
        return "im:msg:idem:" + fromUserId + ":" + clientMsgId;
    }

    /** 用户在线状态：im:online:{userId} —— value 是连接所在的节点标识，用于跨节点投递。 */
    public static String online(Long userId) {
        return "im:online:" + userId;
    }

    /** 未读数：im:unread:{userId} —— hash 结构，field = conversationId，value = 未读数。 */
    public static String unread(Long userId) {
        return "im:unread:" + userId;
    }

    /** 用户已读位点：im:read:{userId} —— hash 结构，field = conversationId，value = lastReadSeq。 */
    public static String readSeq(Long userId) {
        return "im:read:" + userId;
    }

    /** 离线消息队列：im:offline:{userId} —— list，消息体是 msgId。 */
    public static String offlineQueue(Long userId) {
        return "im:offline:" + userId;
    }

    /** 群成员缓存：im:group:members:{conversationId} —— set。 */
    public static String groupMembers(Long conversationId) {
        return "im:group:members:" + conversationId;
    }

    /** 会话成员列表缓存：im:conv:members:{conversationId}。 */
    public static String convMembers(Long conversationId) {
        return "im:conv:members:" + conversationId;
    }

    /** 用户会话列表缓存：im:user:convs:{userId} —— zset，score 是最后消息时间。 */
    public static String userConversations(Long userId) {
        return "im:user:convs:" + userId;
    }
}
