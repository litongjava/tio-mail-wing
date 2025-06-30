package com.tio.mail.wing.handler;

import com.litongjava.jfinal.aop.Aop;
import com.litongjava.tio.boot.http.TioRequestContext;
import com.litongjava.tio.http.common.HttpRequest;
import com.litongjava.tio.http.common.HttpResponse;
import com.tio.mail.wing.consts.MailBoxName;
import com.tio.mail.wing.model.MailRaw;
import com.tio.mail.wing.service.MailSaveService;

import lombok.extern.slf4j.Slf4j;

@Slf4j
public class ErrorAlarmHandler {

  private MailSaveService mailSaveService = Aop.get(MailSaveService.class);

  public HttpResponse send(HttpRequest request) {
    HttpResponse response = TioRequestContext.getResponse();

    String fromUser = request.getHeader("mail-from-user");
    if (fromUser == null) {
      fromUser = "noreply@litong.xyz";
    }
    String toUser = request.getHeader("mail-to-user");
    if (toUser == null) {
      fromUser = "error@litong.xyz";
    }
    String mailBox = request.getHeader("mail-to-mailbox");
    if (mailBox == null) {
      mailBox = MailBoxName.INBOX;
    }
    String subject = request.getHeader("mail-subject");
    if (subject == null) {
      subject = "server_error";
    }
    log.info("from {} to {} {} subject {}", fromUser, toUser, mailBox, subject);
    String body = request.getBodyString();
    // 使用建造者模式创建一个邮件对象
    MailRaw mail = MailRaw.builder().from(fromUser).to(toUser).subject(subject).body(body).build();

    // Act: Save the email to the recipient's inbox
    boolean success = mailSaveService.saveEmail(toUser, mailBox, mail);
    if (success) {
      response.setStatus(200);
    } else {
      response.setStatus(500);
    }
    return response;
  }
}
