package com.tio.mail.wing.model;

import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.UUID;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class MailRaw {
  private String from; // 发件人地址，例如 "alice <alice@localdomain>"
  private String to; // 收件人地址
  private String cc; // 抄送地址 (可选)
  private String bcc; // 密送地址 (可选, 注意Bcc不会出现在最终的邮件头中)
  private String subject; // 主题
  private String body; // 邮件正文

  @Builder.Default
  private String messageId = "<" + UUID.randomUUID().toString() + "@tio.com>";

  @Builder.Default
  private ZonedDateTime date = ZonedDateTime.now();

  @Builder.Default
  private String contentType = "text/plain; charset=UTF-8; format=flowed";

  @Builder.Default
  private String mimeVersion = "1.0";

  @Builder.Default
  private String userAgent = "Mozilla Thunderbird";

  // 用于格式化日期为 RFC 2822 标准格式
  public String getFormattedDate() {
    // 示例: Mon, 23 Jun 2025 09:06:36 -1000
    return DateTimeFormatter.RFC_1123_DATE_TIME.format(this.date);
  }
}