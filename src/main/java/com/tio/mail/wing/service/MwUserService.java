package com.tio.mail.wing.service;

import com.litongjava.annotation.AService;
import com.litongjava.db.activerecord.Db;
import com.litongjava.db.activerecord.Row;
import com.litongjava.template.SqlTemplates;
import com.litongjava.tio.utils.digest.Sha256Utils;

@AService
public class MwUserService {
  /**
   * 认证用户
   * @param username 用户名
   * @param password 密码
   * @return 是否成功
   */
  public boolean authenticate(String username, String password) {
    String sql = "select password_hash from mw_user where username=? and deleted=0";
    String user_password_hash = Db.queryStr(sql,username);
    if(user_password_hash!=null) {
      return Sha256Utils.checkPassword(password, user_password_hash);
    }
    return false;
    
  }

  public boolean userExists(String username) {
    String sql = "select count(1) from mw_user where username=? and deleted=0";
    return Db.existsBySql(sql, username);
  }
  
  public Row getUserByUsername(String username) {
    return Db.findFirst(SqlTemplates.get("mailbox.user.findByUsername"), username);
  }
}