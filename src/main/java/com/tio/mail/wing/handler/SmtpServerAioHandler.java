package com.tio.mail.wing.handler;

import java.nio.ByteBuffer;

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
import com.tio.mail.wing.service.SmtpService;

import lombok.extern.slf4j.Slf4j;

@Slf4j
public class SmtpServerAioHandler implements ServerAioHandler {

  private SmtpService smtpService = Aop.get(SmtpService.class);

  @Override
  public Packet decode(ByteBuffer buffer, int limit, int position, int readableLength, ChannelContext channelContext) throws TioDecodeException {

    String charset = channelContext.getTioConfig().getCharset();
    String line = null;
    try {
      line = ByteBufferUtils.readLine(buffer, charset);
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
    String charset = tioConfig.getCharset();
    SmtpPacket smtpPacket = (SmtpPacket) packet;
    String line = smtpPacket.getLine();
    try {
      return ByteBuffer.wrap(line.getBytes(charset));
    } catch (Exception e) {
      log.error("Encoding error", e);
      return null;
    }
  }

  @Override
  public void handler(Packet packet, ChannelContext ctx) throws Exception {
    SmtpPacket smtpPacket = (SmtpPacket) packet;
    String line = smtpPacket.getLine().trim();
    log.info("SMTP <<< {}", line);

    SmtpSessionContext session = (SmtpSessionContext) ctx.get("sessionContext");

    // 特殊处理：DATA 状态
    if (session.getState() == SmtpSessionContext.State.DATA_RECEIVING) {
      String reply = smtpService.handleDataReceiving(line, session);
      if (reply != null) {
        Tio.send(ctx, new SmtpPacket(reply));
      }
      return;
    }

    String[] parts = line.split("\\s+", 2);
    String command = parts[0].toUpperCase();

    String reply = null;
    switch (command) {
    case "HELO":
    case "EHLO":
      reply = smtpService.handleEhlo(parts, session);
      break;
    case "AUTH":
      reply = smtpService.handleAuth(parts, session);
      break;
    case "MAIL":
      reply = smtpService.handleMail(line, session);
      break;
    case "RCPT":
      reply = smtpService.handleRcpt(line, session);
      break;
    case "DATA":
      reply = smtpService.handleData(session);
      break;
    case "QUIT":
      reply = smtpService.handleQuit(session);
      if (reply != null) {
        Tio.send(ctx, new SmtpPacket(reply));
      }
      Tio.close(ctx, "quit");
      return;
    case "RSET":
      reply = smtpService.handleRset(session);
      break;
    default:
      // 处理认证过程中的 Base64 数据
      if (session.getState() == SmtpSessionContext.State.AUTH_WAIT_USERNAME || session.getState() == SmtpSessionContext.State.AUTH_WAIT_PASSWORD) {
        reply = smtpService.handleAuthData(line, session);
      } else {
        reply = 500 + " " + "Command not recognized\r\n";
      }
    }

    if (reply != null) {
      Tio.send(ctx, new SmtpPacket(reply));
    }
  }

}