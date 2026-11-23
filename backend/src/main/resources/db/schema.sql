-- 遇见 IM 建表脚本（幂等，应用启动时自动执行）
-- 字符集用 utf8mb4：昵称/消息内容里的 emoji 需要 4 字节

CREATE TABLE IF NOT EXISTS im_user (
    id            BIGINT       NOT NULL COMMENT '用户ID',
    username      VARCHAR(32)  NOT NULL COMMENT '登录名，唯一',
    password_hash VARCHAR(100) NOT NULL COMMENT 'BCrypt 哈希，绝不存明文',
    nickname      VARCHAR(32)  NOT NULL COMMENT '昵称',
    avatar        VARCHAR(255) DEFAULT NULL COMMENT '头像URL',
    online        TINYINT      NOT NULL DEFAULT 0 COMMENT '最后已知在线状态，仅作展示用，真相在 Redis',
    created_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_username (username)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT '用户';

CREATE TABLE IF NOT EXISTS im_conversation (
    id           BIGINT      NOT NULL COMMENT '会话ID',
    type         TINYINT     NOT NULL COMMENT '1=单聊 2=群聊',
    name         VARCHAR(64) DEFAULT NULL COMMENT '群名，单聊为 NULL',
    owner_id     BIGINT      DEFAULT NULL COMMENT '群主ID',
    seq          BIGINT      NOT NULL DEFAULT 0 COMMENT '会话当前最大序号，消息定序的唯一依据',
    last_preview VARCHAR(128) DEFAULT NULL COMMENT '最后一条消息摘要，会话列表直接用',
    last_msg_at  DATETIME    DEFAULT NULL COMMENT '最后一条消息时间，会话列表排序键',
    -- 单聊去重：把两个用户ID按大小拼成 pair_key，同一个 pair 只允许一个会话
    pair_key     VARCHAR(64) DEFAULT NULL COMMENT '单聊场景的 (minId:maxId)，唯一',
    created_at   DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_pair_key (pair_key)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT '会话';

CREATE TABLE IF NOT EXISTS im_conversation_member (
    id              BIGINT   NOT NULL COMMENT '主键（MyBatis-Plus 需要单一主键，这里用雪花ID）',
    conversation_id BIGINT   NOT NULL,
    user_id         BIGINT   NOT NULL,
    last_read_seq   BIGINT   NOT NULL DEFAULT 0 COMMENT '该成员已读到的最大 seq，未读数 = 会话seq - 这个值',
    delivered_seq   BIGINT   NOT NULL DEFAULT 0 COMMENT '该成员已确认收到的最大 seq，断线重连从这里续传（ack 推进）',
    role            TINYINT  NOT NULL DEFAULT 0 COMMENT '0=成员 1=群主',
    -- 退群 / 被踢是软删：行保留、left_at 置时间。
    -- 为什么不 DELETE：① 已读位点、入群时间这些数据删了就没了，审计和"重新入群恢复"都没法做；
    --               ② 硬删一行后如果有人重新入群，uk_conv_user 会先冲突再插入，多一步；
    --               ③ "他什么时候退的群"这类问题只有保留行才答得上来。
    -- 代价是每个"查成员"的 SQL 都必须记得带 left_at IS NULL，漏一个就会把退群的人算进去。
    left_at         DATETIME DEFAULT NULL COMMENT '非空表示已退群/被踢，不再接收消息、不再出现在成员列表',
    joined_at       DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_conv_user (conversation_id, user_id),
    KEY idx_user (user_id) COMMENT '按用户查他参与的会话'
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT '会话成员 + 已读位点';

CREATE TABLE IF NOT EXISTS im_message (
    id              BIGINT      NOT NULL COMMENT '消息ID（雪花），对外就是 msgId',
    conversation_id BIGINT      NOT NULL,
    seq             BIGINT      NOT NULL COMMENT '会话内自增序号，客户端按它排序、补洞、去重',
    from_user_id    BIGINT      NOT NULL,
    type            TINYINT     NOT NULL DEFAULT 1 COMMENT '1=文本 2=图片(占位) 3=系统消息',
    content         TEXT        NOT NULL,
    client_msg_id   VARCHAR(64) DEFAULT NULL COMMENT '客户端生成的幂等键，重发时靠它去重',
    -- 撤回是软删：行还在、seq 还占着位置，只把 revoked 置 1。
    -- 为什么不能真删：① 删了会话 seq 就出现空洞，未读数 = 会话seq - 已读位点 会算错；
    --              ② 客户端按 seq 连续补洞的逻辑会永远卡在那个缺口上；
    --              ③ 对方手里可能已经有这条消息，服务端删了也没法撤销对方的副本，
    --                 只能靠"撤回"这个状态让对方改渲染。
    revoked         TINYINT     NOT NULL DEFAULT 0 COMMENT '1=已撤回（软删，content 仍保留原文仅供审计）',
    revoked_at      DATETIME(3) DEFAULT NULL COMMENT '撤回时间',
    created_at      DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '毫秒精度，同一秒多条消息不会乱序',
    PRIMARY KEY (id),
    UNIQUE KEY uk_conv_seq (conversation_id, seq) COMMENT '这条唯一索引是"不重不丢"的最后一道闸门',
    UNIQUE KEY uk_client_msg (from_user_id, client_msg_id) COMMENT '同一发送者的同一 clientMsgId 只能落一条',
    KEY idx_conv_seq (conversation_id, seq) COMMENT '拉历史/拉离线：where conversation_id=? and seq>? order by seq',
    -- 聊天记录搜索：ngram 是 MySQL 内置中文分词器（ngram_token_size 默认 2，按二元组切）
    -- 有了它，搜"粘包问题"走索引，而不是 LIKE '%粘包问题%' 的全表扫描
    FULLTEXT KEY ft_content (content) WITH PARSER ngram
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT '消息';

-- 好友关系：一行 = "owner 把 friend 加为好友"
-- 为什么单向一行而不是一行存两个人，见 Friend.java 的注释
CREATE TABLE IF NOT EXISTS im_friend (
    id         BIGINT   NOT NULL COMMENT '主键（雪花）',
    owner_id   BIGINT   NOT NULL COMMENT '关系属于谁',
    friend_id  BIGINT   NOT NULL COMMENT '被加为好友的人',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_owner_friend (owner_id, friend_id) COMMENT '防止重复添加，同时是"我的好友列表"的覆盖索引',
    KEY idx_friend (friend_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT '好友关系（单向存储，双向各一行）';
