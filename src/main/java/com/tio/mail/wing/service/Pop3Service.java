package com.tio.mail.wing.service;

import java.util.List;

import com.litongjava.jfinal.aop.Aop;
import com.tio.mail.wing.handler.Pop3SessionContext;

import lombok.extern.slf4j.Slf4j;

@Slf4j
public class Pop3Service {

  private final MwUserService userService = Aop.get(MwUserService.class);
  private final MailService mailboxService = Aop.get(MailService.class);

  /**
   * 处理授权阶段命令，返回一次性可发送的 POP3 响应字符串
   */
  public String handleAuthorizationState(String command, String[] parts, Pop3SessionContext sessionContext) {
    StringBuilder resp = new StringBuilder();

    switch (command) {
    case "CAPA":
      // +OK 响应头
      resp.append("+OK Capability list follows\r\n");
      // 多行能力列表，并以单独一行 "." 结束
      resp.append("TOP\r\n").append("USER\r\n").append("UIDL\r\n").append("PIPELINING\r\n")
          // 可选 STLS 注释留在代码里，未来如需支持可解开
          // .append("STLS\r\n")
          .append(".\r\n");
      break;

    case "USER":
      if (parts.length < 2) {
        resp.append("-ERR Username required.\r\n");
      } else {
        sessionContext.setUsername(parts[1]);
        resp.append("+OK Password required for ").append(parts[1]).append("\r\n");
      }
      break;

    case "PASS":
      String username = sessionContext.getUsername();
      if (username == null) {
        resp.append("-ERR USER command first.\r\n");
      } else if (parts.length < 2) {
        resp.append("-ERR Password required.\r\n");
      } else {
        Long userId = userService.authenticate(username, parts[1]);
        if (userId != null) {
          sessionContext.setState(Pop3SessionContext.State.TRANSACTION);
          sessionContext.setUserId(userId);
          resp.append("+OK Mailbox open.\r\n");
        } else {
          sessionContext.setUsername(null);
          resp.append("-ERR Authentication failed.\r\n");
        }
      }
      break;

    case "QUIT":
      // 委托给 handleQuit 构造响应
      return handleQuit(sessionContext);

    default:
      resp.append("-ERR Unknown command or command not allowed.\r\n");
    }

    String result = resp.toString();
    log.info("POP3 >>>\n{}", result.trim());
    return result;
  }

  /**
   * 处理事务阶段命令，返回一次性可发送的 POP3 响应字符串
   */
  public String handleTransactionState(String command, String[] parts, Pop3SessionContext sessionContext) {
    StringBuilder resp = new StringBuilder();
    String username = sessionContext.getUsername();
    Long userId = sessionContext.getUserId();

    switch (command) {
    case "STAT":
      int[] stat = mailboxService.getStat(username);
      resp.append("+OK ").append(stat[0]).append(" ").append(stat[1]).append("\r\n");
      break;

    case "TOP":
      if (parts.length < 2) {
        resp.append("-ERR Message number and number of lines required.\r\n");
        break;
      }
      String[] topArgs = parts[1].split("\\s+");
      if (topArgs.length < 2) {
        resp.append("-ERR Message number and number of lines required.\r\n");
        break;
      }
      try {
        int msgId = Integer.parseInt(topArgs[0]);
        int lines = Integer.parseInt(topArgs[1]);
        String content = mailboxService.getMessageContent(username, msgId);
        if (content == null) {
          resp.append("-ERR No such message.\r\n");
        } else {
          resp.append("+OK Top of message follows\r\n");
          // 头+前几行正文
          String[] contentLines = content.split("\r\n");
          boolean inBody = false;
          int bodyCount = 0;
          for (String line : contentLines) {
            resp.append(line).append("\r\n");
            if (!inBody && line.isEmpty())
              inBody = true;
            if (inBody && !line.isEmpty())
              bodyCount++;
            if (inBody && bodyCount >= lines)
              break;
          }
          resp.append(".\r\n");
        }
      } catch (NumberFormatException e) {
        resp.append("-ERR Invalid arguments for TOP command.\r\n");
      }
      break;

    case "LIST":
      List<Integer> sizes = mailboxService.listMessages(userId);
      resp.append("+OK ").append(sizes.size()).append(" messages\r\n");
      for (int i = 0; i < sizes.size(); i++) {
        resp.append(i + 1).append(" ").append(sizes.get(i)).append("\r\n");
      }
      resp.append(".\r\n");
      break;

    case "UIDL":
      List<Long> uids = mailboxService.listUids(userId);
      resp.append("+OK Unique-ID listing follows\r\n");
      for (int i = 0; i < uids.size(); i++) {
        resp.append(i + 1).append(" ").append(uids.get(i)).append("\r\n");
      }
      resp.append(".\r\n");
      break;

    case "RETR":
      if (parts.length < 2) {
        resp.append("-ERR Message ID required.\r\n");
        break;
      }
      try {
        int msgId = Integer.parseInt(parts[1]);
        String content = mailboxService.getMessageContent(username, msgId);
        if (content == null) {
          resp.append("-ERR No such message.\r\n");
        } else {
          resp.append("+OK Message ").append(msgId).append(" follows\r\n");
          // 邮件全文
          resp.append(content).append("\r\n.\r\n");
        }
      } catch (NumberFormatException e) {
        resp.append("-ERR Invalid message ID.\r\n");
      }
      break;

    case "DELE":
      // 标记删除逻辑（此示例暂不真正删除）
      resp.append("+OK Message marked for deletion.\r\n");
      break;

    case "NOOP":
      resp.append("+OK\r\n");
      break;

    case "RSET":
      // 取消删除标记逻辑
      resp.append("+OK Deletion marks removed.\r\n");
      break;

    case "QUIT":
      return handleQuit(sessionContext);

    default:
      resp.append("-ERR Unknown command.\r\n");
    }

    String result = resp.toString();
    log.info("POP3 >>>\n{}", result.trim());
    return result;
  }

  /**
   * 处理 QUIT，返回一次性可发送的 POP3 响应字符串
   */
  public String handleQuit(Pop3SessionContext sessionContext) {
    sessionContext.setState(Pop3SessionContext.State.UPDATE);
    // 这里执行真正的删除操作（本系统暂不删除邮件）
    String result = "+OK tio-mail-wing POP3 server signing off.\r\n";
    log.info("POP3 >>>\n{}", result.trim());
    return result;
  }
}
