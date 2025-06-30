WITH new_users AS (
  -- 步骤1: 插入新用户
  INSERT INTO mw_user (id, username, password_hash, creator, updater, tenant_id) VALUES
  (1001, 'user1@litong.xyz', '600000$4JRI3BfykBwVVHwSXuUYmA==$qefBA/+M2pcr9o6p4ycojdHNnLAhTs9+7cmSjp664ww=', 'system', 'system', 1),
  (1002, 'user2@litong.xyz', '600000$4JRI3BfykBwVVHwSXuUYmA==$qefBA/+M2pcr9o6p4ycojdHNnLAhTs9+7cmSjp664ww=', 'system', 'system', 1),
  (1003, 'error@litong.xyz', '600000$4JRI3BfykBwVVHwSXuUYmA==$qefBA/+M2pcr9o6p4ycojdHNnLAhTs9+7cmSjp664ww=', 'system', 'system', 1)
  RETURNING id, username
),
mailbox_names (name) AS (
  -- 步骤2: 定义需要创建的邮箱名称列表
  VALUES ('inbox'), ('trash')
),
mailboxes_to_create AS (
  -- 步骤3: 生成用户和邮箱名的所有组合，并为每个组合生成唯一的邮箱ID
  SELECT
    -- 动态生成邮箱ID (示例策略)
    (new_users.id * 100 + ROW_NUMBER() OVER (PARTITION BY new_users.id ORDER BY mailbox_names.name)) AS mailbox_id,
    new_users.id AS user_id,
    mailbox_names.name AS mailbox_name
  FROM new_users
  CROSS JOIN mailbox_names
)
-- 步骤4: 插入邮箱数据，并将邮箱自己的ID用作UIDVALIDITY
INSERT INTO mw_mailbox (id, user_id, name, uid_validity, creator, updater, tenant_id)
SELECT
  mailbox_id,
  user_id,
  mailbox_name,
  mailbox_id,
  'system',
  'system',
  1
FROM mailboxes_to_create;


-- =================================================================
-- 验证插入结果 (可选)
-- =================================================================
SELECT
  u.id AS user_id,
  u.username,
  m.id AS mailbox_id,
  m.name AS mailbox_name,
  m.uid_validity,
  m.uid_next
FROM mw_user u
JOIN mw_mailbox m ON u.id = m.user_id
ORDER BY u.id, m.name;