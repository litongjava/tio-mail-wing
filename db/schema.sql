-- ----------------------------
-- 1. 用户表 (mw_user)
-- 存储系统用户信息和认证凭据
-- ----------------------------
drop table if exists mw_user;
CREATE TABLE mw_user (
  "id" BIGINT NOT NULL PRIMARY KEY,
  "username" VARCHAR(255) NOT NULL UNIQUE, -- 邮箱地址，必须唯一
  "password_hash" VARCHAR(255) NOT NULL,   -- 存储加密后的密码哈希值
  "remark" VARCHAR(256),
  "creator" VARCHAR(64) DEFAULT '',
  "create_time" TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
  "updater" VARCHAR(64) DEFAULT '',
  "update_time" TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
  "deleted" SMALLINT DEFAULT 0,
  "tenant_id" BIGINT NOT NULL DEFAULT 0
);

COMMENT ON TABLE mw_user IS '用户表';
COMMENT ON COLUMN mw_user.id IS '主键ID';
COMMENT ON COLUMN mw_user.username IS '用户名 (邮箱地址)';
COMMENT ON COLUMN mw_user.password_hash IS '加密后的密码哈希';
COMMENT ON COLUMN mw_user.remark IS '备注';
COMMENT ON COLUMN mw_user.creator IS '创建人';
COMMENT ON COLUMN mw_user.create_time IS '创建时间';
COMMENT ON COLUMN mw_user.updater IS '更新人';
COMMENT ON COLUMN mw_user.update_time IS '更新时间';
COMMENT ON COLUMN mw_user.deleted IS '逻辑删除标志 (0:未删除, 1:已删除)';
COMMENT ON COLUMN mw_user.tenant_id IS '租户ID';

-- ----------------------------
-- 2. 邮箱目录表 (mw_mailbox)
-- 存储用户的邮箱，如 INBOX, Sent, Drafts 等
-- ----------------------------
drop table if exists mw_mailbox;
CREATE TABLE mw_mailbox (
  "id" BIGINT NOT NULL PRIMARY KEY,
  "user_id" BIGINT NOT NULL,               -- 所属用户ID
  "name" VARCHAR(255) NOT NULL,            -- 邮箱名称 (e.g., INBOX, Sent)
  "uid_validity" BIGINT NOT NULL,          -- IMAP UIDVALIDITY 值，用于客户端同步
  "uid_next" BIGINT NOT NULL DEFAULT 1,    -- IMAP UIDNEXT 值，下一个可用的UID
  highest_modseq BIGINT NOT NULL DEFAULT 0,
  "remark" VARCHAR(256),
  "creator" VARCHAR(64) DEFAULT '',
  "create_time" TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
  "updater" VARCHAR(64) DEFAULT '',
  "update_time" TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
  "deleted" SMALLINT DEFAULT 0,
  "tenant_id" BIGINT NOT NULL DEFAULT 0,
  UNIQUE (user_id, name)
);

COMMENT ON TABLE mw_mailbox IS '邮箱目录表';
COMMENT ON COLUMN mw_mailbox.id IS '主键ID';
COMMENT ON COLUMN mw_mailbox.user_id IS '所属用户ID (关联 mw_user.id)';
COMMENT ON COLUMN mw_mailbox.name IS '邮箱名称 (如 INBOX, Sent, Drafts)';
COMMENT ON COLUMN mw_mailbox.uid_validity IS 'IMAP UIDVALIDITY，创建时生成，用于客户端同步';
COMMENT ON COLUMN mw_mailbox.uid_next IS '下一个可用的邮件UID';


-- ----------------------------
-- 3. 邮件消息表 (mw_mail_message)
-- 存储唯一的邮件消息体及其可检索的元数据，支持去重
-- ----------------------------
drop table if exists mw_mail_message;
CREATE TABLE mw_mail_message (
  "id" BIGINT NOT NULL PRIMARY KEY,
  "content_hash" VARCHAR(64) NOT NULL UNIQUE, -- 邮件原始内容的SHA-256哈希值，用于去重
  "message_id_header" VARCHAR(512),          -- 邮件头中的 Message-ID
  "subject" VARCHAR(1024),                   -- 邮件主题
  "from_address" TEXT,                       -- 发件人地址
  "to_address" TEXT,                         -- 收件人地址 (可以是多个，用逗号分隔)
  "cc_address" TEXT,                         -- 抄送地址
  "sent_date" TIMESTAMP WITH TIME ZONE,      -- 邮件头中的原始发送日期
  "has_attachment" BOOLEAN DEFAULT FALSE,    -- 是否包含附件
  "size_in_bytes" INT NOT NULL,              -- 邮件大小 (字节)
  "raw_content" TEXT NOT NULL,               -- 邮件的完整原始内容 (MIME格式)
  "search_vector" TSVECTOR,                  -- 用于全文检索的 tsvector
  "remark" VARCHAR(256),
  "creator" VARCHAR(64) DEFAULT '',
  "create_time" TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
  "updater" VARCHAR(64) DEFAULT '',
  "update_time" TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
  "deleted" SMALLINT DEFAULT 0,
  "tenant_id" BIGINT NOT NULL DEFAULT 0
);

