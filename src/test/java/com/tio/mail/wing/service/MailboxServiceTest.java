package com.tio.mail.wing.service;

import java.util.List;

import org.junit.Test;

import com.litongjava.db.activerecord.Db;
import com.litongjava.db.activerecord.Row;
import com.litongjava.jfinal.aop.Aop;
import com.litongjava.tio.boot.testing.TioBootTest;
import com.tio.mail.wing.config.MwBootConfig;
import com.tio.mail.wing.model.Email;
import com.tio.mail.wing.model.MailRaw;
import com.tio.mail.wing.utils.MailRawUtils;

public class MailboxServiceTest {

  @Test
  public void testSaveEmailAndGetActiveMessages() {
    TioBootTest.runWith(MwBootConfig.class);

    // Arrange: Define the email content
    String fromUser = "user1@tio.com";
    String toUser = "user2@tio.com";
    String subject = "hi";
    String body = "hi";
    // 使用建造者模式创建一个邮件对象
    MailRaw mail = MailRaw.builder().from(fromUser).to(toUser).subject(subject).body(body).build();

    String rawContent = MailRawUtils.toRawContent(mail);
    MailboxService mailboxService = Aop.get(MailboxService.class);

    // Act: Save the email to the recipient's inbox
    boolean success = mailboxService.saveEmail(toUser, rawContent);
    System.out.println(success);

    // Act: Retrieve active messages for the recipient
    List<Email> activeMessages = mailboxService.getActiveMessages(toUser);
    System.out.println(activeMessages);

    Email savedEmail = activeMessages.get(0);
    System.out.println(savedEmail);

    // Assert: Check mailbox's uid_next was incremented
    Row row = Db.findFirst("SELECT uid_next FROM mw_mailbox WHERE name = 'Inbox' AND user_id = 1002");
    if(row!=null) {
      Long nextUid = row.getLong("uid_next");
      System.out.println(nextUid);
    }

  }

  //  public void testGetStat() {
  //    // Arrange: Save an email first
  //    String toUser = "error@tio.com";
  //    String rawContent = "Subject: Test for stat\r\n\r\nHello.";
  //    mailboxService.saveEmail(toUser, rawContent);
  //
  //    // Act: Get the mailbox statistics
  //    int[] stats = mailboxService.getStat(toUser);
  //
  //    // Assert: Check the stats
  //    assertEquals(1, stats[0], "Message count should be 1.");
  //    assertEquals(rawContent.getBytes().length, stats[1], "Total size should match the raw content length.");
  //  }

}