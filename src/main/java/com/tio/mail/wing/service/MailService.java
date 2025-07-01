package com.tio.mail.wing.service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import com.litongjava.db.activerecord.Db;
import com.litongjava.db.activerecord.Row;
import com.litongjava.jfinal.aop.Aop;
import com.litongjava.template.SqlTemplates;
import com.litongjava.tio.utils.hutool.StrUtil;
import com.litongjava.tio.utils.snowflake.SnowflakeIdUtils;
import com.tio.mail.wing.consts.MailBoxName;
import com.tio.mail.wing.model.Email;
import com.tio.mail.wing.result.WhereClauseResult;

import lombok.extern.slf4j.Slf4j;

@Slf4j
public class MailService {

  private MwUserService mwUserService = Aop.get(MwUserService.class);
  private MailBoxService mailBoxService = Aop.get(MailBoxService.class);
  private MailSaveService mailSaveService = Aop.get(MailSaveService.class);
  private MailFlagService mailFlagService = Aop.get(MailFlagService.class);

  /**
   * 单独的 * → 匹配所有序号
     n:* → 序号 ≥ n
     *:n → 序号 ≤ n
     n:m → 正常范围
     *:* → 匹配所有
   * @param messageSet
   * @param mailboxId
   * @return
   */
  private WhereClauseResult buildSeqWhereClause(String messageSet, long mailboxId) {
    StringBuilder clause = new StringBuilder();
    List<Object> params = new ArrayList<>();

    String[] parts = messageSet.split(",");
    for (String rawPart : parts) {
      String part = rawPart.trim();
      if (clause.length() > 0) {
        clause.append(" OR ");
      }

      if (part.contains(":")) {
        String[] range = part.split(":", 2);
        String startStr = range[0].trim();
        String endStr = range[1].trim();
        boolean startIsStar = "*".equals(startStr);
        boolean endIsStar = "*".equals(endStr);

        if (startIsStar && endIsStar) {
          // *:* → 全部
          clause.append("TRUE");
        } else if (startIsStar) {
          // *:n → seq_num <= n
          int end = Integer.parseInt(endStr);
          clause.append("seq_num <= ?");
          params.add(end);
        } else if (endIsStar) {
          // n:* → seq_num >= n
          int start = Integer.parseInt(startStr);
          clause.append("seq_num >= ?");
          params.add(start);
        } else {
          // n:m → BETWEEN
          int start = Integer.parseInt(startStr);
          int end = Integer.parseInt(endStr);
          clause.append("seq_num BETWEEN ? AND ?");
          params.add(Math.min(start, end));
          params.add(Math.max(start, end));
        }
      } else {
        if ("*".equals(part)) {
          // 单独的 * → 全部
          clause.append("TRUE");
        } else {
          // 单个序号
          clause.append("seq_num = ?");
          params.add(Integer.parseInt(part));
        }
      }
    }

    return new WhereClauseResult(clause.toString(), params);
  }

  /**
   * 将 IMAP UID 集合字符串（如 "1,2,5:7"）解析为具体的 UID 列表。
   * 支持单个 UID、逗号分隔的多段、数字范围，以及 "*" 通配符（代表最大 UID）。
   *
   * @param uidSet      IMAP UID set，比如 "1,2,5:7,*"
   * @param mailboxId   用于查询最大 UID（处理 '*' 通配符）
   * @return            按顺序展开的 UID 列表
   */
  public List<Long> parseUidSet(String uidSet, long mailboxId) {
    // 拿到当前 mailbox 的最大 UID，用于 '*' 扩展
    long maxUid = getMaxUid(mailboxId);
    List<Long> uids = new ArrayList<>();
    String[] parts = uidSet.split(",");
    for (String raw : parts) {
      String part = raw.trim();
      if (part.contains(":")) {
        String[] range = part.split(":", 2);
        long start = "*".equals(range[0]) ? maxUid : Long.parseLong(range[0]);
        long end = "*".equals(range[1]) ? maxUid : Long.parseLong(range[1]);
        long min = Math.min(start, end), max = Math.max(start, end);
        for (long uid = min; uid <= max; uid++) {
          uids.add(uid);
        }
      } else if ("*".equals(part)) {
        // 单独的 '*' → 取最大那条
        uids.add(maxUid);
      } else {
        // 单个数字
        uids.add(Long.parseLong(part));
      }
    }
    return uids;
  }

