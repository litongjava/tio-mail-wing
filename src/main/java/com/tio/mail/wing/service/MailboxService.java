package com.tio.mail.wing.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import com.litongjava.db.activerecord.Db;
import com.litongjava.db.activerecord.Row;
import com.tio.mail.wing.consts.MailBoxName;
import com.tio.mail.wing.model.Email;

import lombok.extern.slf4j.Slf4j;

@Slf4j
public class MailboxService {

  /**
   * [兼容SMTP] 将接收到的邮件保存到指定用户的收件箱(INBOX)中。
   */
  public boolean saveEmail(String username, String rawContent) {
    // 默认存入 INBOX
    return saveEmailInternal(username, MailBoxName.INBOX, rawContent);
  }

  /**
   * [兼容POP3] 获取用户收件箱(INBOX)中所有未删除的邮件。
   */
  public List<Email> getActiveMessages(String username) {
    return getActiveMessages(username, MailBoxName.INBOX);
  }

  /**
   * [兼容POP3] 获取邮箱状态（邮件数，总大小），针对INBOX。
   */
  public int[] getStat(String username) {
    List<Email> activeMessages = getActiveMessages(username, MailBoxName.INBOX);
    int count = activeMessages.size();
    int totalSize = activeMessages.stream().mapToInt(Email::getSize).sum();
    return new int[] { count, totalSize };
  }

  /**
   * [兼容POP3] 获取指定邮件内容，针对INBOX。
   */
  public String getMessageContent(String username, int msgNumber) {
    List<Email> activeMessages = getActiveMessages(username, MailBoxName.INBOX);
    if (msgNumber > 0 && msgNumber <= activeMessages.size()) {
      return activeMessages.get(msgNumber - 1).getRawContent();
    }
    return null;
  }

  /**
   * [兼容POP3] 获取邮件大小列表，用于 LIST 命令，针对INBOX。
   */
  public List<Integer> listMessages(String username) {
    return getActiveMessages(username, MailBoxName.INBOX).stream().map(Email::getSize).collect(Collectors.toList());
  }

  /**
   * [兼容POP3] 获取邮件的唯一ID列表，用于 UIDL 命令，针对INBOX。
   */
  public List<Long> listUids(String username) {
    return getActiveMessages(username, MailBoxName.INBOX).stream().map(Email::getUid).collect(Collectors.toList());
  }

  // =================================================================
  // == IMAP 专用或内部核心方法
  // =================================================================

  /**
   * 内部核心的邮件保存方法。
   * 使用事务确保数据一致性。
   */
  private boolean saveEmailInternal(String username, String mailboxName, String rawContent) {
    try {
      return Db.tx(() -> {
        // 1. 获取用户和邮箱信息
        Row user = getUserByUsername(username);
        if (user == null) {
          log.error("User '{}' not found. Cannot save email.", username);
          return false;
        }
        long userId = user.getLong("id");

        Row mailbox = getMailboxByName(userId, mailboxName);
        if (mailbox == null) {
          log.error("Mailbox '{}' not found for user '{}'. Cannot save email.", mailboxName, username);
          // 或者在这里可以实现自动创建邮箱的逻辑
          // createMailbox(userId, mailboxName);
          // mailbox = getMailboxByName(userId, mailboxName);
          return false;
        }
        long mailboxId = mailbox.getLong("id");

        // 2. 处理邮件内容，实现去重 (mw_mail_message)
        String contentHash = calculateSha256(rawContent);
        int sizeInBytes = rawContent.getBytes(StandardCharsets.UTF_8).length;

        Row message = Db.findFirst("SELECT id FROM mw_mail_message WHERE content_hash = ?", contentHash);
        long messageId;
        if (message == null) {
          // 解析邮件头信息 (这是一个简化的示例)
          Map<String, String> headers = parseHeaders(rawContent);

          Row newMessage = new Row().set("content_hash", contentHash).set("message_id_header", headers.get("Message-ID")).set("subject", headers.get("Subject"))
              .set("from_address", headers.get("From")).set("to_address", headers.get("To")).set("size_in_bytes", sizeInBytes).set("raw_content", rawContent);
          // sent_date, cc_address, has_attachment 等字段需要更复杂的解析
          Db.save("mw_mail_message", "id", newMessage);
          messageId = newMessage.getLong("id");
        } else {
          messageId = message.getLong("id");
        }

        // 3. 原子地获取并更新邮箱的下一个UID (mw_mailbox)
        // 注意：这在并发下不是严格原子的。在高并发场景下，最好使用数据库的 `RETURNING` 子句或行级锁。
        // 但对于大多数情况和给定的 Db 工具，这种方式可以工作。
        long nextUid = mailbox.getLong("uid_next");
        int updatedRows = Db.updateBySql("UPDATE mw_mailbox SET uid_next = uid_next + 1 WHERE id = ?", mailboxId);
        if (updatedRows == 0) {
          throw new SQLException("Failed to increment uid_next for mailbox " + mailboxId);
        }

        // 4. 创建邮件实例 (mw_mail)
        Row mailInstance = new Row().set("user_id", userId).set("mailbox_id", mailboxId).set("message_id", messageId).set("uid", nextUid).set("internal_date", new Date()); // 使用当前服务器时间
        Db.save("mw_mail", "id", mailInstance);
        long mailInstanceId = mailInstance.getLong("id");

        // 5. 为新邮件设置 \Recent 标志 (mw_mail_flag)
        Row recentFlag = new Row().set("mail_id", mailInstanceId).set("flag", "\\Recent");
        Db.save("mw_mail_flag", recentFlag);

        log.info("Saved new email for {} in mailbox {} with UID {}. Mail instance ID: {}", username, mailboxName, nextUid, mailInstanceId);
        return true;
      });
    } catch (Exception e) {
      log.error("Error saving email for user '{}' in mailbox '{}'", username, mailboxName, e);
      return false;
    }
  }

