// src/main/java/com/tio/mail/wing/model/Email.java
package com.tio.mail.wing.model;

import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 邮件实体类 (面向数据库和真实场景设计)
 * 代表一封存储在服务器上的邮件实例。
 */
@Data
@NoArgsConstructor
public class Email {

  /**
   * 数据库主键 (例如：自增ID)
   * 这个ID主要用于数据库内部关联，不直接暴露给IMAP客户端。
   */
  private Long id;

  /**
   * 所属用户ID
   * 用于标识这封邮件属于哪个用户。
   */
  private Long userId;

  /**
   * 所属邮箱ID (Mailbox ID)
   * 用于标识这封邮件属于用户的哪个邮箱（INBOX, Sent, etc.）。
   */
  private Long mailboxId;

  /**
   * IMAP UID (Unique Identifier)
   * 这是邮件在【一个特定邮箱内】的唯一标识符。
   * 在数据库中，对于同一个 mailboxId，uid 必须是唯一的。
   */
  private long uid;

  /**
   * 邮件的完整原始内容 (MIME 格式)
   * 存储在数据库中通常使用 TEXT 或 BLOB 类型。
   * 为了性能，有时会将其存储在文件系统或对象存储中，数据库只存路径。
   */
  private String rawContent;

  /**
   * 邮件大小 (单位：字节)
   * 在存入时计算一次，避免重复计算。
   */
  private int size;

  /**
   * 邮件的内部接收日期
   * 服务器接收到这封邮件的时间。用于排序等。
   */
  private OffsetDateTime internalDate;

  // --- IMAP 状态字段 ---

  /**
   * IMAP 标志 (e.g., \Seen, \Answered, \Flagged, \Deleted, \Draft, \Recent)
   * 在数据库中可以存为以逗号分隔的字符串，或使用关联表。
   */
  private Set<String> flags = new HashSet<>();

  // --- 非持久化字段 (用于业务逻辑) ---

  /**
   * 解析后的邮件头 (懒加载)
   * 为了避免每次都解析 rawContent，可以解析一次后缓存起来。
   * transient 表示这个字段不会被JPA/MyBatis等ORM框架持久化。
   */
  private transient Map<String, String> headers;

  /**
   * 构造函数，用于从原始MIME内容创建邮件对象。
   * @param rawContent 邮件的原始内容
   */
  public Email(String rawContent) {
    this.rawContent = rawContent;
    this.size = rawContent.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
    this.internalDate = OffsetDateTime.now(); // 记录接收时间
    this.flags.add("\\Recent"); // 新邮件默认为 Recent
  }

  /**
   * 懒加载并获取解析后的邮件头。
   * @return 解析后的邮件头 Map
   */
  public Map<String, String> getHeaders() {
    if (headers == null) {
      headers = new HashMap<>();
      if (rawContent != null) {
        String[] lines = rawContent.split("\\r?\\n");
        for (String line : lines) {
          if (line.isEmpty()) {
            break; // 邮件头结束
          }
          int colonIndex = line.indexOf(':');
          if (colonIndex > 0) {
            String key = line.substring(0, colonIndex).trim();
            String value = line.substring(colonIndex + 1).trim();
            // 只存第一个同名header，或拼接，根据需要
            headers.putIfAbsent(key.toUpperCase(), value);
          }
        }
      }
    }
    return headers;
  }

  /**
   * 便捷方法，获取邮件头中的 Message-ID
   * @return Message-ID 或 null
   */
  public String getMessageId() {
    return getHeaders().get("MESSAGE-ID");
  }

  // 重写 equals 和 hashCode 以确保基于数据库ID的唯一性
  @Override
  public boolean equals(Object o) {
    if (this == o)
      return true;
    if (o == null || getClass() != o.getClass())
      return false;
    Email email = (Email) o;
    return id != null && id.equals(email.id);
  }

  @Override
  public int hashCode() {
    return id != null ? id.hashCode() : 0;
  }
}