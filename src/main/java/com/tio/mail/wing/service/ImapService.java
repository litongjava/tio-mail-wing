package com.tio.mail.wing.service;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import com.litongjava.db.activerecord.Row;
import com.litongjava.jfinal.aop.Aop;
import com.litongjava.tio.utils.base64.Base64Utils;
import com.tio.mail.wing.consts.MailBoxName;
import com.tio.mail.wing.handler.ImapSessionContext;
import com.tio.mail.wing.model.Email;

import lombok.extern.slf4j.Slf4j;

@Slf4j
public class ImapService {

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
      if (m.equalsIgnoreCase(MailBoxName.TRASH)) {
        sb.append("* LIST (\\HasNoChildren) \"/\" ").append("Trash").append("\r\n");
      } else {
        sb.append("* LIST (\\HasNoChildren) \"/\" ").append(m).append("\r\n");
      }

    }
    sb.append(tag).append(" OK LIST completed.").append("\r\n");
    return sb.toString();
  }

  public String handleSubscribe(String tag) {
    return tag + " OK SUBSCRIBE" + "\r\n";
  }

  public String handleCapability(String tag) {
    StringBuilder sb = new StringBuilder();
    sb.append("* CAPABILITY IMAP4rev1 AUTH=LOGIN IDLE UIDPLUS ID LITERAL+ MOVE").append("\r\n");
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
        Long userId = userService.authenticate(user, pass);
        if (userId != null) {
          session.setUsername(user);
          session.setUserId(userId);
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
    Long userId = userService.authenticate(user, pass);
    if (userId != null) {
      session.setUsername(user);
      session.setUserId(userId);

      session.setState(ImapSessionContext.State.AUTHENTICATED);
      return tag + " OK LOGIN completed." + "\r\n";
    } else {
      return tag + " NO LOGIN failed: Authentication failed" + "\r\n";
    }
  }

  public String handleLogout(ImapSessionContext session, String tag) {
    if (session.getState() == ImapSessionContext.State.SELECTED) {
      Long selectedMailboxId = session.getSelectedMailboxId();
      
      mailboxService.clearRecentFlags(selectedMailboxId);
      mailboxService.expunge(session.getUsername(), session.getSelectedMailbox());
      
      session.setSelectedMailbox(null);
      session.setSelectedMailboxId(null);
    }
    StringBuilder sb = new StringBuilder();
    sb.append("* BYE tio-mail-wing IMAP4rev1 server signing off").append("\r\n");
    sb.append(tag).append(" OK LOGOUT").append("\r\n");
    return sb.toString();
  }

  public String handleSelect(ImapSessionContext session, String tag, String args) {
    String mailbox = unquote(args);
    StringBuilder sb = new StringBuilder();
    Long userId = session.getUserId();
    String username = session.getUsername();

    boolean userExists = userService.userExists(userId);
    if (!userExists) {
      return tag + " NO SELECT failed: user not found: " + username + "\r\n";
    }
    Long mailBoxId = mailboxService.queryMailBoxId(userId, mailbox);
    if (mailBoxId == null || mailBoxId < 1) {
      return tag + " NO SELECT failed: mailbox not found: " + mailbox + "\r\n";
    }
    session.setSelectedMailbox(mailbox);
    session.setSelectedMailboxId(mailBoxId);
    session.setState(ImapSessionContext.State.SELECTED);

    Row meta = mailboxService.getMailboxById(userId, mailBoxId);
    if (meta == null) {
      return tag + " NO SELECT failed: mailbox not found: " + mailbox + "\r\n";
    }
    //long highest_modseq = mailboxService.highest_modseq(mailBoxId);
    List<Email> all = mailboxService.getActiveMessages(mailBoxId);

    long exists = all.size();
    int recent = 0;
    long unseen = 0;
    for (Email e : all) {
      Set<String> flags = e.getFlags();
      if (flags.size() > 0) {
        if (flags.contains("\\Recent")) {
          recent++;
        }
        if (!flags.contains("\\Seen")) {
          if (unseen == 0) {
            unseen = e.getUid();
          }

        }
      } else {
        if (unseen == 0) {
          unseen = e.getUid();
        }
      }
    }

    long uv = meta.getLong("uid_next");
    long un = meta.getLong("uid_validity");
    log.info("exists:{},recent:{},uv:{},un:{}", exists, recent, uv, un);

    sb.append("* FLAGS (\\Answered \\Flagged \\Deleted \\Seen \\Draft)").append("\r\n");
    sb.append("* OK [PERMANENTFLAGS (\\Answered \\Flagged \\Deleted \\Seen \\Draft \\*)] Flags permitted.").append("\r\n");
    sb.append("* ").append(exists).append(" EXISTS").append("\r\n");
    sb.append("* ").append(recent).append(" RECENT").append("\r\n");
    if (unseen > 0) {
      //* OK [UNSEEN 5] First unseen.
      sb.append("* OK [UNSEEN ").append(unseen).append("] First unseen.").append("\r\n");
    }
    sb.append("* OK [UIDVALIDITY ").append(un).append("] UIDs valid").append("\r\n");

    sb.append("* OK [UIDNEXT ").append(uv).append("] Predicted next UID").append("\r\n");
    //* OK [HIGHESTMODSEQ 2048]
    //sb.append("* OK [HIGHESTMODSEQ ").append(highest_modseq).append("].").append("\r\n");
    sb.append(tag).append(" OK [READ-WRITE] SELECT completed.").append("\r\n");

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

    Long selectedMailboxId = session.getSelectedMailboxId();

    List<Email> toUpd = null;
    if (isUid) {
      toUpd = mailboxService.findEmailsByUidSet(selectedMailboxId, set);
    } else {
      toUpd = mailboxService.findEmailsBySeqSet(selectedMailboxId, set);
    }

    StringBuilder sb = new StringBuilder();
    for (Email e : toUpd) {
      mailboxService.storeFlags(e, flags, add);
      if (!op.contains(".SILENT")) {
        String f = String.join(" ", e.getFlags());
        int seq = toUpd.indexOf(e) + 1;
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
      ImapFetchService imapFetchService = Aop.get(ImapFetchService.class);
      return imapFetchService.handleFetch(session, tag, sub, true);
    case "STORE":
      return handleStore(session, tag, sub, true);
    case "COPY":
      return handleCopy(session, tag, sub, true);
    case "MOVE":
      return handleMove(session, tag, sub, true);
    default:
      return tag + " BAD Unsupported UID command: " + cmd + "\r\n";
    }
  }

  /**
   * UID MOVE <set> "<mailbox>"
   */
  public String handleMove(ImapSessionContext session, String tag, String args, boolean isUid) {
    if (session.getState() != ImapSessionContext.State.SELECTED) {
      return tag + " NO MOVE failed: No mailbox selected\r\n";
    }
    String[] p = args.split("\\s+", 2);
    if (p.length < 2) {
      return tag + " BAD MOVE arguments invalid\r\n";
    }
    String set = p[0];
    String destMailbox = unquote(p[1]);
    Long userId = session.getUserId();
    String srcMailbox = session.getSelectedMailbox();
    try {
      mailboxService.moveEmailsByUidSet(userId, srcMailbox, set, destMailbox);
      return tag + " OK MOVE completed.\r\n";
    } catch (Exception e) {
      return tag + " NO MOVE failed: " + e.getMessage() + "\r\n";
    }
  }

  private String handleCopy(ImapSessionContext session, String tag, String args, boolean b) {
    if (session.getState() != ImapSessionContext.State.SELECTED) {
      return tag + " NO COPY failed: No mailbox selected\r\n";
    }
    // 拆分出消息集和目标 mailbox
    String[] p = args.split("\\s+", 2);
    if (p.length < 2) {
      return tag + " BAD COPY arguments invalid\r\n";
    }
    String set = p[0];
    String destMailbox = unquote(p[1]);
    String user = session.getUsername();
    String srcMailbox = session.getSelectedMailbox();

    try {
      // 调用新加的接口
      mailboxService.copyEmailsByUidSet(user, srcMailbox, set, destMailbox);
      return tag + " OK COPY completed.\r\n";
    } catch (Exception e) {
      return tag + " NO COPY failed: " + e.getMessage() + "\r\n";
    }
  }

  /**
  * CLOSE: 关闭当前 mailbox，并对所有 \Deleted 标记的邮件做 EXPUNGE
  */
  public String handleClose(ImapSessionContext session, String tag) {
    if (session.getState() != ImapSessionContext.State.SELECTED) {
      return tag + " BAD CLOSE failed: No mailbox selected\r\n";
    }

    String user = session.getUsername();
    String box = session.getSelectedMailbox();

    // 1) 找出待 expunge 的 seq nums，发出 untagged EXPUNGE
    List<Integer> seqs = mailboxService.getExpungeSeqNums(user, box);
    StringBuilder sb = new StringBuilder();
    for (int seq : seqs) {
      sb.append("* ").append(seq).append(" EXPUNGE").append("\r\n");
    }

    // 2) 真正逻辑删除
    mailboxService.expunge(user, box);

    // 3) 取消 selected state
    session.setSelectedMailbox(null);
    session.setSelectedMailboxId(null);
    session.setState(ImapSessionContext.State.AUTHENTICATED);

    // 4) 返回 OK
    sb.append(tag).append(" OK CLOSE completed").append("\r\n");
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

  public String handleStatus(ImapSessionContext session, String tag, String args) {
    // args 示例:  "server_error" (UIDNEXT MESSAGES UNSEEN RECENT)
    // 先拆出 mailbox 名称 和要查询的项
    String[] parts = args.split("\\s+", 2);
    String mbox = unquote(parts[0]);
    String fields = parts[1].trim();
    // 拿到 mailboxId
    Long userId = session.getUserId();
    Long boxId = mailboxService.queryMailBoxId(userId, mbox);
    if (boxId == null) {
      return tag + " NO STATUS failed: mailbox not found\r\n";
    }

    // 统计各项
    // UIDNEXT:
    Row row = mailboxService.status(boxId);
    long uidNext = row.getLong("uidnext");

    // MESSAGES = 总邮件数
    long messages = row.getLong("messages");
    // UNSEEN = 未标 \Seen
    long unseen = row.getLong("unseen");
    // RECENT = 未清 \Recent
    long recent = row.getLong("recent");

    // 构造 untagged STATUS 响应
    StringBuilder sb = new StringBuilder();
    sb.append("* STATUS \"").append(mbox).append("\" (");
    // 按客户端请求的顺序来输出
    if (fields.contains("UIDNEXT")) {
      sb.append("UIDNEXT ").append(uidNext).append(" ");
    }
    if (fields.contains("MESSAGES")) {
      sb.append("MESSAGES ").append(messages).append(" ");
    }
    if (fields.contains("UNSEEN")) {
      sb.append("UNSEEN ").append(unseen).append(" ");
    }
    if (fields.contains("RECENT")) {
      sb.append("RECENT ").append(recent).append(" ");
    }
    // 去掉最后多余的空格
    sb.setLength(sb.length() - 1);
    sb.append(")\r\n");

    // 最后一行 tagged OK
    sb.append(tag).append(" OK STATUS completed\r\n");
    return sb.toString();
  }

}