  /**
   * [兼容POP3] 获取用户收件箱(INBOX)中所有未删除的邮件。
   * 注意：此方法现在性能更高，但如果邮箱巨大，仍需考虑分页。
   */
  public List<Email> getActiveMessagesByUsername(String username) {
    Long userId = mwUserService.getUserIdByUsername(username);
    return getActiveMessages(userId, MailBoxName.INBOX);
  }

  public List<Email> getActiveMessagesByUserId(Long userId) {
    return getActiveMessages(userId, MailBoxName.INBOX);
  }

  /**
   * [兼容POP3] 获取邮箱状态（邮件数，总大小），针对INBOX。
   * 优化：直接在数据库中计算，避免加载所有邮件到内存。
   */
  public int[] getStat(String username) {

    Row user = mwUserService.getUserByUsername(username);
    if (user == null) {
      return new int[] { 0, 0 };
    }

    Row mailbox = mailBoxService.getMailboxByName(user.getLong("id"), MailBoxName.INBOX);
    if (mailbox == null)
      return new int[] { 0, 0 };

    String sql = SqlTemplates.get("mailbox.getStat");
    Row statRow = Db.findFirst(sql, mailbox.getLong("id"));

    if (statRow == null) {
      return new int[] { 0, 0 };
    }
    return new int[] { statRow.getLong("message_count").intValue(), statRow.getBigDecimal("total_size").intValue() };
  }

  /**
   * [兼容POP3] 获取指定邮件内容，针对INBOX。
   * 优化：使用 findEmailsBySeqSet 获取单封邮件，避免加载整个列表。
   */
  public String getMessageContent(String username, int msgNumber) {
    List<Email> emails = findEmailsBySeqSet(username, MailBoxName.INBOX, String.valueOf(msgNumber));
    if (emails != null && !emails.isEmpty()) {
      return emails.get(0).getRawContent();
    }
    return null;
  }

  /**
   * [兼容POP3] 获取邮件大小列表，用于 LIST 命令，针对INBOX。
   */
  public List<Integer> listMessages(Long userId) {
    return getActiveMessages(userId, MailBoxName.INBOX).stream().map(Email::getSize).collect(Collectors.toList());
  }

  /**
   * [兼容POP3] 获取邮件的唯一ID列表，用于 UIDL 命令，针对INBOX。
   */
  public List<Long> listUids(Long userId) {
    Long mailboxId = mailBoxService.getMailboxIdByName(userId, MailBoxName.INBOX);
    return this.listUids(userId, mailboxId);
  }

  /**
   * [IMAP核心] 获取用户【指定邮箱】中所有未被标记为删除的邮件。
   * 优化：使用单个SQL查询，将邮件、内容和标志一次性获取。
   */
  public List<Email> getActiveMessages(Long userId, String mailboxName) {
    Row mailbox = mailBoxService.getMailboxByName(userId, mailboxName);
    if (mailbox == null)
      return Collections.emptyList();

    String sql = SqlTemplates.get("mailbox.getActiveMessages");
    List<Row> mailRows = Db.find(sql, mailbox.getLong("id"));

    return mailRows.stream().map(mailFlagService::rowToEmailWithAggregatedFlags).collect(Collectors.toList());
  }

  public List<Email> getActiveMessages(Long mailboxId) {
    String sql = SqlTemplates.get("mailbox.getActiveMessages");
    List<Row> mailRows = Db.find(sql, mailboxId);
    return mailRows.stream().map(mailFlagService::rowToEmailWithAggregatedFlags).collect(Collectors.toList());
  }

  /**
   * [IMAP核心] 获取邮箱的元数据。
   */
  public Row getMailboxMetadata(Long userId, String mailboxName) {
    Row mailbox = mailBoxService.getMailboxByName(userId, mailboxName);
    if (mailbox == null) {
      return null;
    }
    return mailbox;
  }

  /**
   * [IMAP核心] 根据序号获取邮件。
   * 优化：使用 findEmailsBySeqSet 避免加载全量数据。
   */
  public Email getMessageByNumber(String username, String mailboxName, int msgNumber) {
    List<Email> emails = findEmailsBySeqSet(username, mailboxName, String.valueOf(msgNumber));
    return (emails != null && !emails.isEmpty()) ? emails.get(0) : null;
  }

