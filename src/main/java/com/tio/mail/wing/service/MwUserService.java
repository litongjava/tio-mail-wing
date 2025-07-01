package com.tio.mail.wing.service;

import com.litongjava.db.activerecord.Db;
import com.litongjava.db.activerecord.Row;
import com.litongjava.template.SqlTemplates;
import com.litongjava.tio.utils.digest.Sha256Utils;

public class MwUserService {
  /**
   * 认证用户
   * @param username 用户名
   * @param password 密码
   * @return 是否成功
   */
  public Long authenticate(String username, String password) {
    String sql = "select id,password_hash from mw_user where username=? and deleted=0";
    Row row = Db.findFirst(sql, username);
    if (row == null) {
      return null;
    }
    String user_password_hash = row.getString("password_hash");
    if (user_password_hash != null) {
      if (Sha256Utils.checkPassword(password, user_password_hash)) {
        return row.getLong("id");
      }
    }
    return null;

  }

  public boolean userExists(String username) {
    String sql = "select count(1) from mw_user where username=? and deleted=0";
    return Db.existsBySql(sql, username);
  }

  public boolean userExists(Long userId) {
    String sql = "select count(1) from mw_user where id=? and deleted=0";
    return Db.existsBySql(sql, userId);
  }

  public Long getUserIdByUsername(String username) {
    String sql = "select id from mw_user where username=? and deleted=0";
    return Db.queryLong(sql, username);
  }

  public Row getUserByUsername(String username) {
    return Db.findFirst(SqlTemplates.get("mailbox.user.findByUsername"), username);
  }
}