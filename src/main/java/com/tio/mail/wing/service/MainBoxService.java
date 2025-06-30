package com.tio.mail.wing.service;

import com.litongjava.db.activerecord.Db;
import com.litongjava.db.activerecord.Row;

public class MainBoxService {

  public Row getMailboxByName(long userId, String mailboxName) {
    String sql = "SELECT id, uid_validity, uid_next FROM mw_mailbox WHERE user_id = ? AND name = ? AND deleted = 0";
    return Db.findFirst(sql, userId, mailboxName);
  }

  public Row getMailboxById(long userId, long mailboxId) {
    String sql = "SELECT id, uid_validity, uid_next FROM mw_mailbox WHERE user_id = ? AND id = ? AND deleted = 0";
    return Db.findFirst(sql, userId, mailboxId);
  }

  public Long getMailboxIdByName(long userId, String mailboxName) {
    String sql = "SELECT id WHERE user_id = ? AND name = ? AND deleted = 0";
    return Db.queryLong(sql, userId, mailboxName);
  }

}
