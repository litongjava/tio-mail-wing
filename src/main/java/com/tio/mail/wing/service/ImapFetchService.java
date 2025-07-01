package com.tio.mail.wing.service;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.litongjava.jfinal.aop.Aop;
import com.tio.mail.wing.handler.ImapSessionContext;
import com.tio.mail.wing.model.Email;

import lombok.extern.slf4j.Slf4j;

@Slf4j
public class ImapFetchService {
  private static final String[] EMAIL_HEADER_FIELDS = new String[] { "From", "To", "Cc", "Bcc", "Subject", "Date", "Message-ID", "Priority", "X-Priority", "References", "Newsgroups", "In-Reply-To",
      "Content-Type", "Reply-To" };
  private static final Pattern BODY_FETCH_PATTERN = Pattern.compile("BODY(?:\\.PEEK)?\\[(.*?)\\]", Pattern.CASE_INSENSITIVE);
  private static final Pattern UID_FETCH_PATTERN = Pattern.compile("([\\d\\*:,\\-]+)\\s+\\((.*)\\)", Pattern.CASE_INSENSITIVE);

  private final MailService mailboxService = Aop.get(MailService.class);

  public String handleFetch(ImapSessionContext session, String tag, String args, boolean isUid) {
    if (session.getState() != ImapSessionContext.State.SELECTED) {
      return tag + " NO FETCH failed: No mailbox selected\r\n";
    }
    Matcher m = UID_FETCH_PATTERN.matcher(args);
    if (!m.find()) {
      return tag + " BAD Invalid FETCH arguments: " + args + "\r\n";
    }

    String user = session.getUsername();
    Long userId = session.getUserId();
    String box = session.getSelectedMailbox();
    Long mailBoxId = session.getSelectedMailboxId();
    String set = m.group(1);
    String items = m.group(2).toUpperCase();
    log.info("userId:{},mailBoxId:{},args:{},set:{},items:{}", userId, mailBoxId, args, set, items);
    
    List<Email> toFetch = null;
    if (isUid) {
      toFetch = mailboxService.findEmailsByUidSet(user, box, set);
    } else {
      toFetch = mailboxService.findEmailsBySeqSet(user, box, set);
    }

    StringBuilder sb = new StringBuilder();
    if (toFetch == null || toFetch.isEmpty()) {
      sb.append(tag).append(" OK FETCH completed.\r\n");
      return sb.toString();
    }

    if (items.equalsIgnoreCase("FLAGS")) {
      //UID fetch 1:* (FLAGS)
      sb = fetchFlags(userId, mailBoxId, items, isUid, toFetch);

    } else if (items.contains("BODY.PEEK[]")) {
      sb = fetchBodyPeek(userId, mailBoxId, items, isUid, toFetch);

    } else if (items.contains("BODY[]")) {
      sb = fetchBody(userId, mailBoxId, items, isUid, toFetch);
    } else {
      Matcher b = BODY_FETCH_PATTERN.matcher(items);
      if (b.find()) {
        //UID fetch 1:6 (UID RFC822.SIZE FLAGS BODY.PEEK[HEADER.FIELDS (From To Cc Bcc Subject Date Message-ID Priority X-Priority References Newsgroups In-Reply-To Content-Type Reply-To)])
        String partToken = b.group(0);
        partToken = partToken.replace("BODY.PEEK", "BODY");
        sb = fetchHeader(userId, mailBoxId, items, isUid, partToken, toFetch);
      }
    }

    sb.append(tag).append(" OK FETCH completed.\r\n");
    return sb.toString();
  }

  private StringBuilder fetchFlags(Long userId, Long mailBoxId, String items, boolean isUid, List<Email> toFetch) {

    StringBuilder sb = new StringBuilder();
    List<Long> allUids = mailboxService.listUids(userId, mailBoxId);

    for (int i = 0; i < toFetch.size(); i++) {

      Email e = toFetch.get(i);
      int seq = allUids.indexOf(e.getUid()) + 1;

      List<String> parts = new ArrayList<>();
      parts.add("UID " + e.getUid());

      Set<String> flags = e.getFlags();
      if (flags != null) {
        parts.add("FLAGS (" + String.join(" ", flags) + ")");
      } else {
        parts.add("FLAGS ()");
      }

      String prefix = "* " + seq + " FETCH (" + String.join(" ", parts) + ")\r\n";
      sb.append(prefix);
    }
    return sb;
  }

