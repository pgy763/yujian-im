package com.yujian.im.message;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

@Mapper
public interface MessageMapper extends BaseMapper<Message> {

    /**
     * 按会话 + seq 区间拉消息。
     *
     * <p>必须是 where conversation_id = ? and seq > ? order by seq limit ?：
     * 走 idx_conv_seq 覆盖索引，几千条消息的会话也是毫秒级。
     * 反面教材是 where 1=1 order by created_at —— 时间戳可能重复且不连续，没法当游标。
     */
    @Select("""
            SELECT * FROM im_message
            WHERE conversation_id = #{convId} AND seq > #{afterSeq}
            ORDER BY seq ASC
            LIMIT #{limit}
            """)
    List<Message> selectBySeqAfter(@Param("convId") Long convId,
                                   @Param("afterSeq") Long afterSeq,
                                   @Param("limit") int limit);

    /**
     * 反向拉最近 N 条（进入会话时先看最新的）。
     * 用 seq < afterSeq order by seq desc limit N，再在内存里反转正序。
     */
    @Select("""
            SELECT * FROM im_message
            WHERE conversation_id = #{convId} AND seq < #{beforeSeq}
            ORDER BY seq DESC
            LIMIT #{limit}
            """)
    List<Message> selectBySeqBefore(@Param("convId") Long convId,
                                    @Param("beforeSeq") Long beforeSeq,
                                    @Param("limit") int limit);

    @Select("SELECT COALESCE(MAX(seq), 0) FROM im_message WHERE conversation_id = #{convId}")
    Long selectMaxSeq(@Param("convId") Long convId);

    /** 幂等兜底：Redis 挂了或 key 过期时，直接查库判断这条 clientMsgId 是否已落。 */
    @Select("""
            SELECT * FROM im_message
            WHERE from_user_id = #{fromUserId} AND client_msg_id = #{clientMsgId}
            LIMIT 1
            """)
    Message selectByClientMsgId(@Param("fromUserId") Long fromUserId,
                                @Param("clientMsgId") String clientMsgId);

    /**
     * 批量按 ID 查，用于"离线队列里只有 msgId，需要回表取内容"。
     * 用 IN 一次查完，不要循环单查（N+1）。
     */
    @Select("""
            <script>
            SELECT * FROM im_message WHERE id IN
            <foreach collection="ids" item="id" open="(" separator="," close=")">#{id}</foreach>
            ORDER BY seq ASC
            </script>
            """)
    List<Message> selectMessagesByIds(@Param("ids") java.util.Collection<Long> ids);

    @Select("""
            <script>
            SELECT COUNT(*) FROM im_message
            WHERE conversation_id = #{convId} AND seq &gt; #{afterSeq}
            </script>
            """)
    long countAfter(@Param("convId") Long convId, @Param("afterSeq") Long afterSeq);

    /**
     * 撤回消息：把 revoked 翻成 1。
     *
     * <p>{@code AND revoked = 0} 是幂等的关键：撤回两次只有第一次的
     * affected rows 是 1，第二次是 0。调用方据此判断"这次是不是真的撤回了"，
     * 从而决定要不要再广播一次 PUSH_REVOKE —— 否则用户点两次撤回，
     * 群里每个人都会收到两条"撤回"推送。
     *
     * <p>条件里还要带 {@code from_user_id = #{fromUserId}}：只有发送者本人能撤回，
     * 这一句同时充当了权限校验，比先查出来再在 Java 里 if 更安全
     * （查和改之间有竞态窗口）。
     */
    @Update("""
            UPDATE im_message SET revoked = 1, revoked_at = NOW(3)
            WHERE id = #{msgId} AND conversation_id = #{convId}
              AND from_user_id = #{fromUserId} AND revoked = 0
            """)
    int markRevoked(@Param("msgId") Long msgId,
                    @Param("convId") Long convId,
                    @Param("fromUserId") Long fromUserId);

    /** 会话里最后一条"正常"消息的 seq，撤回后重算 last_preview 用。 */
    @Select("""
            SELECT * FROM im_message
            WHERE conversation_id = #{convId} AND revoked = 0
            ORDER BY seq DESC LIMIT 1
            """)
    Message selectLastNormal(@Param("convId") Long convId);

