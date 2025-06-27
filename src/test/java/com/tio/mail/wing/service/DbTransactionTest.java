package com.tio.mail.wing.service;

import java.sql.Connection;
import java.sql.SQLException;

import org.junit.Test;

import com.litongjava.db.activerecord.ActiveRecordException;
import com.litongjava.db.activerecord.Config;
import com.litongjava.db.activerecord.Db;
import com.litongjava.db.activerecord.DbPro;

public class DbTransactionTest {

  @Test
  public void test() {
    // Db.use() 取默认实例
    DbPro db = Db.use();
    Config config = db.getConfig();
    Connection conn = null;
    boolean oldAutoCommit = true;
    String fromAccountId = null;
    try {
      // 1. 获得连接
      conn = config.getConnection();
      // 2. 关闭自动提交
      oldAutoCommit = conn.getAutoCommit();
      conn.setAutoCommit(false);

      // 3. 在同一个 conn 上执行多个操作
      int count = db.update(config, conn, "UPDATE account SET balance = balance - ? WHERE id = ?", 100, fromAccountId);
      boolean ok = db.save(config, conn, "INSERT INTO account_log(account_id, change_amt) VALUES(?, ?)", fromAccountId, -100);

      // 4. 提交
      conn.commit();

    } catch (Exception e) {
      // 出错回滚
      if (conn != null) {
        try {
          conn.rollback();
        } catch (SQLException ex) {
        }
      }
      throw new ActiveRecordException(e);
    } finally {
      if (conn != null) {
        try {
          // 5. 恢复原始的自动提交状态
          conn.setAutoCommit(oldAutoCommit);
        } catch (SQLException e) {
        }
        // 关闭连接（归还连接池）
        config.close(conn);
      }
    }

  }
}
