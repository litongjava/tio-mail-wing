// src/main/java/com/tio/mail/wing/handler/SmtpServerAioHandler.java
package com.tio.mail.wing.handler;

import java.nio.ByteBuffer;
import java.util.Base64;
import java.util.UUID;

import com.litongjava.aio.Packet;
import com.litongjava.jfinal.aop.Aop;
import com.litongjava.tio.core.ChannelContext;
import com.litongjava.tio.core.Tio;
import com.litongjava.tio.core.TioConfig;
import com.litongjava.tio.core.exception.LengthOverflowException;
import com.litongjava.tio.core.exception.TioDecodeException;
import com.litongjava.tio.core.utils.ByteBufferUtils;
import com.litongjava.tio.server.intf.ServerAioHandler;
import com.tio.mail.wing.packet.SmtpPacket;
import com.tio.mail.wing.service.MailboxService;
import com.tio.mail.wing.service.MwUserService;

import lombok.extern.slf4j.Slf4j;

@Slf4j
public class SmtpServerAioHandler implements ServerAioHandler {

  private MwUserService userService = Aop.get(MwUserService.class);
  private MailboxService mailboxService = Aop.get(MailboxService.class);
  private static final String CHARSET = "UTF-8";

  @Override
  public Packet decode(ByteBuffer buffer, int limit, int position, int readableLength, ChannelContext channelContext) throws TioDecodeException {
    String line = null;
    try {
      line = ByteBufferUtils.readLine(buffer, CHARSET);
    } catch (LengthOverflowException e) {
      e.printStackTrace();
    }
    if (line == null) {
      return null;
    }
    return new SmtpPacket(line);
  }

  @Override
  public ByteBuffer encode(Packet packet, TioConfig tioConfig, ChannelContext channelContext) {
    SmtpPacket smtpPacket = (SmtpPacket) packet;
    String line = smtpPacket.getLine();
    try {
      return ByteBuffer.wrap(line.getBytes(CHARSET));
    } catch (Exception e) {
      log.error("Encoding error", e);
      return null;
    }
  }

  @Override
  public void handler(Packet packet, ChannelContext channelContext) throws Exception {
    SmtpPacket smtpPacket = (SmtpPacket) packet;
    String line = smtpPacket.getLine().trim();
    log.info("SMTP <<< {}", line);

    SmtpSessionContext session = (SmtpSessionContext) channelContext.get("sessionContext");

    // 特殊处理：DATA 状态
    if (session.getState() == SmtpSessionContext.State.DATA_RECEIVING) {
      handleDataReceiving(line, channelContext, session);
      return;
    }

    String[] parts = line.split("\\s+", 2);
    String command = parts[0].toUpperCase();

    switch (command) {
    case "HELO":
    case "EHLO":
      handleEhlo(parts, channelContext, session);
      break;
    case "AUTH":
      handleAuth(parts, channelContext, session);
      break;
    case "MAIL":
      handleMail(line, channelContext, session);
      break;
    case "RCPT":
      handleRcpt(line, channelContext, session);
      break;
    case "DATA":
      handleData(channelContext, session);
      break;
    case "QUIT":
      handleQuit(channelContext, session);
      break;
    case "RSET":
      handleRset(channelContext, session);
      break;
    default:
      // 处理认证过程中的 Base64 数据
      if (session.getState() == SmtpSessionContext.State.AUTH_WAIT_USERNAME || session.getState() == SmtpSessionContext.State.AUTH_WAIT_PASSWORD) {
        handleAuthData(line, channelContext, session);
      } else {
        SmtpSessionContext.sendResponse(channelContext, 500, "Command not recognized");
      }
    }
  }

  private void handleEhlo(String[] parts, ChannelContext ctx, SmtpSessionContext session) {
    if (session.getState() != SmtpSessionContext.State.CONNECTED) {
      SmtpSessionContext.sendResponse(ctx, 503, "Bad sequence of commands");
      return;
    }
    String domain = parts.length > 1 ? parts[1] : "unknown";
    // EHLO 的响应是多行的
    Tio.send(ctx, new SmtpPacket("250-tio-mail-wing says hello to " + domain + "\r\n"));
    Tio.send(ctx, new SmtpPacket("250 AUTH LOGIN\r\n")); // 声明支持 AUTH LOGIN
    session.setState(SmtpSessionContext.State.GREETED);
  }

  private void handleAuth(String[] parts, ChannelContext ctx, SmtpSessionContext session) {
    if (session.getState() != SmtpSessionContext.State.GREETED) {
      SmtpSessionContext.sendResponse(ctx, 503, "Bad sequence of commands");
      return;
    }
    if (parts.length > 1 && "LOGIN".equalsIgnoreCase(parts[1])) {
      session.setState(SmtpSessionContext.State.AUTH_WAIT_USERNAME);
      SmtpSessionContext.sendResponse(ctx, 334, Base64.getEncoder().encodeToString("Username:".getBytes()));
    } else {
      SmtpSessionContext.sendResponse(ctx, 504, "Authentication mechanism not supported");
    }
  }

