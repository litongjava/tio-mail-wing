package com.tio.mail.wing.service;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.litongjava.db.activerecord.Row;
import com.litongjava.jfinal.aop.Aop;
import com.litongjava.tio.utils.base64.Base64Utils;
import com.tio.mail.wing.handler.ImapSessionContext;
import com.tio.mail.wing.model.Email;

public class ImapService {
  private static final Pattern BODY_FETCH_PATTERN = Pattern.compile("BODY(?:\\.PEEK)?\\[(.*?)\\]", Pattern.CASE_INSENSITIVE);
  private static final Pattern UID_FETCH_PATTERN = Pattern.compile("([\\d\\*:,\\-]+)\\s+\\((.*)\\)", Pattern.CASE_INSENSITIVE);
  public static final String[] EMAIL_HEADER_FIELDS = new String[] { "From", "To", "Cc", "Bcc", "Subject", "Date", "Message-ID", "Priority", "X-Priority", "References", "Newsgroups", "In-Reply-To",
      "Content-Type", "Reply-To" };

  private final MwUserService userService = Aop.get(MwUserService.class);
  private final MailboxService mailboxService = Aop.get(MailboxService.class);

  /**
   * EXPUNGE: 逻辑删除并通知客户端
   */
  public String handleExpunge(ImapSessionContext session, String tag) {
    String username = session.getUsername();
    String mailbox = session.getSelectedMailbox();
    StringBuilder sb = new StringBuilder();

    // 查询所有待 EXPUNGE 的 seq_num
    List<Integer> seqs = mailboxService.getExpungeSeqNums(username, mailbox);
    // 逻辑删除数据
    mailboxService.expunge(username, mailbox);

    // 通知客户端
    for (int seq : seqs) {
      sb.append("* ").append(seq).append(" EXPUNGE").append("\r\n");
    }
    sb.append(tag).append(" OK EXPUNGE completed.").append("\r\n");
    return sb.toString();
  }

  /**
   * CREATE: 在数据库中创建新邮箱目录
   */
  public String handleCreate(ImapSessionContext session, String tag, String args) {
    String mailboxName = unquote(args);
    mailboxService.createMailbox(session.getUsername(), mailboxName);
    return tag + " OK CREATE completed." + "\r\n";
  }

  /**
   * LIST: 从数据库中获取所有用户邮箱目录
   */
  public String handleList(ImapSessionContext session, String tag, String args) {
    String username = session.getUsername();
    List<String> mailboxes = mailboxService.listMailboxes(username);
    StringBuilder sb = new StringBuilder();
    for (String m : mailboxes) {
      sb.append("* LIST (\\HasNoChildren) \"/\" ").append(m).append("\r\n");
    }
    sb.append(tag).append(" OK LIST completed.").append("\r\n");
    return sb.toString();
  }

  public String handleSubscribe(String tag) {
    return tag + " OK SUBSCRIBE" + "\r\n";
  }

  public String handleCapability(String tag) {
    StringBuilder sb = new StringBuilder();
    sb.append("* CAPABILITY IMAP4rev1 AUTH=LOGIN IDLE UIDPLUS ID LITERAL+").append("\r\n");
    sb.append(tag).append(" OK CAPABILITY").append("\r\n");
    return sb.toString();
  }

  public String handleId(String tag) {
    StringBuilder sb = new StringBuilder();
    sb.append("* ID (\"name\" \"tio-mail-wing\")").append("\r\n");
    sb.append(tag).append(" OK ID completed.").append("\r\n");
    return sb.toString();
  }

  public String handleIdle() {
    return "+ idling" + "\r\n";
  }

  public String handleAuthenticate(ImapSessionContext session, String tag, String mech) {
    StringBuilder sb = new StringBuilder();
    if (!"LOGIN".equalsIgnoreCase(mech) && !"PLAIN".equalsIgnoreCase(mech)) {
      sb.append(tag).append(" BAD Unsupported authentication mechanism").append("\r\n");
      return sb.toString();
    }
    session.setCurrentCommandTag(tag);
    if ("LOGIN".equalsIgnoreCase(mech)) {
      session.setState(ImapSessionContext.State.AUTH_WAIT_USERNAME);
      String chal = Base64Utils.encodeToString("Username:".getBytes(StandardCharsets.UTF_8));
      sb.append("+ ").append(chal).append("\r\n");
    } else {
      session.setState(ImapSessionContext.State.AUTH_WAIT_PASSWORD);
      sb.append("+ ").append("\r\n");
    }
    return sb.toString();
  }