  /**
   * [IMAP核心] 根据 UID 获取邮件。
   * 优化：使用单个SQL查询，直接获取目标邮件。
   */
  public Email getMessageByUid(String username, String mailboxName, long uid) {
    Row user = mwUserService.getUserByUsername(username);
    if (user == null)
      return null;
    Row mailbox = mailBoxService.getMailboxByName(user.getLong("id"), mailboxName);
    if (mailbox == null)
      return null;

    String sql = SqlTemplates.get("mailbox.getMessageByUid");
    Row row = Db.findFirst(sql, mailbox.getLong("id"), uid);
    if (row == null)
      return null;

    return mailFlagService.rowToEmailWithAggregatedFlags(row);
  }

  /**
   * [IMAP核心] 修改邮件标志。
   * 优化：使用批量、原子的SQL操作。
   */
  public void storeFlags(Long mailId, Set<String> newFlags, boolean add) {

    if (add) {
      String sql = SqlTemplates.get("mailbox.flags.addBatch");
      for (String flag : newFlags) {
        Db.updateBySql(sql, SnowflakeIdUtils.id(), mailId, flag);
      }

    } else {
      String flagPlaceholders = String.join(",", Collections.nCopies(newFlags.size(), "?"));
      String sql = String.format(SqlTemplates.get("mailbox.flags.removeBatch"), flagPlaceholders);

      List<Object> params = new ArrayList<>();
      params.add(mailId);
      params.addAll(newFlags);
      Db.updateBySql(sql, params.toArray());
    }
  }

  /**
   * [IMAP核心] 清除指定用户邮箱的所有 \Recent 标志。
   */
  public void clearRecentFlags(String username, String mailboxName) {
    Row user = mwUserService.getUserByUsername(username);
    if (user == null) {
      return;
    }

    Long userId = user.getLong("id");
    clearRecentFlags(userId, mailboxName);
  }

  public void clearRecentFlags(Long userId, String mailboxName) {
    Row mailbox = mailBoxService.getMailboxByName(userId, mailboxName);
    if (mailbox == null) {
      return;
    }
    Long mailBoxId = mailbox.getLong("id");
    clearRecentFlags(mailBoxId);
  }

  public void clearRecentFlags(Long mailBoxId) {
    String sql = SqlTemplates.get("mailbox.flags.clearRecent");
    Db.updateBySql(sql, mailBoxId);
  }

  /**
  * [IMAP核心] 根据UID集合获取邮件列表。
  * 优化：将过滤逻辑下推到数据库，避免在内存中操作大列表。
  */
  public List<Email> findEmailsByUidSet(String username, String mailboxName, String messageSet) {
    Row user = mwUserService.getUserByUsername(username);
    if (user == null) {
      return Collections.emptyList();
    }

    Row mailbox = mailBoxService.getMailboxByName(user.getLong("id"), mailboxName);
    if (mailbox == null) {
      return Collections.emptyList();
    }

    long mailboxId = mailbox.getLong("id");

    return findEmailsByUidSet(mailboxId, messageSet);
  }

  public List<Email> findEmailsByUidSet(long mailboxId, String messageSet) {
    // —— 1. 查库
    WhereClauseResult where = buildUidWhereClause(messageSet, mailboxId);
    if (where.getClause().isEmpty()) {
      return Collections.emptyList();
    }
    String sql = String.format(SqlTemplates.get("mailbox.findEmails.baseQuery"), where.getClause());
    List<Object> params = new ArrayList<>();
    params.add(mailboxId);
    params.addAll(where.getParams());
    List<Email> emails = new ArrayList<>();
    for (Row r : Db.find(sql, params.toArray())) {
      emails.add(mailFlagService.rowToEmailWithAggregatedFlags(r));
    }

    // —— 2. 按客户端顺序展开 messageSet ——  
    // 支持 "5"、"1:4"、"4:1"、"*"、"10:*"、"*:20" 等格式
    List<Long> requestedOrder = new ArrayList<>();
    Long maxUid = null;
    for (String part0 : messageSet.split(",")) {
      String part = part0.trim();
      // 2.1 range
      if (part.contains(":")) {
        String[] ss = part.split(":", 2);
        // 懒加载 maxUid
        if (maxUid == null) {
          maxUid = getMaxUid(mailboxId);
        }
        long start = ss[0].equals("*") ? maxUid : Long.parseLong(ss[0]);
        long end = ss[1].equals("*") ? maxUid : Long.parseLong(ss[1]);
        if (start <= end) {
          for (long u = start; u <= end; u++) {
            requestedOrder.add(u);
          }
        } else {
          for (long u = start; u >= end; u--) {
            requestedOrder.add(u);
          }
        }
      }
      // 2.2 wildcard "*"
      else if (part.equals("*")) {
        if (maxUid == null) {
          maxUid = getMaxUid(mailboxId);
        }
        requestedOrder.add(maxUid);
      }
      // 2.3 单个 UID
      else {
        requestedOrder.add(Long.parseLong(part));
      }
    }

    // —— 3. 再次重排查询到的结果 ——  
    Map<Long, Email> byUid = new HashMap<>(emails.size());
    for (Email e : emails) {
      byUid.put(e.getUid(), e);
    }
    List<Email> ordered = new ArrayList<>(requestedOrder.size());
    for (Long uid : requestedOrder) {
      Email e = byUid.get(uid);
      if (e != null) {
        ordered.add(e);
      }
    }
    return ordered;
  }

