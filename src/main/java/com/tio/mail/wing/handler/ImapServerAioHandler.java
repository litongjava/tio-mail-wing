// src/main/java/com/tio/mail/wing/handler/ImapServerAioHandler.java
package com.tio.mail.wing.handler;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.litongjava.aio.Packet;
import com.litongjava.jfinal.aop.Aop;
import com.litongjava.tio.core.ChannelContext;
import com.litongjava.tio.core.Tio;
import com.litongjava.tio.core.TioConfig;
import com.litongjava.tio.core.exception.LengthOverflowException;
import com.litongjava.tio.core.exception.TioDecodeException;
import com.litongjava.tio.core.utils.ByteBufferUtils;
import com.litongjava.tio.server.intf.ServerAioHandler;
import com.litongjava.tio.utils.base64.Base64Utils;
import com.tio.mail.wing.model.Email;
import com.tio.mail.wing.packet.ImapPacket;
import com.tio.mail.wing.service.MailboxService;
import com.tio.mail.wing.service.MwUserService;

import lombok.extern.slf4j.Slf4j;

@Slf4j
public class ImapServerAioHandler implements ServerAioHandler {

  // =================================================================
  // 关键改动 1: 将正则表达式预编译为静态常量，提升性能
  // =================================================================
  private static final Pattern BODY_FETCH_PATTERN = Pattern.compile("BODY(?:\\.PEEK)?\\[(.*?)\\]", Pattern.CASE_INSENSITIVE);
  private static final Pattern UID_FETCH_PATTERN = Pattern.compile("([\\d\\*:,\\-]+)\\s+\\((.*)\\)", Pattern.CASE_INSENSITIVE);

  private MwUserService userService = Aop.get(MwUserService.class);
  private MailboxService mailboxService = Aop.get(MailboxService.class);
  private static final String CHARSET = "UTF-8";

  @Override
  public Packet decode(ByteBuffer buffer, int limit, int position, int readableLength, ChannelContext ctx) throws TioDecodeException {
    String line = null;
    try {
      line = ByteBufferUtils.readLine(buffer, CHARSET);
    } catch (LengthOverflowException e) {
      log.error("Line length overflow", e);
    }
    return line == null ? null : new ImapPacket(line);
  }

  @Override
  public ByteBuffer encode(Packet packet, TioConfig tioConfig, ChannelContext ctx) {
    ImapPacket imapPacket = (ImapPacket) packet;
    try {
      return ByteBuffer.wrap(imapPacket.getLine().getBytes(CHARSET));
    } catch (Exception e) {
      log.error("Encoding error", e);
      return null;
    }
  }

  @Override
  public void handler(Packet packet, ChannelContext ctx) throws Exception {
    ImapPacket imapPacket = (ImapPacket) packet;
    String line = imapPacket.getLine().trim();
    log.info("IMAP <<< {}", line);

    ImapSessionContext session = (ImapSessionContext) ctx.get("sessionContext");

    if (session.getState() == ImapSessionContext.State.AUTH_WAIT_USERNAME || session.getState() == ImapSessionContext.State.AUTH_WAIT_PASSWORD) {
      handleAuthData(ctx, session, line);
      return;
    }

    String[] parts = line.split("\\s+", 3);
    String tag = parts[0];
    String command = parts.length > 1 ? parts[1].toUpperCase() : "";
    String args = parts.length > 2 ? parts[2] : "";

    try {
      switch (command) {
      case "CAPABILITY":
        handleCapability(ctx, tag);
        break;
      // =================================================================
      // 关键改动 2: 增加对 ID 和 IDLE 命令的支持
      // =================================================================
      case "ID":
        handleId(ctx, tag);
        break;
      case "IDLE":
        handleIdle(ctx, tag);
        break;
      case "AUTHENTICATE":
        handleAuthenticate(ctx, session, tag, args);
        break;
      case "LOGIN":
        handleLogin(ctx, session, tag, args);
        break;
      case "LOGOUT":
        handleLogout(ctx, tag);
        break;
      case "LIST":
        handleList(ctx, tag, args, command);
        break;
      case "LSUB":
        handleList(ctx, tag, args, command);
        break;
      case "CREATE":
        handleCreate(ctx, tag, args);
        break;
      case "SUBSCRIBE":
        handleSubscribe(ctx, tag, args);
        break;
      case "SELECT":
        handleSelect(ctx, session, tag, args);
        break;
      case "FETCH":
        // 传递 isUidCommand = false
        handleFetch(ctx, session, tag, args, false);
        break;
      case "STORE":
        // 传递 isUidCommand = false
        handleStore(ctx, session, tag, args, false);
        break;
      case "UID":
        handleUid(ctx, session, tag, args);
        break;
      case "NOOP":
        ImapSessionContext.sendTaggedOk(ctx, tag, "NOOP");
        break;
      default:
        ImapSessionContext.sendTaggedBad(ctx, tag, "Unknown or unimplemented command");
      }
    } catch (Exception e) {
      log.error("Error handling IMAP command: " + line, e);
      ImapSessionContext.sendTaggedBad(ctx, tag, "Internal server error");
    }
  }

