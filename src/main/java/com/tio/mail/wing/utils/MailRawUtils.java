package com.tio.mail.wing.utils;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

import com.tio.mail.wing.model.MailRaw;

public class MailRawUtils {

  /**
   * 根据 MailRaw 对象生成符合 RFC 5322 标准的邮件原文。
   * @param mail 包含所有邮件信息的对象
   * @return 邮件的原始文本内容 (raw content)
   */
  public static String toRawContent(MailRaw mail) {
    StringBuilder rawContent = new StringBuilder();

    // 1. 添加必要的邮件头
    rawContent.append("Message-ID: ").append(mail.getMessageId()).append("\r\n");
    rawContent.append("Date: ").append(mail.getFormattedDate()).append("\r\n");
    rawContent.append("MIME-Version: ").append(mail.getMimeVersion()).append("\r\n");
    rawContent.append("User-Agent: ").append(mail.getUserAgent()).append("\r\n");
    rawContent.append("Content-Type: ").append(mail.getContentType()).append("\r\n");
    rawContent.append("From: ").append(mail.getFrom()).append("\r\n");
    rawContent.append("To: ").append(mail.getTo()).append("\r\n");

    // 2. 添加可选的邮件头 (如果存在)
    if (mail.getCc() != null && !mail.getCc().isEmpty()) {
      rawContent.append("Cc: ").append(mail.getCc()).append("\r\n");
    }

    // 注意：Bcc (密送) 头不应该出现在邮件数据中，它只用于SMTP传输阶段的指令。

    rawContent.append("Subject: ").append(mail.getSubject()).append("\r\n");

    // 3. 添加一个空行来分隔邮件头和邮件体
    rawContent.append("\r\n");

    // 4. 添加邮件正文
    rawContent.append(mail.getBody());

    return rawContent.toString();
  }
  
  public static Map<String, String> parseHeaders(String rawContent) {
    Map<String, String> headers = new HashMap<>();
    String[] lines = rawContent.split("\r\n");
    for (String line : lines) {
      if (line.isEmpty())
        break;
      int colonIndex = line.indexOf(':');
      if (colonIndex > 0) {
        String key = line.substring(0, colonIndex).trim();
        String value = line.substring(colonIndex + 1).trim();
        if (Arrays.asList("Message-ID", "Subject", "From", "To").contains(key)) {
          headers.put(key, value);
        }
      }
    }
    return headers;
  }
}