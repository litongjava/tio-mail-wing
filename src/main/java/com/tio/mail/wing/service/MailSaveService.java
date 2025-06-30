package com.tio.mail.wing.service;

import java.util.List;
import java.util.Set;

import com.litongjava.db.activerecord.Db;
import com.litongjava.db.activerecord.Row;
import com.litongjava.jfinal.aop.Aop;
import com.litongjava.model.db.IAtom;
import com.litongjava.tio.core.ChannelContext;
import com.litongjava.tio.core.Tio;
import com.litongjava.tio.utils.lock.SetWithLock;
import com.tio.mail.wing.config.ImapServerConfig;
import com.tio.mail.wing.consts.MailBoxName;
import com.tio.mail.wing.model.Email;
import com.tio.mail.wing.model.MailRaw;
import com.tio.mail.wing.packet.ImapPacket;
import com.tio.mail.wing.utils.MailRawUtils;

import lombok.extern.slf4j.Slf4j;

@Slf4j
public class MailSaveService {
  private MailService mailService = Aop.get(MailService.class);
  private MwUserService mwUserService = Aop.get(MwUserService.class);
  private MailBoxService mailBoxService = Aop.get(MailBoxService.class);
  
  public boolean saveEmail(String toUser, MailRaw mail) {
    
    String rawContent = MailRawUtils.toRawContent(mail);
    return this.saveEmail(toUser, rawContent);
  }

  /**
   * [兼容SMTP] 将接收到的邮件保存到指定用户的收件箱(INBOX)中。
   */
  public boolean saveEmail(String username, String rawContent) {
    return saveEmailInternal(username, MailBoxName.INBOX, rawContent);
  }

  public boolean saveEmail(String toUser, String mailBoxName, MailRaw mail) {
    String rawContent = MailRawUtils.toRawContent(mail);
    return this.saveEmailInternal(toUser, mailBoxName, rawContent);
  }

  /**
   * 内部核心的邮件保存方法。
   * 优化：使用事务和原子性UID更新。
   */
  public boolean saveEmailInternal(String username, String mailboxName, String rawContent) {
    // 1. 获取用户和邮箱信息
    
    Row user = mwUserService.getUserByUsername(username);
    if (user == null) {
      log.error("User '{}' not found. Cannot save email.", username);
      return false;
    }
    Long userId = user.getLong("id");

    
    Row mailbox = mailBoxService.getMailboxByName(userId, mailboxName);
    if (mailbox == null) {
      log.error("Mailbox '{}' not found for user '{}'.", mailboxName, username);
      return false;
    }

    Long mailboxId = mailbox.getLong("id");
    return saveEmailInternal(username, mailboxName, rawContent, userId, mailboxId);
  }

  private boolean saveEmailInternal(String username, String mailboxName, String rawContent, Long userId, Long mailboxId) {
    IAtom atom = new MailSaveAtom(userId, username, mailboxId, mailboxName, rawContent);
    try {
      boolean result = Db.tx(atom);
      if (!result) {
        return result;
      }

      // 通知客户端

      SetWithLock<ChannelContext> channelContexts = Tio.getByUserId(ImapServerConfig.serverTioConfig, userId.toString());
      if (channelContexts == null) {
        return true;
      }
      Set<ChannelContext> ctxs = channelContexts.getObj();
      if (ctxs != null && ctxs.size() > 0) {
        List<Email> all = mailService.getActiveMailFlags(mailboxId);
        long exists = all.size();
        int recent = 0;
        for (Email e : all) {
          Set<String> flags = e.getFlags();
          if (flags.size() > 0) {
            if (flags.contains("\\Recent")) {
              recent++;
            }
          }
        }

        StringBuffer sb = new StringBuffer();
        sb.append("* ").append(exists).append(" EXISTS").append("\r\n");
        sb.append("* ").append(recent).append(" RECENT").append("\r\n");

        ImapPacket imapPacket = new ImapPacket(sb.toString());

        for (ChannelContext ctx : ctxs) {
          //* 9 EXISTS  
          //* 1 RECENT
          Tio.send(ctx, imapPacket);
        }

      }

      return result;
    } catch (Exception e) {
      log.error("Error saving email for user '{}' in mailbox '{}'", username, mailboxName, e);
      return false;
    }
  }
}