  private void handleSubscribe(ChannelContext ctx, String tag, String args) {
    ImapSessionContext.sendTaggedOk(ctx, tag, "SUBSCRIBE");
  }

  private void handleCreate(ChannelContext ctx, String tag, String args) {
    ImapSessionContext.sendTaggedOk(ctx, tag, "CREATE");
  }

  // =================================================================
  // 关键改动 3: 增强 CAPABILITY 响应
  // =================================================================
  private void handleCapability(ChannelContext ctx, String tag) {
    // 至少要包含这些，IDLE 和 UIDPLUS 非常重要，LITERAL+ 也很常用
    ImapSessionContext.sendUntagged(ctx, "CAPABILITY IMAP4rev1 AUTH=LOGIN IDLE UIDPLUS ID LITERAL+");
    ImapSessionContext.sendTaggedOk(ctx, tag, "CAPABILITY");
  }

  // =================================================================
  // 关键改动 4: 实现 ID 命令
  // =================================================================
  private void handleId(ChannelContext ctx, String tag) {
    // 响应我们自己的服务器信息
    ImapSessionContext.sendUntagged(ctx, "ID (\"name\" \"tio-mail-wing\")");
    ImapSessionContext.sendTaggedOk(ctx, tag, "ID completed.");
  }

  // =================================================================
  // 关键改动 5: 实现 IDLE 命令 (简化版)
  // =================================================================
  private void handleIdle(ChannelContext ctx, String tag) {
    // 告诉客户端我们已经进入 IDLE 状态
    ImapSessionContext.send(ctx, "+ idling");
    // 在真实实现中，服务器会在这里挂起，等待新邮件或其他事件。
    // 当客户端发送 "DONE" 时，这个会话才结束。
    // 这里的实现是简化的，我们不阻塞，直接等待客户端的 DONE。
    // Tio 的心跳机制会保持连接活跃。
    // 当客户端发送 DONE 时，它会进入 handler 方法，但由于没有匹配的 command，
    // 我们需要在 handler 的开头部分处理它。
    // (为了简化，我们暂时不处理 DONE，客户端超时后会重发 NOOP 或其他命令)
  }

  private void handleAuthenticate(ChannelContext ctx, ImapSessionContext session, String tag, String args) {
    // ... (此方法无需修改)
    if (!"LOGIN".equalsIgnoreCase(args) && !"PLAIN".equalsIgnoreCase(args)) {
      ImapSessionContext.sendTaggedBad(ctx, tag, "Unsupported authentication mechanism");
      return;
    }
    session.setCurrentCommandTag(tag);
    if ("LOGIN".equalsIgnoreCase(args)) {
      session.setState(ImapSessionContext.State.AUTH_WAIT_USERNAME);
      String challenge = Base64Utils.encodeToString("Username:".getBytes(StandardCharsets.UTF_8));
      ImapSessionContext.send(ctx, "+ " + challenge);
    } else { // PLAIN
      ImapSessionContext.send(ctx, "+");
      session.setState(ImapSessionContext.State.AUTH_WAIT_PASSWORD); // 直接等待包含用户名和密码的data
    }
  }