  public String handleAuthData(ImapSessionContext session, String data) {
    String tag = session.getCurrentCommandTag();
    StringBuilder sb = new StringBuilder();
    try {
      String decoded = Base64Utils.decodeToString(data);
      if (session.getState() == ImapSessionContext.State.AUTH_WAIT_USERNAME) {
        session.setUsername(decoded);
        session.setState(ImapSessionContext.State.AUTH_WAIT_PASSWORD);
        String chal = Base64Utils.encodeToString("Password:".getBytes(StandardCharsets.UTF_8));
        sb.append("+ ").append(chal).append("\r\n");
      } else if (session.getState() == ImapSessionContext.State.AUTH_WAIT_PASSWORD) {
        String user, pass;
        if (decoded.contains("\0")) {
          String[] parts = decoded.split("\0");
          user = parts.length > 1 ? parts[1] : "";
          pass = parts.length > 2 ? parts[2] : "";
        } else {
          user = session.getUsername();
          pass = decoded;
        }
        if (userService.authenticate(user, pass)) {
          session.setUsername(user);
          session.setState(ImapSessionContext.State.AUTHENTICATED);
          sb.append(tag).append(" OK AUTHENTICATE completed.").append("\r\n");
        } else {
          session.setState(ImapSessionContext.State.NON_AUTHENTICATED);
          sb.append(tag).append(" NO AUTHENTICATE failed: Authentication failed").append("\r\n");
        }
        session.setCurrentCommandTag(null);
      }
    } catch (IllegalArgumentException e) {
      session.setState(ImapSessionContext.State.NON_AUTHENTICATED);
      sb.append(tag).append(" BAD Invalid base64 data").append("\r\n");
      session.setCurrentCommandTag(null);
    }
    return sb.toString();
  }

  public String handleLogin(ImapSessionContext session, String tag, String args) {
    String[] parts = args.split("\\s+", 2);
    if (parts.length < 2) {
      return tag + " BAD login arguments invalid" + "\r\n";
    }
    String user = unquote(parts[0]);
    String pass = unquote(parts[1]);
    if (userService.authenticate(user, pass)) {
      session.setUsername(user);
      session.setState(ImapSessionContext.State.AUTHENTICATED);
      return tag + " OK LOGIN completed." + "\r\n";
    } else {
      return tag + " NO LOGIN failed: Authentication failed" + "\r\n";
    }
  }

  public String handleLogout(String tag) {
    StringBuilder sb = new StringBuilder();
    sb.append("* BYE tio-mail-wing IMAP4rev1 server signing off").append("\r\n");
    sb.append(tag).append(" OK LOGOUT").append("\r\n");
    return sb.toString();
  }

  public String handleSelect(ImapSessionContext session, String tag, String args) {
    String mailbox = unquote(args);
    StringBuilder sb = new StringBuilder();
    if (!"INBOX".equalsIgnoreCase(mailbox)) {
      return tag + " NO SELECT failed: mailbox not found: " + mailbox + "\r\n";
    }
    session.setSelectedMailbox(mailbox);
    session.setState(ImapSessionContext.State.SELECTED);

    Row meta = mailboxService.getMailboxMetadata(session.getUsername(), mailbox);
    if (meta == null) {

    }
    List<Email> all = mailboxService.getActiveMessages(session.getUsername(), mailbox);
    long exists = all.size();
    long recent = all.stream().filter(e -> e.getFlags().contains("\\Recent")).count();
    long uv = meta.getLong("uid_next");
    long un = meta.getLong("uid_validity");

    sb.append("* FLAGS (\\Answered \\Flagged \\Deleted \\Seen \\Draft)").append("\r\n");
    sb.append("* OK [PERMANENTFLAGS (\\Answered \\Flagged \\Deleted \\Seen \\Draft \\*)] Flags permitted.").append("\r\n");
    sb.append("* ").append(exists).append(" EXISTS").append("\r\n");
    sb.append("* ").append(recent).append(" RECENT").append("\r\n");
    sb.append("* OK [UIDVALIDITY ").append(uv).append("] UIDs valid.").append("\r\n");
    sb.append("* OK [UIDNEXT ").append(un).append("] Predicted next UID.").append("\r\n");
    sb.append(tag).append(" OK [READ-WRITE] SELECT completed.").append("\r\n");
    return sb.toString();
  }