  /**
   * [IMAP核心] 获取用户【指定邮箱】中所有未被标记为删除的邮件。
   */
  public List<Email> getActiveMessages(String username, String mailboxName) {
    Row user = getUserByUsername(username);
    if (user == null)
      return Collections.emptyList();
    Row mailbox = getMailboxByName(user.getLong("id"), mailboxName);
    if (mailbox == null)
      return Collections.emptyList();

    String sql = "SELECT m.id, m.uid, m.internal_date, msg.raw_content, msg.size_in_bytes " + "FROM mw_mail m " + "JOIN mw_mail_message msg ON m.message_id = msg.id "
        + "WHERE m.mailbox_id = ? AND NOT EXISTS (" + "  SELECT 1 FROM mw_mail_flag mf WHERE mf.mail_id = m.id AND mf.flag = '\\Deleted'" + ") ORDER BY m.uid ASC";

    List<Row> mailRows = Db.find(sql, mailbox.getLong("id"));
    if (mailRows.isEmpty()) {
      return Collections.emptyList();
    }

    // 高效地一次性获取所有相关邮件的标志
    List<Long> mailIds = mailRows.stream().map(r -> r.getLong("id")).collect(Collectors.toList());
    Map<Long, Set<String>> flagsMap = getFlagsForMailIds(mailIds);

    // 将 Row 转换为 Email DTO
    return mailRows.stream().map(row -> rowToEmail(row, flagsMap.getOrDefault(row.getLong("id"), new HashSet<>()))).collect(Collectors.toList());
  }

  /**
   * [IMAP核心] 获取邮箱的元数据。
   */
  public Map<String, Object> getMailboxMetadata(String username, String mailboxName) {
    Row user = getUserByUsername(username);
    if (user == null)
      return null;
    Row mailbox = getMailboxByName(user.getLong("id"), mailboxName);
    if (mailbox == null)
      return null;

    Map<String, Object> meta = new HashMap<>();
    meta.put("uidNext", mailbox.getLong("uid_next"));
    meta.put("uidValidity", mailbox.getLong("uid_validity"));
    return meta;
  }

  /**
   * [IMAP核心] 根据序号获取邮件。
   */
  public Email getMessageByNumber(String username, String mailboxName, int msgNumber) {
    List<Email> activeMessages = getActiveMessages(username, mailboxName);
    if (msgNumber > 0 && msgNumber <= activeMessages.size()) {
      return activeMessages.get(msgNumber - 1);
    }
    return null;
  }

  /**
   * [IMAP核心] 根据 UID 获取邮件。
   */
  public Email getMessageByUid(String username, String mailboxName, long uid) {
    Row user = getUserByUsername(username);
    if (user == null)
      return null;
    Row mailbox = getMailboxByName(user.getLong("id"), mailboxName);
    if (mailbox == null)
      return null;

    String sql = "SELECT m.id, m.uid, m.internal_date, msg.raw_content, msg.size_in_bytes " + "FROM mw_mail m " + "JOIN mw_mail_message msg ON m.message_id = msg.id "
        + "WHERE m.mailbox_id = ? AND m.uid = ?";

    Row row = Db.findFirst(sql, mailbox.getLong("id"), uid);
    if (row == null)
      return null;

    Set<String> flags = getFlagsForMailIds(Collections.singletonList(row.getLong("id"))).getOrDefault(row.getLong("id"), new HashSet<>());

    return rowToEmail(row, flags);
  }