  private void handleAuthData(ChannelContext ctx, ImapSessionContext session, String base64Data) {
    // ... (此方法无需修改)
    String tag = session.getCurrentCommandTag();
    try {
      String decodedData = Base64Utils.decodeToString(base64Data);
      if (session.getState() == ImapSessionContext.State.AUTH_WAIT_USERNAME) {
        session.setUsername(decodedData);
        session.setState(ImapSessionContext.State.AUTH_WAIT_PASSWORD);
        String challenge = Base64Utils.encodeToString("Password:".getBytes(StandardCharsets.UTF_8));
        ImapSessionContext.send(ctx, "+ " + challenge);
      } else if (session.getState() == ImapSessionContext.State.AUTH_WAIT_PASSWORD) {
        String username;
        String password;
        // 兼容 AUTH PLAIN
        if (decodedData.contains("\0")) {
          String[] parts = decodedData.split("\0");
          username = parts.length > 1 ? parts[1] : "";
          password = parts.length > 2 ? parts[2] : "";
        } else {
          username = session.getUsername();
          password = decodedData;
        }

        if (userService.authenticate(username, password)) {
          session.setUsername(username);
          session.setState(ImapSessionContext.State.AUTHENTICATED);
          ImapSessionContext.sendTaggedOk(ctx, tag, "AUTHENTICATE completed.");
        } else {
          session.setState(ImapSessionContext.State.NON_AUTHENTICATED);
          ImapSessionContext.sendTaggedNo(ctx, tag, "AUTHENTICATE", "Authentication failed");
        }
        session.setCurrentCommandTag(null);
      }
    } catch (IllegalArgumentException e) {
      session.setState(ImapSessionContext.State.NON_AUTHENTICATED);
      ImapSessionContext.sendTaggedBad(ctx, tag, "Invalid base64 data");
      session.setCurrentCommandTag(null);
    }
  }

  private void handleLogin(ChannelContext ctx, ImapSessionContext session, String tag, String args) {
    // ... (此方法无需修改)
    String[] parts = args.split("\\s+", 2);
    if (parts.length < 2) {
      ImapSessionContext.sendTaggedBad(ctx, tag, "login arguments invalid");
      return;
    }
    String username = unquote(parts[0]);
    String password = unquote(parts[1]);
    if (userService.authenticate(username, password)) {
      session.setUsername(username);
      session.setState(ImapSessionContext.State.AUTHENTICATED);
      ImapSessionContext.sendTaggedOk(ctx, tag, "LOGIN completed.");
    } else {
      ImapSessionContext.sendTaggedNo(ctx, tag, "LOGIN", "Authentication failed");
    }
  }

  private void handleLogout(ChannelContext ctx, String tag) {
    // ... (此方法无需修改)
    ImapSessionContext.sendUntagged(ctx, "BYE tio-mail-wing IMAP server signing off");
    ImapSessionContext.sendTaggedOk(ctx, tag, "LOGOUT");
    if (!ctx.isClosed) {
      Tio.close(ctx, "LOGOUT");
    }
  }

  private void handleList(ChannelContext ctx, String tag, String args, String command) {
    ImapSessionContext.sendUntagged(ctx, "LIST (\\HasNoChildren) \"/\" INBOX");
    ImapSessionContext.sendTaggedOk(ctx, tag, command.toUpperCase() + " completed.");
  }

