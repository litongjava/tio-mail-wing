package com.sejie.admin.sql;

import org.junit.Test;

import com.litongjava.template.SqlTemplates;

public class SqlTemplatesTest {
  @Test
  public void loadTest() {
    String sqlTemplate = SqlTemplates.get("mailbox.user.findByUsername");
    System.out.println(sqlTemplate);
  }

}