    /**
     * 位点推进后，算"哪些人的消息被这一批已读覆盖了"。
     *
     * <p>场景：B 一口气从第 5 条读到第 20 条，其中 A 发了 6/9/12，C 发了 7/18。
     * A 和 C 都该收到"你的消息已被读"的回执，但<b>每个人只需要收一条</b>——
     * 回执是按人聚合的（"A 的消息被读了"），不是按消息逐条推。
     * 逐条推的话，一次已读能产生几十条推送，纯属骚扰。
     *
     * <p>所以这里用 {@code GROUP BY from_user_id} 取每个发送者在区间内的
     * <b>最大 seq 那条</b>，回执带上它，客户端拿这条的 seq 去更新
     * "我最后被读到的消息"标记即可。
     *
     * <p>{@code max(seq)} 与 {@code id} 的搭配在 MySQL 里依赖
     * {@code ONLY_FULL_GROUP_BY} 的宽松模式，严格模式下会有歧义。
     * 用 {@code ORDER BY seq DESC}+子查询虽然更"正确"，但这里数据量很小
     * （一个会话内的消息），且 MySQL 8 在这种情况下会选到 max 对应的那行。
     * 更稳妥的写法是先 GROUP BY 取 max(seq)，再 join 回原表，
     * 但那是把一次查询变两次，不值得——真要严格就改用窗口函数 ROW_NUMBER()。
     */
    @Select("""
            SELECT * FROM im_message m
            WHERE m.conversation_id = #{convId}
              AND m.seq <= #{maxSeq}
              AND m.seq > #{afterSeq}
              AND m.from_user_id <> #{readerId}
              AND m.seq = (
                  SELECT MAX(m2.seq) FROM im_message m2
                  WHERE m2.conversation_id = m.conversation_id
                    AND m2.from_user_id = m.from_user_id
                    AND m2.seq <= #{maxSeq} AND m2.seq > #{afterSeq}
              )
            """)
    List<Message> selectLatestPerSenderInRange(@Param("convId") Long convId,
                                               @Param("afterSeq") Long afterSeq,
                                               @Param("maxSeq") Long maxSeq,
                                               @Param("readerId") Long readerId);

    /**
     * 会话内搜聊天记录 —— 全文索引版（关键字 ≥ 2 个字符时走这条）。
     *
     * <p>表上有 {@code FULLTEXT(content) WITH PARSER ngram}。ngram 是 MySQL 内置的
     * 中文分词器：{@code ngram_token_size=2}（默认值），把"粘包问题"切成
     * 粘包/包问/问题 这样的二元组，所以中文不需要额外装 IK 分词插件也能走索引。
     *
     * <p>查询写成 {@code AGAINST('"关键字"' IN BOOLEAN MODE)} —— <b>带双引号是短语匹配</b>，
     * 要求分词后的二元组按顺序相邻出现；不加引号会退化成"任一 token 命中"，
     * 搜"粘包问题"会把只含"问题"的消息也搜出来。
     *
     * <p>为什么不用 {@code LIKE '%关键字%'}：前导 % 让 B+ 树索引彻底失效，
     * 只能全表扫描 + 逐行字符串匹配。几万条消息时是几十毫秒到几百毫秒的差别，
     * 而且随消息量线性变差。实测见 README §7。
     */
    @Select("""
            SELECT m.* FROM im_message m
            WHERE m.conversation_id = #{convId}
              AND MATCH(m.content) AGAINST(#{phrase} IN BOOLEAN MODE)
            ORDER BY m.seq DESC
            LIMIT #{limit}
            """)
    List<Message> searchByFulltextInConv(@Param("convId") Long convId,
                                         @Param("phrase") String phrase,
                                         @Param("limit") int limit);

    /**
     * 会话内搜聊天记录 —— LIKE 版（关键字只有 1 个字符时用）。
     *
     * <p>必须保留这条兜底：ngram_token_size=2 意味着单个汉字<strong>根本不会被建索引</strong>，
     * 用 MATCH 搜一个字永远返回 0 行。这不是 bug，是 ngram 的固有行为；
     * 要么改 token_size=1（索引体积翻倍），要么对单字请求退回 LIKE。
     * 选后者：单字搜索本来就少见，且用户只搜一个字的场景下数据量不会太大。
     */
    @Select("""
            SELECT * FROM im_message
            WHERE conversation_id = #{convId} AND content LIKE CONCAT('%', #{keyword}, '%')
            ORDER BY seq DESC
            LIMIT #{limit}
            """)
    List<Message> searchByLikeInConv(@Param("convId") Long convId,
                                     @Param("keyword") String keyword,
                                     @Param("limit") int limit);

    /**
     * 全局搜（跨我会话）的全文版。
     *
     * <p>用 {@code EXISTS} 子查询把范围锁死在"我是成员"的会话里 ——
     * 这是权限边界，绝不能让用户搜到别人的聊天记录。
     * 先按会话成员过滤再 MATCH，优化器能把会话 id 集合当驱动表。
     */
    @Select("""
            SELECT m.* FROM im_message m
            WHERE MATCH(m.content) AGAINST(#{phrase} IN BOOLEAN MODE)
              AND EXISTS (
                  SELECT 1 FROM im_conversation_member cm
                  WHERE cm.conversation_id = m.conversation_id AND cm.user_id = #{userId}
              )
            ORDER BY m.created_at DESC
            LIMIT #{limit}
            """)
    List<Message> searchByFulltextGlobal(@Param("userId") Long userId,
                                         @Param("phrase") String phrase,
                                         @Param("limit") int limit);

    @Select("""
            SELECT m.* FROM im_message m
            WHERE m.content LIKE CONCAT('%', #{keyword}, '%')
              AND EXISTS (
                  SELECT 1 FROM im_conversation_member cm
                  WHERE cm.conversation_id = m.conversation_id AND cm.user_id = #{userId}
              )
            ORDER BY m.created_at DESC
            LIMIT #{limit}
            """)
    List<Message> searchByLikeGlobal(@Param("userId") Long userId,
                                     @Param("keyword") String keyword,
                                     @Param("limit") int limit);
}