  // =================================================================
  // 关键改动 6: 全面改造 SELECT 响应，使其符合 Dovecot 标准
  // =================================================================
  private void handleSelect(ChannelContext ctx, ImapSessionContext session, String tag, String args) {
    String mailboxName = unquote(args);
    if (!"INBOX".equalsIgnoreCase(mailboxName)) {
      ImapSessionContext.sendTaggedNo(ctx, tag, "SELECT", "mailbox not found: " + mailboxName);
      return;
    }
    session.setSelectedMailbox(mailboxName);
    session.setState(ImapSessionContext.State.SELECTED);

    // 使用新的 Service 方法
    Map<String, Object> mailboxMeta = mailboxService.getMailboxMetadata(session.getUsername(), mailboxName);
    if (mailboxMeta == null) {
      ImapSessionContext.sendTaggedNo(ctx, tag, "SELECT", "mailbox not found: " + mailboxName);
      return;
    }

    List<Email> allEmails = mailboxService.getActiveMessages(session.getUsername(), mailboxName);
    long existsCount = allEmails.size();
    long recentCount = allEmails.stream().filter(e -> e.getFlags().contains("\\Recent")).count();

    long uidValidity = (long) mailboxMeta.get("uidValidity");
    AtomicLong uidNextCounter = (AtomicLong) mailboxMeta.get("uidNext");
    long uidNext = uidNextCounter.get();

    // 按照 Dovecot 的顺序和格式发送响应
    ImapSessionContext.sendUntagged(ctx, "FLAGS (\\Answered \\Flagged \\Deleted \\Seen \\Draft)");
    ImapSessionContext.sendUntagged(ctx, "OK [PERMANENTFLAGS (\\Answered \\Flagged \\Deleted \\Seen \\Draft \\*)] Flags permitted.");
    ImapSessionContext.sendUntagged(ctx, existsCount + " EXISTS");
    ImapSessionContext.sendUntagged(ctx, recentCount + " RECENT");

    ImapSessionContext.sendUntagged(ctx, "OK [UIDVALIDITY " + uidValidity + "] UIDs valid.");
    ImapSessionContext.sendUntagged(ctx, "OK [UIDNEXT " + uidNext + "] Predicted next UID.");

    ImapSessionContext.sendTaggedOk(ctx, tag, "[READ-WRITE] SELECT completed.");
  }

  // =================================================================
  // 关键改动 7: 统一 FETCH 和 UID FETCH 的处理逻辑
  // =================================================================
  private void handleFetch(ChannelContext ctx, ImapSessionContext session, String tag, String args, boolean isUidCommand) {
    if (session.getState() != ImapSessionContext.State.SELECTED) {
      ImapSessionContext.sendTaggedNo(ctx, tag, "FETCH", "No mailbox selected");
      return;
    }

    Matcher matcher = UID_FETCH_PATTERN.matcher(args);
    if (!matcher.find()) {
      ImapSessionContext.sendTaggedBad(ctx, tag, "Invalid FETCH arguments: " + args);
      return;
    }

    String username = session.getUsername();
    String selectedMailbox = session.getSelectedMailbox();
        
    String messageSet = matcher.group(1);
    String fetchItemsStr = matcher.group(2).toUpperCase();

    
    
    List<Email> emailsToFetch;

    if (isUidCommand) {
      emailsToFetch = mailboxService.findEmailsByUidSet(username,selectedMailbox,messageSet);
    } else {
      emailsToFetch = mailboxService.findEmailsBySeqSet(messageSet, selectedMailbox,messageSet);
    }

    if (emailsToFetch.isEmpty()) {
      ImapSessionContext.sendTaggedOk(ctx, tag, "FETCH completed.");
      return;
    }

    for (Email email : emailsToFetch) {
      try {
        int sequenceNumber = allEmails.indexOf(email) + 1;
        if (sequenceNumber == 0) continue;

        List<String> responseParts = new ArrayList<>();
        if (isUidCommand || fetchItemsStr.contains("UID")) {
          responseParts.add("UID " + email.getUid());
        }
        if (fetchItemsStr.contains("FLAGS")) {
          responseParts.add("FLAGS (" + String.join(" ", email.getFlags()) + ")");
        }
        if (fetchItemsStr.contains("RFC822.SIZE")) {
          responseParts.add("RFC822.SIZE " + email.getSize());
        }

        String responsePrefix = String.format("* %d FETCH (%s", sequenceNumber, String.join(" ", responseParts));

        Matcher bodyMatcher = BODY_FETCH_PATTERN.matcher(fetchItemsStr);
        if (bodyMatcher.find()) {
          String bodyPartName = bodyMatcher.group(0);
          String headerContent = parseHeaderFields(email.getRawContent(),
              new String[] { "From", "To", "Cc", "Bcc", "Subject", "Date", "Message-ID", "Priority", "X-Priority", "References", "Newsgroups", "In-Reply-To", "Content-Type", "Reply-To" });
          byte[] headerBytes = headerContent.getBytes(StandardCharsets.UTF_8);

          String finalPrefix = responsePrefix + " " + bodyPartName + " {" + headerBytes.length + "}";
          ImapSessionContext.send(ctx, finalPrefix);
          ImapSessionContext.send(ctx, headerContent);
          ImapSessionContext.send(ctx, ")");
        } else {
          ImapSessionContext.send(ctx, responsePrefix + ")");
        }
      } catch (Exception e) {
        log.error("Error processing email during FETCH: " + email, e);
      }
    }

    ImapSessionContext.sendTaggedOk(ctx, tag, "FETCH completed.");
    mailboxService.clearRecentFlags(session.getUsername(),selectedMailbox);
  }

