package com.tio.mail.wing.service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

import com.tio.mail.wing.model.Email;

import lombok.extern.slf4j.Slf4j;

@Slf4j
public class MailboxService {

  // --- 模拟数据库存储 ---

  private static final Map<String, Map<String, Object>> mailboxesMeta = new ConcurrentHashMap<>();
  private static final Map<Long, Email> emailStore = new ConcurrentHashMap<>();
  private static final AtomicLong emailIdGenerator = new AtomicLong(0);

  // --- 静态初始化 ---
  static {
    // 为 user1@tio.com 初始化 INBOX
    createMailbox("user1@tio.com", "INBOX");

    String email1Content = "Return-Path: <sender@example.com>\r\n" + "Date: Mon, 23 Jun 2025 08:00:00 -1000\r\n" + "From: sender@example.com\r\n" + "To: user1@tio.com\r\n" + "Subject: Test Mail 1\r\n"
        + "Message-ID: <msg1.12345@example.com>\r\n" + "\r\n" + "This is the body of the first email.";
    saveEmail("user1@tio.com", email1Content); // 使用兼容的 saveEmail

    String email2Content = "Return-Path: <another@example.com>\r\n" + "Date: Mon, 23 Jun 2025 09:06:36 -1000\r\n" + "From: another@example.com\r\n" + "To: user1@tio.com\r\n"
        + "Subject: Hello World\r\n" + "Message-ID: <msg2.67890@example.com>\r\n" + "\r\n" + "This is the second message.";
    saveEmail("user1@tio.com", email2Content); // 使用兼容的 saveEmail

    // 为 user2@tio.com 创建一个空邮箱
    createMailbox("user2@tio.com", "INBOX");
  }

  /**
   * 内部辅助方法，用于创建邮箱。
   */
  private static void createMailbox(String username, String mailboxName) {
    String mailboxKey = username + ":" + mailboxName;
    if (!mailboxesMeta.containsKey(mailboxKey)) {
      Map<String, Object> meta = new ConcurrentHashMap<>();
      meta.put("uidNext", new AtomicLong(1));
      long uidValidity = "INBOX".equalsIgnoreCase(mailboxName) ? 1234567890L : System.currentTimeMillis();
      meta.put("uidValidity", uidValidity);
      mailboxesMeta.put(mailboxKey, meta);
      log.info("Created mailbox '{}' for user '{}'", mailboxName, username);
    }
  }

  // =================================================================
  // == 公共API (兼容 POP3, SMTP, IMAP)
  // =================================================================

  /**
   * [兼容SMTP] 将接收到的邮件保存到指定用户的收件箱(INBOX)中。
   * 保持旧的签名不变。
   */
  public static boolean saveEmail(String username, String rawContent) {
    // 默认存入 INBOX
    return saveEmailInternal(username, "INBOX", rawContent);
  }

  /**
   * [兼容POP3] 获取用户收件箱(INBOX)中所有未删除的邮件。
   */
  public List<Email> getActiveMessages(String username) {
    return getActiveMessages(username, "INBOX");
  }

  /**
   * [兼容POP3] 获取邮箱状态（邮件数，总大小），针对INBOX。
   */
  public int[] getStat(String username) {
    List<Email> activeMessages = getActiveMessages(username, "INBOX");
    int count = activeMessages.size();
    int totalSize = activeMessages.stream().mapToInt(Email::getSize).sum();
    return new int[] { count, totalSize };
  }

  /**
   * [兼容POP3] 获取指定邮件内容，针对INBOX。
   */
  public String getMessageContent(String username, int msgNumber) {
    List<Email> activeMessages = getActiveMessages(username, "INBOX");
    if (msgNumber > 0 && msgNumber <= activeMessages.size()) {
      return activeMessages.get(msgNumber - 1).getRawContent();
    }
    return null;
  }

  /**
   * [兼容POP3] 获取邮件大小列表，用于 LIST 命令，针对INBOX。
   */
  public List<Integer> listMessages(String username) {
    return getActiveMessages(username, "INBOX").stream().map(Email::getSize).collect(Collectors.toList());
  }

  /**
   * [兼容POP3] 获取邮件的唯一ID列表，用于 UIDL 命令，针对INBOX。
   */
  public List<Long> listUids(String username) {
    return getActiveMessages(username, "INBOX").stream().map(Email::getUid).collect(Collectors.toList());
  }

  // =================================================================
  // == IMAP 专用或内部核心方法
  // =================================================================

  /**
   * 内部核心的邮件保存方法。
   */
  private static boolean saveEmailInternal(String username, String mailboxName, String rawContent) {
    String mailboxKey = username + ":" + mailboxName;
    Map<String, Object> mailboxMeta = mailboxesMeta.get(mailboxKey);
    if (mailboxMeta == null) {
      log.error("Mailbox '{}' not found for user '{}'. Cannot save email.", mailboxName, username);
      return false;
    }

    Email email = new Email(rawContent);
    email.setId(emailIdGenerator.incrementAndGet());
    email.setUserId(username.hashCode() & 0xFFFFFFFFL);
    email.setMailboxId((long) mailboxKey.hashCode());

    AtomicLong uidNext = (AtomicLong) mailboxMeta.get("uidNext");
    email.setUid(uidNext.getAndIncrement());

    emailStore.put(email.getId(), email);
    log.info("Saved new email for {} in mailbox {} with UID {}", username, mailboxName, email.getUid());
    return true;
  }

  /**
   * [IMAP核心] 获取用户【指定邮箱】中所有未被标记为删除的邮件。
   */
  public List<Email> getActiveMessages(String username, String mailboxName) {
    long mockUserId = username.hashCode() & 0xFFFFFFFFL;
    long mockMailboxId = (username + ":" + mailboxName).hashCode();

    return emailStore.values().stream().filter(e -> e.getUserId() == mockUserId && e.getMailboxId() == mockMailboxId).filter(e -> !e.getFlags().contains("\\Deleted"))
        .sorted(Comparator.comparingLong(Email::getUid)).collect(Collectors.toList());
  }

  /**
   * [IMAP核心] 获取邮箱的元数据。
   */
  public Map<String, Object> getMailboxMetadata(String username, String mailboxName) {
    return mailboxesMeta.get(username + ":" + mailboxName);
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
    long mockUserId = username.hashCode() & 0xFFFFFFFFL;
    long mockMailboxId = (username + ":" + mailboxName).hashCode();

    return emailStore.values().stream().filter(e -> e.getUserId() == mockUserId && e.getMailboxId() == mockMailboxId && e.getUid() == uid).findFirst().orElse(null);
  }

  /**
   * [IMAP核心] 修改邮件标志。
   */
  public Email storeFlags(Email email, Set<String> newFlags, boolean add) {
    if (email != null) {
      synchronized (email) {
        if (add) {
          email.getFlags().addAll(newFlags);
        } else {
          email.getFlags().removeAll(newFlags);
        }
      }
    }
    return email;
  }

  /**
   * [IMAP核心] 清除指定用户邮箱的所有 \Recent 标志。
   */
  public void clearRecentFlags(String username, String mailboxName) {
    List<Email> emails = getActiveMessages(username, mailboxName);
    synchronized (emails) {
      for (Email email : emails) {
        synchronized (email) {
          email.getFlags().remove("\\Recent");
        }
      }
    }
  }

  // --- 用于 FETCH/STORE 命令的辅助方法 ---

  public List<Email> findEmailsByUidSet(String messageSet, List<Email> allEmails) {
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
}