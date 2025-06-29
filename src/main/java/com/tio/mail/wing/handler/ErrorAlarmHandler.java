package com.tio.mail.wing.handler;

import com.litongjava.jfinal.aop.Aop;
import com.litongjava.tio.boot.http.TioRequestContext;
import com.litongjava.tio.http.common.HttpRequest;
import com.litongjava.tio.http.common.HttpResponse;
import com.tio.mail.wing.model.MailRaw;
import com.tio.mail.wing.service.MailboxService;

public class ErrorAlarmHandler {

  public HttpResponse send(HttpRequest request) {
    HttpResponse response = TioRequestContext.getResponse();

    String fromUser = request.getHeader("mail_from_user");
    String toUser = request.getHeader("mail_to_user");
    String mailBox = request.getHeader("mail_to_mailbox");
    if (mailBox == null) {
      mailBox = "server_error";
    }
    String subject = request.getHeader("mail_subject");
    if (subject == null) {
      subject = "server_error";
    }

    String body = request.getBodyString();

    // 使用建造者模式创建一个邮件对象
    MailRaw mail = MailRaw.builder().from(fromUser).to(toUser).subject(subject).body(body).build();

    MailboxService mailboxService = Aop.get(MailboxService.class);
    // Act: Save the email to the recipient's inbox
    boolean success = mailboxService.saveEmail(toUser, mailBox, mail);
    if (success) {
      response.setStatus(200);
    } else {
      response.setStatus(500);
    }
    return response;
  }
}