  /**
   * [IMAP核心] 修改邮件标志。
   * @param email 包含邮件实例ID (mw_mail.id) 的Email对象
   * @param newFlags 要添加或移除的标志集合
   * @param add true表示添加, false表示移除
   */
  public void storeFlags(Email email, Set<String> newFlags, boolean add) {
    if (email == null || email.getId() == null || newFlags == null || newFlags.isEmpty()) {
      return;
    }
    long mailId = email.getId(); // 假设 email.id 存储的是 mw_mail.id

    if (add) {
      // 批量插入，忽略已存在的冲突
      // 注意: ON CONFLICT 是 PostgreSQL 特有的。对于 MySQL, 可以使用 INSERT IGNORE。
      // 如果是通用SQL, 需要先查询再插入，或者接受可能的异常。
      // 这里使用 Db.batchSave 并期望它能处理好，或者在循环中单独处理。
      List<Row> flagsToSave = new ArrayList<>();
      for (String flag : newFlags) {
        flagsToSave.add(new Row().set("mail_id", mailId).set("flag", flag));
      }
      // 使用 batchSave 可能因主键冲突而失败，更稳妥的方式是循环插入并捕获异常
      // 或者使用特定于数据库的 "INSERT IGNORE" 或 "ON CONFLICT DO NOTHING"
      // 这里为了演示，我们用一个简单的循环
      for (Row flagRow : flagsToSave) {
        try {
          Db.save("mw_mail_flag", flagRow);
        } catch (Exception e) {
          // 忽略主键冲突异常
          log.trace("Flag already exists for mail_id {} and flag {}", mailId, flagRow.getStr("flag"));
        }
      }
    } else {
      // 批量删除
      String placeholders = String.join(",", Collections.nCopies(newFlags.size(), "?"));
      String sql = "DELETE FROM mw_mail_flag WHERE mail_id = ? AND flag IN (" + placeholders + ")";

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
    Row user = getUserByUsername(username);
    if (user == null)
      return;
    Row mailbox = getMailboxByName(user.getLong("id"), mailboxName);
    if (mailbox == null)
      return;

    String sql = "DELETE FROM mw_mail_flag WHERE flag = '\\Recent' AND mail_id IN " + "(SELECT id FROM mw_mail WHERE mailbox_id = ?)";
    Db.updateBySql(sql, mailbox.getLong("id"));
  }

  // --- 用于 FETCH/STORE 命令的辅助方法 (逻辑不变, 依赖于 getActiveMessages) ---

  public List<Email> findEmailsByUidSet(String messageSet, List<Email> allEmails) {
    // 此方法逻辑不变，因为它操作的是一个已经获取到的 Email 列表
    // ... (原始代码)
    List<Email> foundEmails = new ArrayList<>();
    if (allEmails.isEmpty())
      return foundEmails;
    Set<Email> resultSet = new HashSet<>();
    long maxUid = allEmails.stream().mapToLong(Email::getUid).max().orElse(0);

    String[] parts = messageSet.split(",");
    for (String part : parts) {
      part = part.trim();
      if (part.contains(":")) {
        String[] range = part.split(":", 2);
        long start = range[0].equals("*") ? maxUid : Long.parseLong(range[0]);
        long end = range[1].equals("*") ? maxUid : Long.parseLong(range[1]);
        if (start > end) {
          long temp = start;
          start = end;
          end = temp;
        }
        for (Email email : allEmails) {
          if (email.getUid() >= start && email.getUid() <= end)
            resultSet.add(email);
        }
      } else {
        long uidToFind = part.equals("*") ? maxUid : Long.parseLong(part);
        for (Email email : allEmails) {
          if (email.getUid() == uidToFind) {
            resultSet.add(email);
            break;
          }
        }
      }
    }
    foundEmails.addAll(resultSet);
    foundEmails.sort(Comparator.comparingLong(Email::getUid));
    return foundEmails;
  }

  public List<Email> findEmailsBySeqSet(String messageSet, List<Email> allEmails) {
    // 此方法逻辑不变
    // ... (原始代码)
    List<Email> foundEmails = new ArrayList<>();
    if (allEmails.isEmpty())
      return foundEmails;
    Set<Email> resultSet = new HashSet<>();
    int maxSeq = allEmails.size();

    String[] parts = messageSet.split(",");
    for (String part : parts) {
      part = part.trim();
      if (part.contains(":")) {
        String[] range = part.split(":", 2);
        int start = range[0].equals("*") ? maxSeq : Integer.parseInt(range[0]);
        int end = range[1].equals("*") ? maxSeq : Integer.parseInt(range[1]);
        if (start > end) {
          int temp = start;
          start = end;
          end = temp;
        }
        for (int i = start; i <= end; i++) {
          if (i > 0 && i <= maxSeq)
            resultSet.add(allEmails.get(i - 1));
        }
      } else {
        int seqToFind = part.equals("*") ? maxSeq : Integer.parseInt(part);
        if (seqToFind > 0 && seqToFind <= maxSeq)
          resultSet.add(allEmails.get(seqToFind - 1));
      }
    }
    foundEmails.addAll(resultSet);
    foundEmails.sort(Comparator.comparingLong(Email::getUid));
    return foundEmails;
  }

  // =================================================================
  // == 私有辅助方法
  // =================================================================

  private Row getUserByUsername(String username) {
    return Db.findFirst("SELECT id FROM mw_user WHERE username = ? AND deleted = 0", username);
  }

  private Row getMailboxByName(long userId, String mailboxName) {
    return Db.findFirst("SELECT id, uid_validity, uid_next FROM mw_mailbox WHERE user_id = ? AND name = ? AND deleted = 0", userId, mailboxName);
  }

  /**
   * 将数据库行转换为 Email DTO 对象。
   */
  private Email rowToEmail(Row row, Set<String> flags) {
    Email email = new Email();
    email.setId(row.getLong("id")); // mw_mail.id
    email.setUid(row.getLong("uid"));
    email.setRawContent(row.getStr("raw_content"));
    email.setSize(row.getInt("size_in_bytes"));
    email.setFlags(new HashSet<>(flags)); // 使用副本以保证线程安全

    OffsetDateTime internalDate = row.getOffsetDateTime("internal_date");
    if (internalDate != null) {
      email.setInternalDate(internalDate);
    }
    return email;
  }

  /**
   * 为给定的邮件实例ID列表，批量获取它们的标志。
   * @return Map<MailId, Set<Flag>>
   */
  private Map<Long, Set<String>> getFlagsForMailIds(List<Long> mailIds) {
    if (mailIds == null || mailIds.isEmpty()) {
      return Collections.emptyMap();
    }
    Map<Long, Set<String>> flagsMap = new HashMap<>();
    mailIds.forEach(id -> flagsMap.put(id, new HashSet<>()));

    String placeholders = String.join(",", Collections.nCopies(mailIds.size(), "?"));
    String sql = "SELECT mail_id, flag FROM mw_mail_flag WHERE mail_id IN (" + placeholders + ")";

    List<Row> flagRows = Db.find(sql, mailIds.toArray());

    for (Row flagRow : flagRows) {
      flagsMap.get(flagRow.getLong("mail_id")).add(flagRow.getStr("flag"));
    }
    return flagsMap;
  }

  /**
   * 计算字符串的 SHA-256 哈希值。
   */
  private String calculateSha256(String text) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      byte[] hash = digest.digest(text.getBytes(StandardCharsets.UTF_8));
      StringBuilder hexString = new StringBuilder();
      for (byte b : hash) {
        String hex = Integer.toHexString(0xff & b);
        if (hex.length() == 1)
          hexString.append('0');
        hexString.append(hex);
      }
      return hexString.toString();
    } catch (NoSuchAlgorithmException e) {
      throw new RuntimeException("SHA-256 algorithm not found", e);
    }
  }

  /**
   * 简化的邮件头解析器。生产环境建议使用 javax.mail 或类似库。
   */
  private Map<String, String> parseHeaders(String rawContent) {
    Map<String, String> headers = new HashMap<>();
    String[] lines = rawContent.split("\r\n");
    for (String line : lines) {
      if (line.isEmpty()) {
        break; // 邮件头结束
      }
      int colonIndex = line.indexOf(':');
      if (colonIndex > 0) {
        String key = line.substring(0, colonIndex).trim();
        String value = line.substring(colonIndex + 1).trim();
        // 只存储我们关心的几个头
        if (Arrays.asList("Message-ID", "Subject", "From", "To").contains(key)) {
          headers.put(key, value);
        }
      }
    }
    return headers;
  }
}