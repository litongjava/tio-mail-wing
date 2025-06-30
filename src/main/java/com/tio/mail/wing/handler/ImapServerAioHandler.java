package com.tio.mail.wing.handler;

import java.nio.ByteBuffer;

import com.litongjava.aio.Packet;
import com.litongjava.db.activerecord.ActiveRecordException;
import com.litongjava.jfinal.aop.Aop;
import com.litongjava.tio.core.ChannelContext;
import com.litongjava.tio.core.Tio;
import com.litongjava.tio.core.TioConfig;
import com.litongjava.tio.core.exception.LengthOverflowException;
import com.litongjava.tio.core.exception.TioDecodeException;
import com.litongjava.tio.core.utils.ByteBufferUtils;
import com.litongjava.tio.server.intf.ServerAioHandler;
import com.tio.mail.wing.packet.ImapPacket;
import com.tio.mail.wing.service.ImapFetchService;
import com.tio.mail.wing.service.ImapService;

import lombok.extern.slf4j.Slf4j;

@Slf4j
public class ImapServerAioHandler implements ServerAioHandler {

  private ImapService imapService = Aop.get(ImapService.class);

  @Override
  public Packet decode(ByteBuffer buffer, int limit, int position, int readableLength, ChannelContext ctx) throws TioDecodeException {
    String charset = ctx.getTioConfig().getCharset();
    String line = null;
    try {
      line = ByteBufferUtils.readLine(buffer, charset);
    } catch (LengthOverflowException e) {
      log.error("Line length overflow", e);
    }
    return line == null ? null : new ImapPacket(line);
  }

  @Override
  public ByteBuffer encode(Packet packet, TioConfig tioConfig, ChannelContext ctx) {
    String charset = ctx.getTioConfig().getCharset();
    ImapPacket imapPacket = (ImapPacket) packet;
    try {
      return ByteBuffer.wrap(imapPacket.getLine().getBytes(charset));
    } catch (Exception e) {
      log.error("Encoding error", e);
      return null;
    }
  }

  @Override
  public void handler(Packet packet, ChannelContext ctx) throws Exception {
    ImapPacket imapPacket = (ImapPacket) packet;
    String line = imapPacket.getLine().trim();
    ImapSessionContext session = (ImapSessionContext) ctx.get("sessionContext");
    String username = session.getUsername();
    if (username != null) {
      log.info("user {} <<< {}", username, line);
    } else {
      log.info("<<< {}", line);
    }

    if (session.getState() == ImapSessionContext.State.AUTH_WAIT_USERNAME || session.getState() == ImapSessionContext.State.AUTH_WAIT_PASSWORD) {
      String reply = imapService.handleAuthData(session, line);
      if (reply != null) {
        Tio.bSend(ctx, new ImapPacket(reply));
      }
      return;
    }

    String[] parts = line.split("\\s+", 3);
    String tag = parts[0];
    String command = parts.length > 1 ? parts[1].toUpperCase() : "";
    String args = parts.length > 2 ? parts[2] : "";

    String reply = null;
    try {
      switch (command) {
      case "CAPABILITY":
        reply = imapService.handleCapability(tag);
        break;
      case "ID":
        reply = imapService.handleId(tag);
        break;
      case "IDLE":
        reply = imapService.handleIdle();
        break;
      case "AUTHENTICATE":
        reply = imapService.handleAuthenticate(session, tag, args);
        break;
      case "LOGIN":
        reply = imapService.handleLogin(session, tag, args);
        break;
      case "LOGOUT":
        reply = imapService.handleLogout(session, tag);
        if (reply != null) {
          Tio.bSend(ctx, new ImapPacket(reply));
        }
        Tio.close(ctx, "logout");
        return;
      case "CLOSE":
        reply = imapService.handleClose(session, tag);
      case "LIST":
        reply = imapService.handleList(session, tag, args);
        break;
      case "LSUB":
        reply = imapService.handleList(session, tag, args);
        break;
      case "CREATE":
        reply = imapService.handleCreate(session, tag, args);
        break;
      case "SUBSCRIBE":
        reply = imapService.handleSubscribe(tag);
        break;
      case "SELECT":
        reply = imapService.handleSelect(session, tag, args);
        break;
      case "STATUS":
        // args 里是: "<mailbox>" "(UIDNEXT MESSAGES UNSEEN RECENT)"
        reply = imapService.handleStatus(session, tag, args);
        break;
      case "FETCH":
        // 传递 isUidCommand = false
        ImapFetchService imapFetchService = Aop.get(ImapFetchService.class);
        reply = imapFetchService.handleFetch(session, tag, args, false);
        break;
      case "STORE":
        // 传递 isUidCommand = false
        reply = imapService.handleStore(session, tag, args, false);
        break;
      case "UID":
        reply = imapService.handleUid(session, tag, args);
        break;
      case "NOOP":
        reply = tag + " OK NOOP";
      case "EXPUNGE":
        reply = imapService.handleExpunge(session, tag);
        break;
      default:
        reply = tag + " BAD Unknown or unimplemented command.\r\n";
      }
    } catch (Exception e) {
      reply = tag + " BAD Internal server error.\r\n";
      if (e instanceof ActiveRecordException) {
        ActiveRecordException ae = (ActiveRecordException) e;
        log.error("Error handling IMAP command:{},{},{}", line, ae.getSql(), ae.getParas(), e);
      } else {
        log.error("Error handling IMAP command: " + line, e);
      }
    }

    if (reply != null) {
      log.info(reply);
      Tio.bSend(ctx, new ImapPacket(reply));
    }
  }

}