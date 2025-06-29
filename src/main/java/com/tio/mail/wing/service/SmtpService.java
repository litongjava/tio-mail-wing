package com.tio.mail.wing.service;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.UUID;

import com.litongjava.jfinal.aop.Aop;
import com.tio.mail.wing.handler.SmtpSessionContext;

public class SmtpService {
  private final MwUserService userService = Aop.get(MwUserService.class);
  private final MailboxService mailboxService = Aop.get(MailboxService.class);

  public String handleEhlo(String[] parts, SmtpSessionContext session) {
    if (session.getState() != SmtpSessionContext.State.CONNECTED) {
      return "503 Bad sequence of commands\r\n";
    }
    String domain = parts.length > 1 ? parts[1] : "unknown";
    session.setState(SmtpSessionContext.State.GREETED);

    StringBuilder sb = new StringBuilder();
    sb.append("250-tio-mail-wing says hello to ").append(domain).append("\r\n");
    sb.append("250 AUTH LOGIN\r\n");
    return sb.toString();
  }

  public String handleAuth(String[] parts, SmtpSessionContext session) {
    if (session.getState() != SmtpSessionContext.State.GREETED) {
      return "503 Bad sequence of commands\r\n";
    }
    if (parts.length > 1 && "LOGIN".equalsIgnoreCase(parts[1])) {
      session.setState(SmtpSessionContext.State.AUTH_WAIT_USERNAME);
      String challenge = Base64.getEncoder().encodeToString("Username:".getBytes(StandardCharsets.UTF_8));
      return "334 " + challenge + "\r\n";
    } else {
      return "504 Authentication mechanism not supported\r\n";
    }
  }

  public String handleAuthData(String line, SmtpSessionContext session) {
    try {
      String decoded = new String(Base64.getDecoder().decode(line), StandardCharsets.UTF_8);
      if (session.getState() == SmtpSessionContext.State.AUTH_WAIT_USERNAME) {
        session.setUsername(decoded);
        session.setState(SmtpSessionContext.State.AUTH_WAIT_PASSWORD);
        String challenge = Base64.getEncoder().encodeToString("Password:".getBytes(StandardCharsets.UTF_8));
        return "334 " + challenge + "\r\n";
      } else if (session.getState() == SmtpSessionContext.State.AUTH_WAIT_PASSWORD) {
        String username = session.getUsername();
        Long userId = userService.authenticate(username, decoded);
        if (userId!=null) {
          session.setAuthenticated(true);
          session.setUserId(userId);
          session.setState(SmtpSessionContext.State.GREETED);
          return "235 Authentication successful\r\n";
        } else {
          session.resetTransaction();
          session.setState(SmtpSessionContext.State.GREETED);
          return "535 Authentication failed\r\n";
        }
      }
    } catch (Exception e) {
      session.resetTransaction();
      session.setState(SmtpSessionContext.State.GREETED);
      return "501 Invalid base64 data\r\n";
    }
    // shouldn't reach here
    return "501 Authentication sequence error\r\n";
  }

  public String handleMail(String line, SmtpSessionContext session) {
    if (session.getState() != SmtpSessionContext.State.GREETED || !session.isAuthenticated()) {
      return "503 Bad sequence of commands or not authenticated\r\n";
    }
    int start = line.indexOf('<'), end = line.lastIndexOf('>');
    if (start < 0 || end < 0 || end <= start + 1) {
      return "501 Invalid address\r\n";
    }
    String from = line.substring(start + 1, end);
    session.setFromAddress(from);
    session.setState(SmtpSessionContext.State.MAIL_FROM_RECEIVED);
    return "250 OK\r\n";
  }

  public String handleRcpt(String line, SmtpSessionContext session) {
    SmtpSessionContext.State st = session.getState();
    if (st != SmtpSessionContext.State.MAIL_FROM_RECEIVED && st != SmtpSessionContext.State.RCPT_TO_RECEIVED) {
      return "503 Bad sequence of commands\r\n";
    }
    int start = line.indexOf('<'), end = line.lastIndexOf('>');
    String to = (start >= 0 && end > start + 1) ? line.substring(start + 1, end) : "";
    if (userService.userExists(to)) {
      session.getToAddresses().add(to);
      session.setState(SmtpSessionContext.State.RCPT_TO_RECEIVED);
      return "250 OK\r\n";
    } else {
      return "550 No such user here\r\n";
    }
  }

  public String handleData(SmtpSessionContext session) {
    if (session.getState() != SmtpSessionContext.State.RCPT_TO_RECEIVED) {
      return "503 Bad sequence of commands\r\n";
    }
    session.setState(SmtpSessionContext.State.DATA_RECEIVING);
    return "354 Start mail input; end with <CRLF>.<CRLF>\r\n";
  }

  public String handleDataReceiving(String line, SmtpSessionContext session) {
    if (".".equals(line)) {
      String mailData = session.getMailContent().toString();
      for (String recipient : session.getToAddresses()) {
        mailboxService.saveEmail(recipient, mailData);
      }
      String id = UUID.randomUUID().toString();
      session.resetTransaction();
      return "250 OK: queued as " + id + "\r\n";
    } else {
      session.getMailContent().append(line).append("\r\n");
      return null;
    }
  }

  public String handleRset(SmtpSessionContext session) {
    session.resetTransaction();
    return "250 OK\r\n";
  }

  public String handleQuit(SmtpSessionContext session) {
    session.setState(SmtpSessionContext.State.QUIT);
    return "221 Bye\r\n";
  }
}