  private void handleAuthData(String line, ChannelContext ctx, SmtpSessionContext session) {
    try {
      String decoded = new String(Base64.getDecoder().decode(line), CHARSET);
      if (session.getState() == SmtpSessionContext.State.AUTH_WAIT_USERNAME) {
        session.setUsername(decoded);
        session.setState(SmtpSessionContext.State.AUTH_WAIT_PASSWORD);
        SmtpSessionContext.sendResponse(ctx, 334, Base64.getEncoder().encodeToString("Password:".getBytes()));
      } else if (session.getState() == SmtpSessionContext.State.AUTH_WAIT_PASSWORD) {
        if (userService.authenticate(session.getUsername(), decoded)) {
          session.setAuthenticated(true);
          session.setState(SmtpSessionContext.State.GREETED);
          SmtpSessionContext.sendResponse(ctx, 235, "Authentication successful");
        } else {
          session.resetTransaction();
          session.setState(SmtpSessionContext.State.GREETED);
          SmtpSessionContext.sendResponse(ctx, 535, "Authentication failed");
        }
      }
    } catch (Exception e) {
      session.resetTransaction();
      session.setState(SmtpSessionContext.State.GREETED);
      SmtpSessionContext.sendResponse(ctx, 501, "Invalid base64 data");
    }
  }

  private void handleMail(String line, ChannelContext ctx, SmtpSessionContext session) {
    if (session.getState() != SmtpSessionContext.State.GREETED || !session.isAuthenticated()) {
      SmtpSessionContext.sendResponse(ctx, 503, "Bad sequence of commands or not authenticated");
      return;
    }
    // 简单解析 MAIL FROM:<address>
    String from = line.substring(line.indexOf('<') + 1, line.lastIndexOf('>'));
    if (from.isEmpty()) {
      SmtpSessionContext.sendResponse(ctx, 501, "Invalid address");
      return;
    }
    session.setFromAddress(from);
    session.setState(SmtpSessionContext.State.MAIL_FROM_RECEIVED);
    SmtpSessionContext.sendResponse(ctx, 250, "OK");
  }

  private void handleRcpt(String line, ChannelContext ctx, SmtpSessionContext session) {
    if (session.getState() != SmtpSessionContext.State.MAIL_FROM_RECEIVED && session.getState() != SmtpSessionContext.State.RCPT_TO_RECEIVED) {
      SmtpSessionContext.sendResponse(ctx, 503, "Bad sequence of commands");
      return;
    }
    String to = line.substring(line.indexOf('<') + 1, line.lastIndexOf('>'));
    // 检查收件人是否是本域用户
    if (userService.userExists(to)) {
      session.getToAddresses().add(to);
      session.setState(SmtpSessionContext.State.RCPT_TO_RECEIVED);
      SmtpSessionContext.sendResponse(ctx, 250, "OK");
    } else {
      SmtpSessionContext.sendResponse(ctx, 550, "No such user here");
    }
  }

  private void handleData(ChannelContext ctx, SmtpSessionContext session) {
    if (session.getState() != SmtpSessionContext.State.RCPT_TO_RECEIVED) {
      SmtpSessionContext.sendResponse(ctx, 503, "Bad sequence of commands");
      return;
    }
    session.setState(SmtpSessionContext.State.DATA_RECEIVING);
    SmtpSessionContext.sendResponse(ctx, 354, "Start mail input; end with <CRLF>.<CRLF>");
  }

  private void handleDataReceiving(String line, ChannelContext ctx, SmtpSessionContext session) {
    if (line.equals(".")) {
      // 邮件内容接收完毕
      String mailData = session.getMailContent().toString();
      for (String recipient : session.getToAddresses()) {
        mailboxService.saveEmail(recipient, mailData);
      }
      SmtpSessionContext.sendResponse(ctx, 250, "OK: queued as " + UUID.randomUUID().toString());
      // 重置事务，准备接收下一封邮件
      session.resetTransaction();
    } else {
      // 累加邮件内容
      session.getMailContent().append(line).append("\r\n");
    }
  }

  private void handleRset(ChannelContext ctx, SmtpSessionContext session) {
    session.resetTransaction();
    SmtpSessionContext.sendResponse(ctx, 250, "OK");
  }

  private void handleQuit(ChannelContext ctx, SmtpSessionContext session) {
    session.setState(SmtpSessionContext.State.QUIT);
    SmtpSessionContext.sendResponse(ctx, 221, "Bye");
    Tio.close(ctx, "Client requested QUIT");
  }
}