  /**
  * [IMAP核心] 根据序号集合获取邮件列表。
  * 优化：使用窗口函数在数据库中过滤，避免内存操作。
  */
  public List<Email> findEmailsBySeqSet(String username, String mailboxName, String messageSet) {
    Row user = mwUserService.getUserByUsername(username);
    if (user == null) {
      return Collections.emptyList();
    }

    Row mailbox = mailBoxService.getMailboxByName(user.getLong("id"), mailboxName);
    if (mailbox == null) {
      return Collections.emptyList();
    }

    long mailboxId = mailbox.getLong("id");

    return findEmailsBySeqSet(mailboxId, messageSet);
  }

  public List<Email> findEmailsBySeqSet(long mailboxId, String messageSet) {
    // 解析 messageSet 并构建 SQL 条件
    WhereClauseResult whereClause = buildSeqWhereClause(messageSet, mailboxId);
    if (whereClause.getClause().isEmpty()) {
      return Collections.emptyList();
    }

    // 使用 SqlTemplates 动态解析 #include
    String resolvedBaseQuery = SqlTemplates.get("mailbox.findEmails.BySeqSet");
    String finalSql = String.format(resolvedBaseQuery, whereClause.getClause());

    List<Object> params = new ArrayList<>();
    params.add(mailboxId); // 这是子查询中的 '?'
    params.addAll(whereClause.getParams()); // 这是外部WHERE条件的参数

    List<Row> mailRows = Db.find(finalSql, params.toArray());
    return mailRows.stream().map(mailFlagService::rowToEmailWithAggregatedFlags).collect(Collectors.toList());
  }

  private WhereClauseResult buildUidWhereClause(String messageSet, long mailboxId) {
    StringBuilder clause = new StringBuilder();
    List<Object> params = new ArrayList<>();
    Long maxUid = null; // Lazy-loaded max UID

    String[] parts = messageSet.split(",");
    for (String part : parts) {
      part = part.trim();
      if (clause.length() > 0)
        clause.append(" OR ");

      if (part.contains(":")) {
        String[] range = part.split(":", 2);
        if ("*".equals(range[0]) || "*".equals(range[1])) {
          if (maxUid == null)
            maxUid = getMaxUid(mailboxId);
        }
        long start = "*".equals(range[0]) ? maxUid : Long.parseLong(range[0]);
        long end = "*".equals(range[1]) ? maxUid : Long.parseLong(range[1]);
        clause.append("m.uid BETWEEN ? AND ?");
        params.add(Math.min(start, end));
        params.add(Math.max(start, end));
      } else {
        if ("*".equals(part)) {
          if (maxUid == null)
            maxUid = getMaxUid(mailboxId);
          clause.append("m.uid = ?");
          params.add(maxUid);
        } else {
          clause.append("m.uid = ?");
          params.add(Long.parseLong(part));
        }
      }
    }
    return new WhereClauseResult(clause.toString(), params);
  }

  private Long getMaxUid(long mailboxId) {
    Row row = Db.findFirst(SqlTemplates.get("mailbox.getMaxUid"), mailboxId);
    return (row != null && row.getLong("max") != null) ? row.getLong("max") : 0L;
  }

  /**
   * 获取待 EXPUNGE 的邮件序号列表
   */
  public List<Integer> getExpungeSeqNums(String username, String mailboxName) {
    String sql = SqlTemplates.get("mailbox.getExpungeSeqNums");
    List<Row> rows = Db.find(sql, username, mailboxName);
    return rows.stream().map(r -> r.getInt("seq_num")).collect(Collectors.toList());
  }

