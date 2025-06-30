package com.tio.mail.wing.service;

import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.util.Date;
import java.util.Map;

import com.litongjava.db.activerecord.Db;
import com.litongjava.db.activerecord.Row;
import com.litongjava.model.db.IAtom;
import com.litongjava.template.SqlTemplates;
import com.litongjava.tio.utils.digest.Sha256Utils;
import com.litongjava.tio.utils.snowflake.SnowflakeIdUtils;
import com.tio.mail.wing.utils.MailRawUtils;

import lombok.extern.slf4j.Slf4j;

@Slf4j
public class MailSaveAtom implements IAtom {

  private Long userId;
  private String username;
  private Long mailboxId;
  private String mailboxName;
  private String rawContent;

  public MailSaveAtom(Long userId, String username, Long mailboxId, String mailboxName, String rawContent) {
    this.userId = userId;
    this.username = username;
    this.mailboxId = mailboxId;
    this.mailboxName = mailboxName;
    this.rawContent = rawContent;
  }

  @Override
  public boolean run() throws SQLException {

    // 2. 处理邮件内容，实现去重 (mw_mail_message)
    String contentHash = Sha256Utils.digestToHex(rawContent);
    int sizeInBytes = rawContent.getBytes(StandardCharsets.UTF_8).length;

    Row message = Db.findFirst(SqlTemplates.get("mailbox.message.findByHash"), contentHash);
    long messageId;
    long id = SnowflakeIdUtils.id();
    if (message == null) {
      Map<String, String> headers = MailRawUtils.parseHeaders(rawContent);
      Row newMessage = Row.by("id", id).set("content_hash", contentHash)
          //
          .set("message_id_header", headers.get("Message-ID"))
          //
          .set("subject", headers.get("Subject"))
          //
          .set("from_address", headers.get("From")).set("to_address", headers.get("To"))
          //
          .set("size_in_bytes", sizeInBytes).set("raw_content", rawContent);
      Db.save("mw_mail_message", "id", newMessage);
      messageId = newMessage.getLong("id");
    } else {
      messageId = message.getLong("id");
    }

    // 3. 原子地获取并更新邮箱的下一个UID (mw_mailbox)
    String updateSql = SqlTemplates.get("mailbox.updateUidNextAndGet");
    Row result = Db.findFirst(updateSql, mailboxId);
    if (result == null) {
      throw new SQLException("Failed to increment and retrieve uid_next for mailbox " + mailboxId);
    }
    long nextUid = result.getLong("next_uid");

    // 4. 创建邮件实例 (mw_mail)
    Row mailInstance = Row.by("id", id).set("user_id", userId).set("mailbox_id", mailboxId).set("message_id", messageId).set("uid", nextUid).set("internal_date", new Date());
    Db.save("mw_mail", "id", mailInstance);

    // 5. 为新邮件设置 \Recent 标志 (mw_mail_flag)
    long flagId = SnowflakeIdUtils.id();
    Row recentFlag = Row.by("id", flagId).set("mail_id", id).set("flag", "\\Recent");
    Db.save("mw_mail_flag", recentFlag);
    log.info("Saved new email for {} in mailbox {} with UID {}. Mail instance ID: {}", username, mailboxName, nextUid, id);
    return true;
  }

}