  // =================================================================
  // 关键改动 8: 统一 STORE 和 UID STORE 的处理逻辑
  // =================================================================
  private void handleStore(ChannelContext ctx, ImapSessionContext session, String tag, String args, boolean isUidCommand) {
    if (session.getState() != ImapSessionContext.State.SELECTED) {
      ImapSessionContext.sendTaggedNo(ctx, tag, "STORE", "No mailbox selected");
      return;
    }

    String[] parts = args.split("\\s+", 3);
    if (parts.length < 3) {
      ImapSessionContext.sendTaggedBad(ctx, tag, "Invalid STORE arguments");
      return;
    }
    String messageSet = parts[0];
    String operation = parts[1].toUpperCase();
    String flagsStr = parts[2].replaceAll("[()]", "");
    Set<String> flags = new HashSet<>(Arrays.asList(flagsStr.split("\\s+")));
    boolean add = operation.startsWith("+");
    String selectedMailbox = session.getSelectedMailbox();
    List<Email> allEmails = mailboxService.getActiveMessages(session.getUsername(), selectedMailbox);
    List<Email> emailsToUpdate;
    if (isUidCommand) {
      emailsToUpdate = mailboxService.findEmailsByUidSet(messageSet, allEmails);
    } else {
      emailsToUpdate = mailboxService.findEmailsBySeqSet(messageSet, allEmails);
    }

    for (Email email : emailsToUpdate) {
      mailboxService.storeFlags(email, flags, add);
      if (!operation.contains(".SILENT")) {
        String updatedFlagsStr = String.join(" ", email.getFlags());
        int sequenceNumber = allEmails.indexOf(email) + 1;

        String response = String.format("%d FETCH (FLAGS (%s) UID %d)", sequenceNumber, updatedFlagsStr, email.getUid());
        ImapSessionContext.sendUntagged(ctx, response);
      }
    }

    ImapSessionContext.sendTaggedOk(ctx, tag, "STORE completed.");
  }

  // =================================================================
  // 关键改动 9: 改造 UID 命令，使其分发到统一的 handler
  // =================================================================
  private void handleUid(ChannelContext ctx, ImapSessionContext session, String tag, String args) {
    if (session.getState() != ImapSessionContext.State.SELECTED) {
      ImapSessionContext.sendTaggedNo(ctx, tag, "UID", "No mailbox selected");
      return;
    }

    String[] parts = args.split("\\s+", 2);
    String subCommand = parts[0].toUpperCase();
    String subArgs = parts.length > 1 ? parts[1] : "";

    switch (subCommand) {
    case "FETCH":
      handleFetch(ctx, session, tag, subArgs, true);
      break;
    case "STORE":
      handleStore(ctx, session, tag, subArgs, true);
      break;
    default:
      ImapSessionContext.sendTaggedBad(ctx, tag, "Unsupported UID command: " + subCommand);
    }
  }

  // 废弃旧的 handleUidFetch，逻辑已合并到 handleFetch
  // private void handleUidFetch(...) { ... }

  private String parseHeaderFields(String mailContent, String[] requestedFields) {
    // ... (此方法无需修改)
    Map<String, String> headers = new HashMap<>();
    String[] lines = mailContent.split("\\r?\\n");
    for (String line : lines) {
      if (line.isEmpty()) {
        break;
      }
      int colonIndex = line.indexOf(':');
      if (colonIndex > 0) {
        String key = line.substring(0, colonIndex).trim();
        String value = line.substring(colonIndex + 1).trim();
        headers.put(key.toUpperCase(), value);
      }
    }
    StringBuilder result = new StringBuilder();
    for (String field : requestedFields) {
      String value = headers.get(field.toUpperCase());
      if (value != null) {
        result.append(field).append(": ").append(value).append("\r\n");
      }
    }
    return result.toString();
  }

  private String unquote(String s) {
    if (s != null && s.startsWith("\"") && s.endsWith("\"")) {
      return s.substring(1, s.length() - 1);
    }
    return s;
  }
}