  /**
   * 逻辑删除所有已标记 \Deleted 的邮件实例
   */
  public void expunge(String username, String mailboxName) {
    String sql = SqlTemplates.get("mailbox.expunge");
    Db.updateBySql(sql, username, mailboxName);
  }

  /**
   * 列出指定用户的所有邮箱目录名称
   */
  public List<String> listMailboxes(String username) {
    Row user = mwUserService.getUserByUsername(username);
    if (user == null) {
      return new ArrayList<>(0);
    }
    Long userId = user.getLong("id");
    String sql = "SELECT name FROM mw_mailbox WHERE user_id = ? AND deleted = 0";
    List<Row> rows = Db.find(sql, userId);
    return rows.stream().map(r -> r.getStr("name")).collect(Collectors.toList());
  }

  /**
   * 为指定用户创建一个新的邮箱目录
   */
  public void createMailbox(String username, String mailboxName) {
    if (StrUtil.isBlank(mailboxName)) {
      throw new IllegalArgumentException("Mailbox name cannot be blank");
    }
    Row user = mwUserService.getUserByUsername(username);
    if (user == null) {
      throw new IllegalStateException("User not found: " + username);
    }
    long mailboxId = SnowflakeIdUtils.id();
    long userId = user.getLong("id");

    Row newMailbox = Row.by("id", mailboxId).set("user_id", userId).set("name", mailboxName)
        //
        .set("uid_validity", mailboxId).set("uid_next", 1).set("creator", "system").set("updater", "system")
        //
        .set("tenant_id", user.getLong("tenant_id"));

    Db.save("mw_mailbox", "id", newMailbox);
    log.info("Created mailbox '{}' (id={}) for user {}", mailboxName, mailboxId, username);
  }

  public void copyEmailsByUidSet(String username, String srcMailboxName, String uidSet, String destMailboxName) {
    // 1. 找到要复制的邮件
    List<Email> toCopy = findEmailsByUidSet(username, srcMailboxName, uidSet);
    if (toCopy.isEmpty()) {
      return;
    }
    // 2. 对每封邮件，重新用 saveEmailInternal 插入到目标 mailbox
    for (Email e : toCopy) {
      // rawContent 来自 Email.getRawContent()
      mailSaveService.saveEmailInternal(username, destMailboxName, e.getRawContent());
    }
  }

  public boolean exitsMailBox(Long userId, String mailboxName) {
    String sql = "select count(1) from mw_mailbox where user_id=? and name=?";
    return Db.existsBySql(sql, userId, mailboxName);
  }

  public Long queryMailBoxId(Long userId, String mailboxName) {
    String sql = "select id from mw_mailbox where user_id=? and name=?";
    return Db.queryLong(sql, userId, mailboxName);
  }

  public void moveEmailsByUidSet(Long userId, String src, String uidSet, String dest) {
    long srcMailboxId = mailBoxService.getMailboxByName(userId, src).getLong("id");
    long destMailboxId = mailBoxService.getMailboxByName(userId, dest).getLong("id");

    // 1. 解析 UID set
    List<Long> uids = parseUidSet(uidSet, srcMailboxId); // 比如 [42L, 43L, 44L]
    // 2. 计算要 bump 的数量
    int cnt = uids.size();
    // 3. 准备 IN 占位符

    String inClause = uids.stream().map(u -> "?").collect(Collectors.joining(","));

    // 4. 从模板中取出 SQL，然后替换占位注释
    String raw = SqlTemplates.get("mailbox.moveEmails");
    String sql = String.format(raw, inClause);
    // 5. 按顺序组装参数：cnt, destMailboxId, srcMailboxId, 然后是每个 uid
    List<Object> params = new ArrayList<>();
    params.add(cnt);
    params.add(destMailboxId);
    params.add(cnt);
    params.add(srcMailboxId);
    params.addAll(uids);
    params.add(destMailboxId);
    // 6. 执行
    Db.updateBySql(sql, params.toArray());
  }

  public long highest_modseq(long mailboxId) {
    String sql = "select highest_modseq from mw_mailbox where id=?";
    return Db.queryLong(sql, mailboxId);
  }

  public List<Long> listUids(Long userId, Long mailBoxId) {
    String sql = SqlTemplates.get("mailbox.listUids");
    return Db.queryListLong(sql, userId, mailBoxId);
  }

  public Row status(Long boxId) {
    String sql = SqlTemplates.get("mailbox.status");
    return Db.findFirst(sql, boxId, boxId, boxId, boxId);
  }

}