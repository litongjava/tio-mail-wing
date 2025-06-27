// src/main/java/com/tio/mail/wing/handler/SmtpSessionContext.java
package com.tio.mail.wing.handler;

import java.util.ArrayList;
import java.util.List;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class SmtpSessionContext {

  // SMTP 会话状态机
  public enum State {
    /** 初始连接，等待 HELO/EHLO */
    CONNECTED,
    /** 已问候，等待认证或邮件事务 */
    GREETED,
    /** 已发送 AUTH LOGIN，等待 Base64 用户名 */
    AUTH_WAIT_USERNAME,
    /** 已收到用户名，等待 Base64 密码 */
    AUTH_WAIT_PASSWORD,
    /** 已收到 MAIL FROM，等待 RCPT TO 或 DATA */
    MAIL_FROM_RECEIVED,
    /** 已收到 RCPT TO，等待更多 RCPT TO 或 DATA */
    RCPT_TO_RECEIVED,
    /** 正在接收邮件内容 */
    DATA_RECEIVING,
    /** 准备关闭 */
    QUIT
  }

  private State state = State.CONNECTED;
  private boolean authenticated = false;
  private String username; // 认证后的用户名

  // 用于一封邮件的临时数据
  private String fromAddress;
  private List<String> toAddresses = new ArrayList<>();
  private StringBuilder mailContent = new StringBuilder();

  /**
   * 重置邮件事务状态，以便在同一连接中发送下一封邮件
   */
  public void resetTransaction() {
    this.fromAddress = null;
    this.toAddresses.clear();
    this.mailContent.setLength(0);
    // 认证状态保留，但事务状态回到 GREETED
    this.state = State.GREETED;
  }
}