package com.sejie.admin.sql;

import org.junit.Test;

import com.litongjava.template.SqlTemplates;

public class SqlTemplatesTest {
  @Test
  public void findByUsername() {
    String sqlTemplate = SqlTemplates.get("mailbox.user.findByUsername");
    System.out.println(sqlTemplate);
  }

  @Test
  public void getActiveMessages() {
    String sqlTemplate = SqlTemplates.get("mailbox.getActiveMessages");
    System.out.println("--- Fetched SQL for mailbox.getActiveMessages ---");
    System.out.println(sqlTemplate);
    System.out.println("--- End of SQL ---");

    // 也可以单独获取被包含的部分，以验证它是否被正确加载
    String cteTemplate = SqlTemplates.get("mailbox.baseRankedEmailsCTE");
    System.out.println("\n--- Fetched SQL for mailbox.baseRankedEmailsCTE ---");
    System.out.println(cteTemplate);
    System.out.println("--- End of SQL ---");
  }

}