  private StringBuilder fetchHeader(Long userId, Long mailBoxId, String items, boolean isUid, String partToken, List<Email> toFetch) {
    StringBuilder sb = new StringBuilder();
    List<Long> allUids = mailboxService.listUids(userId, mailBoxId);
    for (int i = 0; i < toFetch.size(); i++) {
      Email e = toFetch.get(i);
      int seq = allUids.indexOf(e.getUid()) + 1;

      // 先把整封 raw byte[] 读出来，用于大小计算
      String rawContent = e.getRawContent();
      byte[] raw = rawContent.getBytes(StandardCharsets.UTF_8);
      int fullSize = raw.length;

      String prefix = prefixLine(seq, isUid, items, fullSize, e);

      String hdr = parseHeaderFields(rawContent, EMAIL_HEADER_FIELDS);
      byte[] hdrBytes = hdr.getBytes(StandardCharsets.UTF_8);
      sb.append(prefix).append(" ").append(partToken);
      sb.append(" {").append(hdrBytes.length + 2).append("}\r\n");
      sb.append(hdr);
      sb.append("\r\n)\r\n");
    }
    return sb;
  }

  private StringBuilder fetchBody(Long userId, Long mailBoxId, String items, boolean isUid, List<Email> toFetch) {
    StringBuilder sb = new StringBuilder();
    List<Long> allUids = mailboxService.listUids(userId, mailBoxId);
    for (int i = 0; i < toFetch.size(); i++) {
      Email e = toFetch.get(i);
      int seq = allUids.indexOf(e.getUid()) + 1;

      // 先把整封 raw byte[] 读出来，用于大小计算
      String rawContent = e.getRawContent();
      byte[] raw = rawContent.getBytes(StandardCharsets.UTF_8);
      int fullSize = raw.length;

      String prefix = prefixLine(seq, isUid, items, fullSize, e);

      mailboxService.storeFlags(e.getId(), Collections.singleton("\\Seen"), true);
      sb.append(prefix);
      sb.append(" BODY[] {").append(fullSize).append("}\r\n");
      sb.append(rawContent);
      sb.append("\r\n)\r\n");

    }
    return sb;
  }

  private StringBuilder fetchBodyPeek(Long userId, Long mailBoxId, String items, boolean isUid, List<Email> toFetch) {
    StringBuilder sb = new StringBuilder();
    List<Long> allUids = mailboxService.listUids(userId, mailBoxId);
    for (int i = 0; i < toFetch.size(); i++) {

      Email e = toFetch.get(i);
      int seq = allUids.indexOf(e.getUid()) + 1;
      // 先把整封 raw byte[] 读出来，用于大小计算
      String rawContent = e.getRawContent();
      byte[] raw = rawContent.getBytes(StandardCharsets.UTF_8);
      int fullSize = raw.length;

      String prefix = prefixLine(seq, isUid, items, fullSize, e);

      sb.append(prefix);
      sb.append(" BODY[] {").append(fullSize).append("}\r\n");
      sb.append(rawContent);
      sb.append("\r\n)\r\n");
    }
    return sb;
  }

  //* 1 FETCH (UID 1 RFC822.SIZE 262 FLAGS (\Seen) BODY[HEADER.FIELDS (FROM TO CC BCC SUBJECT DATE MESSAGE-ID PRIORITY X-PRIORITY REFERENCES NEWSGROUPS IN-REPLY-TO CONTENT-TYPE REPLY-TO)] {211}
  private String prefixLine(int seq, boolean isUid, String items, int fullSize, Email email) {
    // 按 固定顺序 UID → RFC822.SIZE → FLAGS 构造 parts 列表
    List<String> parts = new ArrayList<>();
    if (isUid || items.contains("UID")) {
      parts.add("UID " + email.getUid());
    }
    if (items.contains("RFC822.SIZE")) {
      parts.add("RFC822.SIZE " + fullSize);
    }
    if (items.contains("FLAGS")) {
      Set<String> flags = email.getFlags();
      if (flags != null) {
        parts.add("FLAGS (" + String.join(" ", flags) + ")");
      } else {
        parts.add("FLAGS ()");
      }
    }

    String prefix = "* " + seq + " FETCH (" + String.join(" ", parts);
    return prefix;
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
}
