--# mail.baseColumns
-- 邮件实例和消息的核心字段
m.id,
m.uid,
m.internal_date,
msg.raw_content,
msg.size_in_bytes

--# mailbox.user.findByUsername
-- 根据用户名查找未删除的用户ID
SELECT * FROM mw_user WHERE username = ? AND deleted = 0;

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
-- 根据 UID 获取单封邮件，包含聚合后的标志
SELECT
  --#include(mail.baseColumns),
  COALESCE(
    (SELECT ARRAY_AGG(f.flag) FROM mw_mail_flag f WHERE f.mail_id = m.id),
    '{}'
  ) as flags
FROM
  mw_mail m
JOIN
  mw_mail_message msg ON m.message_id = msg.id
WHERE
  m.mailbox_id = ? AND m.uid = ? AND m.deleted = 0

--# mailbox.flags.addBatch
INSERT INTO mw_mail_flag (id,mail_id, flag) VALUES (?,?,?) ON CONFLICT (mail_id, flag) DO NOTHING;

--# mailbox.flags.removeBatch
-- 批量删除标志，%s 会被Java代码动态替换为占位符 ?, ?, ...
DELETE FROM mw_mail_flag WHERE mail_id = ? AND flag IN (%s);

--# mailbox.flags.clearRecent
-- 清除指定邮箱的所有 \Recent 标志
DELETE FROM mw_mail_flag WHERE flag = '\Recent' AND mail_id IN (SELECT id FROM mw_mail WHERE mailbox_id = ?);

--# mailbox.findEmails.baseQuery
SELECT
  m.id, m.uid, m.internal_date,
  msg.raw_content, msg.size_in_bytes,
  ARRAY_AGG(mf.flag) FILTER (WHERE mf.flag IS NOT NULL) as flags
FROM mw_mail m
JOIN mw_mail_message msg ON m.message_id = msg.id
LEFT JOIN mw_mail_flag mf ON m.id = mf.mail_id
WHERE m.mailbox_id = ?
  AND (%s)
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
-- 获取一个邮箱中所有未删除的邮件，包含聚合后的标志
SELECT
  --#include(mail.baseColumns),
  -- 这里也需要聚合标志
  COALESCE(
    (SELECT ARRAY_AGG(f.flag) FROM mw_mail_flag f WHERE f.mail_id = m.id),
    '{}'
  ) as flags
FROM
  mw_mail m
JOIN
  mw_mail_message msg ON m.message_id = msg.id
WHERE
  m.mailbox_id = ? AND m.deleted = 0
  AND NOT EXISTS (
    SELECT 1 FROM mw_mail_flag f WHERE f.mail_id = m.id AND f.flag = '\\Deleted'
  )
ORDER BY
  m.uid ASC

--# mailbox.findEmails.byUidSet
-- 同样可以包含
--#include(mailbox.baseRankedEmailsCTE)
SELECT
  r.id, r.uid, r.internal_date, r.seq_num,
  msg.raw_content, msg.size_in_bytes,
  ARRAY_AGG(mf.flag) FILTER (WHERE mf.flag IS NOT NULL) as flags
FROM ranked_emails r

--# mailbox.findEmails.BySeqSet
SELECT *
FROM (
    SELECT
        m.id,
        m.uid,
        m.internal_date,
        msg.raw_content,
        msg.size_in_bytes,
        ROW_NUMBER() OVER (ORDER BY m.uid ASC) AS seq_num,
        COALESCE(
            (
                SELECT ARRAY_AGG(f.flag)
                FROM mw_mail_flag AS f
                WHERE f.mail_id = m.id
            ),
            '{}'
        ) AS flags
    FROM mw_mail AS m
    JOIN mw_mail_message AS msg
        ON m.message_id = msg.id
    WHERE m.mailbox_id = ?
      AND m.deleted = 0
) AS subquery
WHERE %s;

--# mailbox.getExpungeSeqNums
-- 获取指定用户、指定邮箱中所有标记为 \Deleted 的邮件原始序号（seq_num）
WITH ranked_emails AS (
  SELECT
    m.id,
    ROW_NUMBER() OVER (ORDER BY m.uid ASC) AS seq_num
  FROM mw_mail m
  JOIN mw_mailbox b ON m.mailbox_id = b.id
  JOIN mw_user u    ON b.user_id    = u.id
  WHERE u.username = ?
    AND b.name     = ?
    AND m.deleted  = 0
)
SELECT r.seq_num
FROM ranked_emails r
WHERE EXISTS (
  SELECT 1
  FROM mw_mail_flag f
  WHERE f.mail_id = r.id
    AND f.flag    = '\\Deleted'
);

--# mailbox.expunge
-- 逻辑删除所有已标记 \Deleted 的邮件实例
UPDATE mw_mail m
SET deleted = 1
FROM mw_mailbox b, mw_user u
WHERE m.mailbox_id = b.id
  AND b.user_id    = u.id
  AND u.username   = ?
  AND b.name       = ?
  AND EXISTS (
    SELECT 1
    FROM mw_mail_flag f
    WHERE f.mail_id = m.id
      AND f.flag    = '\\Deleted'
  );