COMMENT ON TABLE mw_mail_message IS '邮件消息表 (支持去重和检索)';
COMMENT ON COLUMN mw_mail_message.id IS '主键ID';
COMMENT ON COLUMN mw_mail_message.content_hash IS '原始内容的SHA-256哈希，用于去重';
COMMENT ON COLUMN mw_mail_message.message_id_header IS '邮件头中的Message-ID';
COMMENT ON COLUMN mw_mail_message.subject IS '邮件主题';
COMMENT ON COLUMN mw_mail_message.from_address IS '发件人';
COMMENT ON COLUMN mw_mail_message.to_address IS '收件人';
COMMENT ON COLUMN mw_mail_message.cc_address IS '抄送';
COMMENT ON COLUMN mw_mail_message.sent_date IS '邮件头中的原始发送日期';
COMMENT ON COLUMN mw_mail_message.has_attachment IS '是否包含附件';
COMMENT ON COLUMN mw_mail_message.size_in_bytes IS '邮件大小 (字节)';
COMMENT ON COLUMN mw_mail_message.raw_content IS '邮件的完整原始内容 (MIME格式)';
COMMENT ON COLUMN mw_mail_message.search_vector IS '全文检索向量';

-- 为常用检索字段创建索引
CREATE INDEX idx_message_subject ON mw_mail_message USING GIN (to_tsvector('simple', subject)); -- 主题搜索
CREATE INDEX idx_message_from ON mw_mail_message(from_address);
-- 为全文检索向量创建 GIN 索引，这是最高效的方式
CREATE INDEX idx_message_search_vector ON mw_mail_message USING GIN (search_vector);

-- ----------------------------
-- 4. 邮件元数据/实例表 (mw_mail) - [修订]
-- 将一封唯一的邮件消息(mw_mail_message)与一个用户的邮箱(mw_mailbox)关联起来
-- ----------------------------
drop table if exists mw_mail;
CREATE TABLE mw_mail (
  "id" BIGINT NOT NULL PRIMARY KEY,
  "user_id" BIGINT NOT NULL,               -- 所属用户ID
  "mailbox_id" BIGINT NOT NULL,            -- 所属邮箱ID
  "message_id" BIGINT NOT NULL,            -- 邮件消息ID (关联 mw_mail_message.id)
  "uid" BIGINT NOT NULL,                   -- IMAP UID，在单个邮箱内唯一
  modseq BIGINT NOT NULL DEFAULT 0,
  "internal_date" TIMESTAMP WITH TIME ZONE NOT NULL, -- 服务器内部接收日期
  "remark" VARCHAR(256),
  "creator" VARCHAR(64) DEFAULT '',
  "create_time" TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
  "updater" VARCHAR(64) DEFAULT '',
  "update_time" TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
  "deleted" SMALLINT DEFAULT 0,
  "tenant_id" BIGINT NOT NULL DEFAULT 0,
  -- IMAP核心约束：UID在同一个邮箱内必须是唯一的
  UNIQUE (mailbox_id, uid)
);

COMMENT ON TABLE mw_mail IS '邮件实例表 (关联用户、邮箱和消息)';
COMMENT ON COLUMN mw_mail.id IS '主键ID (邮件实例ID)';
COMMENT ON COLUMN mw_mail.user_id IS '所属用户ID';
COMMENT ON COLUMN mw_mail.mailbox_id IS '所属邮箱ID';
COMMENT ON COLUMN mw_mail.message_id IS '邮件消息ID (关联 mw_mail_message.id)';
COMMENT ON COLUMN mw_mail.uid IS 'IMAP UID，在 mailbox_id 内唯一';
COMMENT on COLUMN mw_mail.internal_date IS '服务器内部接收/投递日期';

-- 为常用查询创建索引
CREATE INDEX idx_mail_user_id ON mw_mail(user_id);
CREATE INDEX idx_mail_mailbox_id ON mw_mail(mailbox_id);
CREATE INDEX idx_mail_message_id ON mw_mail(message_id);
CREATE INDEX idx_mail_mailbox_id_modseq ON mw_mail(mailbox_id, modseq);

-- ----------------------------
-- 5. 邮件标志表 (mw_mail_flag)
-- 存储邮件的IMAP标志 (多对多关系)
-- ----------------------------
drop table if exists mw_mail_flag;
CREATE TABLE mw_mail_flag (
  "id" bigint primary key,
  "mail_id" BIGINT NOT NULL,
  "flag" VARCHAR(64) NOT NULL,
  UNIQUE(mail_id, flag)
);

COMMENT ON TABLE mw_mail_flag IS '邮件标志关联表';
COMMENT ON COLUMN mw_mail_flag.mail_id IS '邮件ID (关联 mw_mail.id)';
COMMENT ON COLUMN mw_mail_flag.flag IS 'IMAP标志 (e.g., \Seen, \Answered, \Flagged)';

-- 为按标志查询创建索引
CREATE INDEX idx_mail_flag_flag ON mw_mail_flag(flag);