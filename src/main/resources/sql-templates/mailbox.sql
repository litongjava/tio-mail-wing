--# mailbox.user.findByUsername
-- 根据用户名查找未删除的用户ID
SELECT id FROM mw_user WHERE username = ? AND deleted = 0;

--# mailbox.getByName
-- 根据用户ID和邮箱名查找邮箱信息
SELECT id, uid_validity, uid_next FROM mw_mailbox WHERE user_id = ? AND name = ? AND deleted = 0;

--# mailbox.message.findByHash
-- 根据内容哈希查找已存在的邮件消息
SELECT id FROM mw_mail_message WHERE content_hash = ?;

--# mailbox.updateUidNextAndGet
-- 原子性地将uid_next加1，并返回更新前的uid_next值作为新邮件的UID
UPDATE mw_mailbox SET uid_next = uid_next + 1 WHERE id = ? RETURNING uid_next - 1 AS next_uid;

--# mailbox.getMessageByUid
-- [核心查询] 根据UID获取单封邮件的完整信息
SELECT
  m.id, m.uid, m.internal_date,
  msg.raw_content, msg.size_in_bytes,
  ARRAY_AGG(mf.flag) FILTER (WHERE mf.flag IS NOT NULL) as flags
FROM mw_mail m
JOIN mw_mail_message msg ON m.message_id = msg.id
LEFT JOIN mw_mail_flag mf ON m.id = mf.mail_id
WHERE m.mailbox_id = ? AND m.uid = ?
GROUP BY m.id, msg.id;

--# mailbox.flags.addBatch
-- 批量添加标志，利用 ON CONFLICT 忽略已存在的冲突，%s 会被Java代码动态替换为 VALUES (?, ?), ...
INSERT INTO mw_mail_flag (mail_id, flag) VALUES %s ON CONFLICT (mail_id, flag) DO NOTHING;

--# mailbox.flags.removeBatch
-- 批量删除标志，%s 会被Java代码动态替换为占位符 ?, ?, ...
DELETE FROM mw_mail_flag WHERE mail_id = ? AND flag IN (%s);

--# mailbox.flags.clearRecent
-- 清除指定邮箱的所有 \Recent 标志
DELETE FROM mw_mail_flag WHERE flag = '\Recent' AND mail_id IN (SELECT id FROM mw_mail WHERE mailbox_id = ?);

--# mailbox.findEmails.baseQuery
-- 用于 findEmailsBy...Set 的基础查询结构，%s 将被替换为具体的UID或SEQ过滤条件
SELECT
  m.id, m.uid, m.internal_date,
  msg.raw_content, msg.size_in_bytes,
  ARRAY_AGG(mf.flag) FILTER (WHERE mf.flag IS NOT NULL) as flags
FROM mw_mail m
JOIN mw_mail_message msg ON m.message_id = msg.id
LEFT JOIN mw_mail_flag mf ON m.id = mf.mail_id
WHERE m.mailbox_id = ?
  AND (%s) -- 动态条件占位符
  AND NOT EXISTS (
    SELECT 1 FROM mw_mail_flag del_mf WHERE del_mf.mail_id = m.id AND del_mf.flag = '\Deleted'
  )
GROUP BY m.id, msg.id
ORDER BY m.uid ASC;

--# mailbox.findEmails.bySeqSet
-- 使用窗口函数 ROW_NUMBER() 来按序号过滤邮件
-- %s 将被替换为具体的序号过滤条件 (e.g., seq_num IN (1,5) OR seq_num BETWEEN 10 AND 20)
WITH ranked_emails AS (
  SELECT
    m.id,
    m.uid,
    m.internal_date,
    m.message_id,
    -- 在未删除的邮件中计算序号
    ROW_NUMBER() OVER (ORDER BY m.uid ASC) as seq_num
  FROM mw_mail m
  WHERE m.mailbox_id = ?
    AND NOT EXISTS (
      SELECT 1 FROM mw_mail_flag del_mf WHERE del_mf.mail_id = m.id AND del_mf.flag = '\Deleted'
    )
)
SELECT
  r.id, r.uid, r.internal_date,
  msg.raw_content, msg.size_in_bytes,
  ARRAY_AGG(mf.flag) FILTER (WHERE mf.flag IS NOT NULL) as flags
FROM ranked_emails r
JOIN mw_mail_message msg ON r.message_id = msg.id
LEFT JOIN mw_mail_flag mf ON r.id = mf.mail_id
WHERE %s -- 动态序号条件占位符
GROUP BY r.id, r.uid, r.internal_date, msg.id
ORDER BY r.uid ASC;

--# mailbox.getMaxUid
-- 获取邮箱中最大的UID，用于处理 * 通配符
SELECT MAX(uid) FROM mw_mail WHERE mailbox_id = ?;

--# mailbox.getStat
-- 直接在数据库中计算邮件数量和总大小
SELECT
  COUNT(*) as message_count,
  COALESCE(SUM(msg.size_in_bytes), 0) as total_size
FROM mw_mail m
JOIN mw_mail_message msg ON m.message_id = msg.id
WHERE m.mailbox_id = ?
  AND NOT EXISTS (
    SELECT 1 FROM mw_mail_flag del_mf WHERE del_mf.mail_id = m.id AND del_mf.flag = '\Deleted'
  );
  
--# mailbox.baseRankedEmailsCTE
-- 这个SQL块现在既可以被独立获取，也可以被其他块包含
-- 它定义了一个公共表表达式（CTE）
WITH ranked_emails AS (
  SELECT
    m.id, m.uid, m.internal_date, m.message_id,
    ROW_NUMBER() OVER (ORDER BY m.uid ASC) as seq_num
  FROM mw_mail m
  WHERE m.mailbox_id = ?
    AND NOT EXISTS (
      SELECT 1 FROM mw_mail_flag del_mf WHERE del_mf.mail_id = m.id AND del_mf.flag = '\Deleted'
    )
)

--# mailbox.getActiveMessages
-- 使用 --include 来包含上面定义的CTE
--#include(mailbox.baseRankedEmailsCTE)
SELECT
  r.id, r.uid, r.internal_date, r.seq_num,
  msg.raw_content, msg.size_in_bytes,
  ARRAY_AGG(mf.flag) FILTER (WHERE mf.flag IS NOT NULL) as flags
FROM ranked_emails r
JOIN mw_mail_message msg ON r.message_id = msg.id
LEFT JOIN mw_mail_flag mf ON r.id = mf.mail_id
GROUP BY r.id, r.uid, r.internal_date, r.seq_num, msg.id
ORDER BY r.uid ASC;

--# mailbox.findEmails.byUidSet
-- 同样可以包含
--#include(mailbox.baseRankedEmailsCTE)
SELECT
  r.id, r.uid, r.internal_date, r.seq_num,
  msg.raw_content, msg.size_in_bytes,
  ARRAY_AGG(mf.flag) FILTER (WHERE mf.flag IS NOT NULL) as flags
FROM ranked_emails r