  public String handleFetch(ImapSessionContext session, String tag, String args, boolean isUid) {
    if (session.getState() != ImapSessionContext.State.SELECTED) {
      return tag + " NO FETCH failed: No mailbox selected\r\n";
    }
    Matcher m = UID_FETCH_PATTERN.matcher(args);
    if (!m.find()) {
      return tag + " BAD Invalid FETCH arguments: " + args + "\r\n";
    }

    String user = session.getUsername();
    String box = session.getSelectedMailbox();
    String set = m.group(1);
    String items = m.group(2).toUpperCase();

    List<Email> toFetch = isUid ? mailboxService.findEmailsByUidSet(user, box, set) : mailboxService.findEmailsBySeqSet(user, box, set);
    StringBuilder sb = new StringBuilder();
    if (toFetch.isEmpty()) {
      sb.append(tag).append(" OK FETCH completed.\r\n");
      return sb.toString();
    }

    List<Email> all = mailboxService.getActiveMessages(user, box);
    for (Email e : toFetch) {
      int seq = all.indexOf(e) + 1;
      if (seq <= 0)
        continue;

      // 先把整封 raw byte[] 读出来，用于大小计算
      String rawContent = e.getRawContent();
      byte[] raw = rawContent.getBytes(StandardCharsets.UTF_8);
      int fullSize = raw.length;

      // 按 固定顺序 UID → RFC822.SIZE → FLAGS 构造 parts 列表
      List<String> parts = new ArrayList<>();
      if (isUid || items.contains("UID")) {
        parts.add("UID " + e.getUid());
      }
      if (items.contains("RFC822.SIZE")) {
        parts.add("RFC822.SIZE " + fullSize);
      }
      if (items.contains("FLAGS")) {
        Set<String> flags = e.getFlags();
        if (flags != null) {
          parts.add("FLAGS (" + String.join(" ", flags) + ")");
        } else {
          parts.add("FLAGS ()");
        }
      }

      String prefix = "* " + seq + " FETCH (" + String.join(" ", parts);

      // BODY.PEEK[]（全文）
      if (items.contains("BODY.PEEK[]") || items.contains("BODY[]")) {
        sb.append(prefix);
        sb.append(" BODY[] {").append(fullSize).append("}\r\n");
        sb.append(rawContent);
        sb.append("\r\n)\r\n");
        continue;
      }

      // BODY.PEEK[HEADER.FIELDS ...]
      Matcher b = BODY_FETCH_PATTERN.matcher(items);

      if (b.find()) {
        String partToken = b.group(0);
        partToken = partToken.replace("BODY.PEEK", "BODY");
        String hdr = parseHeaderFields(rawContent, EMAIL_HEADER_FIELDS);
        byte[] hdrBytes = hdr.getBytes(StandardCharsets.UTF_8);
        sb.append(prefix).append(" ").append(partToken);
        sb.append(" {").append(hdrBytes.length).append("}\r\n");
        sb.append(hdr);
        sb.append(hdr).append("\r\n)\r\n");
      } else {
        sb.append(prefix).append(")\r\n");
      }
    }

    sb.append(tag).append(" OK FETCH completed.\r\n");
    mailboxService.clearRecentFlags(user, box);
    return sb.toString();
  }

  public String handleStore(ImapSessionContext session, String tag, String args, boolean isUid) {
    if (session.getState() != ImapSessionContext.State.SELECTED) {
      return tag + " NO STORE failed: No mailbox selected" + "\r\n";
    }
    String[] p = args.split("\\s+", 3);
    if (p.length < 3) {
      return tag + " BAD Invalid STORE arguments" + "\r\n";
    }
    String set = p[0];
    String op = p[1];
    String flagsStr = p[2].replaceAll("[()]", "");
    boolean add = op.startsWith("+");
    Set<String> flags = new HashSet<>(Arrays.asList(flagsStr.split("\\s+")));
    String user = session.getUsername();
    String box = session.getSelectedMailbox();
    List<Email> all = mailboxService.getActiveMessages(user, box);
    List<Email> toUpd = isUid ? mailboxService.findEmailsByUidSet(user, box, set) : mailboxService.findEmailsBySeqSet(user, box, set);
    StringBuilder sb = new StringBuilder();
    for (Email e : toUpd) {
      mailboxService.storeFlags(e, flags, add);
      if (!op.contains(".SILENT")) {
        String f = String.join(" ", e.getFlags());
        int seq = all.indexOf(e) + 1;
        sb.append("* ").append(seq).append(" FETCH (FLAGS (" + f + ") UID " + e.getUid() + ")").append("\r\n");
      }
    }
    sb.append(tag).append(" OK STORE completed.").append("\r\n");
    return sb.toString();
  }

  public String handleUid(ImapSessionContext session, String tag, String args) {
    if (session.getState() != ImapSessionContext.State.SELECTED) {
      return tag + " NO UID failed: No mailbox selected" + "\r\n";
    }
    String[] parts = args.split("\\s+", 2);
    String cmd = parts[0].toUpperCase();
    String sub = parts.length > 1 ? parts[1] : "";
    switch (cmd) {
    case "FETCH":
      return handleFetch(session, tag, sub, true);
    case "STORE":
      return handleStore(session, tag, sub, true);
    default:
      return tag + " BAD Unsupported UID command: " + cmd + "\r\n";
    }
  }

  public String parseHeaderFields(String content, String[] fields) {
    Map<String, String> hdr = new HashMap<>();
    for (String line : content.split("\\r?\\n")) {
      if (line.isEmpty()) {
        break;
      }

      int i = line.indexOf(":");
      if (i > 0) {
        hdr.put(line.substring(0, i).toUpperCase(), line.substring(i + 1).trim());
      }
    }

    StringBuilder sb = new StringBuilder();
    for (String f : fields) {
      String v = hdr.get(f.toUpperCase());
      if (v != null) {
        sb.append(f).append(": ").append(v).append("\r\n");
      }

    }
    return sb.toString();
  }

  public String unquote(String s) {
    if (s != null) {
      if (s.startsWith("\"") && s.endsWith("\"")) {
        return s.substring(1, s.length() - 1).toLowerCase();
      } else {
        return s.toLowerCase();
      }

    }
    return s;
  }
}
