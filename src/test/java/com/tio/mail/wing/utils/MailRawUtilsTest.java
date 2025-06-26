package com.tio.mail.wing.utils;

import org.junit.Test;

import com.tio.mail.wing.model.MailRaw;

public class MailRawUtilsTest {

  @Test
  public void testToRawContent() {
    // 使用建造者模式创建一个邮件对象
    MailRaw mail = MailRaw.builder().from("alice <alice@localdomain>").to("bob@localdomain").cc("charlie@localdomain").subject("Work")
        .body("This is the main content of the email.\r\nLet's discuss the project.").build();

    String rawEmail = MailRawUtils.toRawContent(mail);

    System.out.println("--- Generated Raw Email ---");
    System.out.println(rawEmail);
  }

}
