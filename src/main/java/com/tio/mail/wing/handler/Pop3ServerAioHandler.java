// src/main/java/com/tio/mail/wing/handler/Pop3ServerAioHandler.java
package com.tio.mail.wing.handler;

import java.nio.ByteBuffer;
import java.util.List;

import com.litongjava.aio.Packet;
import com.litongjava.jfinal.aop.Aop;
import com.litongjava.tio.core.ChannelContext;
import com.litongjava.tio.core.Tio;
import com.litongjava.tio.core.TioConfig;
import com.litongjava.tio.core.exception.LengthOverflowException;
import com.litongjava.tio.core.exception.TioDecodeException;
import com.litongjava.tio.core.utils.ByteBufferUtils;
import com.litongjava.tio.server.intf.ServerAioHandler;
import com.tio.mail.wing.packet.Pop3Packet;
import com.tio.mail.wing.service.MailboxService;
import com.tio.mail.wing.service.MwUserService;

import lombok.extern.slf4j.Slf4j;

@Slf4j
public class Pop3ServerAioHandler implements ServerAioHandler {

  private MwUserService userService = Aop.get(MwUserService.class);

  private MailboxService mailboxService = Aop.get(MailboxService.class);

  private static final String CHARSET = "UTF-8";

  /**
   * 解码：从 ByteBuffer 中解析出以 \r\n 结尾的一行命令
   */
  @Override
  public Packet decode(ByteBuffer buffer, int limit, int position, int readableLength, ChannelContext channelContext) throws TioDecodeException {
    // Tio内置了行解码器，非常方便
    String line = null;
    try {
      line = ByteBufferUtils.readLine(buffer, CHARSET);
    } catch (LengthOverflowException e) {
      e.printStackTrace();
    }

    // 如果 line 为 null，表示数据不完整，不是一个完整的行，需要等待更多数据
    if (line == null) {
      return null;
    }

    // 返回一个包含该行命令的 Packet
    return new Pop3Packet(line);
  }

  /**
   * 编码：将响应字符串转换为 ByteBuffer
   */
  @Override
  public ByteBuffer encode(Packet packet, TioConfig tioConfig, ChannelContext channelContext) {
    Pop3Packet pop3Packet = (Pop3Packet) packet;
    String line = pop3Packet.getLine();
    try {
      byte[] bytes = line.getBytes(CHARSET);
      ByteBuffer buffer = ByteBuffer.allocate(bytes.length);
      buffer.put(bytes);
      return buffer;
    } catch (Exception e) {
      log.error("Encoding error", e);
      return null;
    }
  }

  /**
   * 消息处理：根据收到的命令和当前会话状态进行响应
   */
  @Override
  public void handler(Packet packet, ChannelContext channelContext) throws Exception {
    Pop3Packet pop3Packet = (Pop3Packet) packet;
    String commandLine = pop3Packet.getLine().trim();
    log.info("POP3 <<< {}", commandLine);

    // 获取或创建会日志上下文
    Pop3SessionContext sessionContext = (Pop3SessionContext) channelContext.get("sessionContext");

    String[] parts = commandLine.split("\\s+", 2);
    String command = parts[0].toUpperCase();

    // 根据会话状态处理命令
    switch (sessionContext.getState()) {
    case AUTHORIZATION:
      handleAuthorizationState(command, parts, channelContext, sessionContext);
      break;
    case TRANSACTION:
      handleTransactionState(command, parts, channelContext, sessionContext);
      break;
    case UPDATE:
      // 在 UPDATE 状态，通常只响应 QUIT
      if ("QUIT".equals(command)) {
        handleQuit(channelContext, sessionContext);
      } else {
        Pop3SessionContext.sendErr(channelContext, "Command not allowed in UPDATE state.");
      }
      break;
    }
  }

  private void handleAuthorizationState(String command, String[] parts, ChannelContext channelContext, Pop3SessionContext sessionContext) {
    switch (command) {
    case "CAPA":
      // 1. 先发送 +OK 响应头
      Pop3SessionContext.sendOk(channelContext, "Capability list follows");

      // 2. 构造多行响应体
      StringBuilder capaBuilder = new StringBuilder();
      capaBuilder.append("TOP\r\n"); // 声明支持 TOP 命令
      capaBuilder.append("USER\r\n"); // 声明支持 USER/PASS 认证
      capaBuilder.append("UIDL\r\n"); // 声明支持 UIDL 命令
      capaBuilder.append("PIPELINING\r\n"); // 声明支持管道，可以提高效率
      // capaBuilder.append("STLS\r\n");  // 如果未来支持 STARTTLS，可以取消这行注释
      capaBuilder.append("."); // 以点结束多行响应

      // 3. 发送响应体
      Tio.send(channelContext, new Pop3Packet(capaBuilder.toString() + "\r\n"));
      // 注意：因为我们自己拼接了多行响应，所以不需要用 sendData 方法，
      // 并且日志也应该在发送时手动打印，以保持一致性。
      log.info("POP3 >>>\n{}", capaBuilder.toString().trim());
      break;
    case "USER":
      if (parts.length < 2) {
        Pop3SessionContext.sendErr(channelContext, "Username required.");
        return;
      }
      sessionContext.setUsername(parts[1]);
      Pop3SessionContext.sendOk(channelContext, "Password required for " + parts[1]);
      break;
    case "PASS":
      if (sessionContext.getUsername() == null) {
        Pop3SessionContext.sendErr(channelContext, "USER command first.");
        return;
      }
      if (parts.length < 2) {
        Pop3SessionContext.sendErr(channelContext, "Password required.");
        return;
      }
      if (userService.authenticate(sessionContext.getUsername(), parts[1])) {
        sessionContext.setState(Pop3SessionContext.State.TRANSACTION);
        Pop3SessionContext.sendOk(channelContext, "Mailbox open.");
      } else {
        Pop3SessionContext.sendErr(channelContext, "Authentication failed.");
        sessionContext.setUsername(null); // 认证失败，清空用户名
      }
      break;
    case "QUIT":
      handleQuit(channelContext, sessionContext);
      break;
    default:
      Pop3SessionContext.sendErr(channelContext, "Unknown command or command not allowed.");
    }
  }

  private void handleTransactionState(String command, String[] parts, ChannelContext channelContext, Pop3SessionContext sessionContext) {
    String username = sessionContext.getUsername(); // 获取当前用户名
    switch (command) {
    case "STAT":
      int[] stat = mailboxService.getStat(sessionContext.getUsername());
      Pop3SessionContext.sendOk(channelContext, stat[0] + " " + stat[1]);
      break;
    case "TOP":
      if (parts.length < 2) {
        Pop3SessionContext.sendErr(channelContext, "Message number and number of lines required.");
        return;
      }
      String[] topArgs = parts[1].split("\\s+");
      if (topArgs.length < 2) {
        Pop3SessionContext.sendErr(channelContext, "Message number and number of lines required.");
        return;
      }

      try {
        int msgId = Integer.parseInt(topArgs[0]);
        int lines = Integer.parseInt(topArgs[1]);

        String content = mailboxService.getMessageContent(username, msgId);
        if (content != null) {
          Pop3SessionContext.sendOk(channelContext, "Top of message follows");

          // 简单的模拟实现：返回邮件头和正文的前几行
          String[] contentLines = content.split("\r\n");
          StringBuilder topResponse = new StringBuilder();

          boolean inBody = false;
          int bodyLinesCount = 0;

          for (String line : contentLines) {
            topResponse.append(line).append("\r\n");
            if (line.isEmpty()) { // 空行是邮件头和体的分隔符
              inBody = true;
            }
            if (inBody && !line.isEmpty()) {
              bodyLinesCount++;
            }
            if (inBody && bodyLinesCount >= lines) {
              break; // 正文行数已达到要求
            }
          }
          topResponse.append(".");

          Tio.send(channelContext, new Pop3Packet(topResponse.toString() + "\r\n"));
          log.info("POP3 >>>\n{}", topResponse.toString().trim());

        } else {
          Pop3SessionContext.sendErr(channelContext, "No such message.");
        }

      } catch (NumberFormatException e) {
        Pop3SessionContext.sendErr(channelContext, "Invalid arguments for TOP command.");
      }
      break;
    case "LIST":
      List<Integer> sizes = mailboxService.listMessages(username);
      Pop3SessionContext.sendOk(channelContext, sizes.size() + " messages");

      StringBuilder listResponse = new StringBuilder();
      for (int i = 0; i < sizes.size(); i++) {
        listResponse.append(i + 1).append(" ").append(sizes.get(i)).append("\r\n");
      }
      listResponse.append(".");

      Tio.send(channelContext, new Pop3Packet(listResponse.toString() + "\r\n"));
      log.info("POP3 >>>\n{}", listResponse.toString().trim());
      break;

    case "UIDL":
      List<Long> uids = mailboxService.listUids(username);
      Pop3SessionContext.sendOk(channelContext, "Unique-ID listing follows");

      StringBuilder uidlResponse = new StringBuilder();
      for (int i = 0; i < uids.size(); i++) {
        // 序号 (i+1) 和 唯一ID
        uidlResponse.append(i + 1).append(" ").append(uids.get(i)).append("\r\n");
      }
      uidlResponse.append(".");

      Tio.send(channelContext, new Pop3Packet(uidlResponse.toString() + "\r\n"));
      log.info("POP3 >>>\n{}", uidlResponse.toString().trim());
      break;

    case "RETR":
      if (parts.length < 2) {
        Pop3SessionContext.sendErr(channelContext, "Message ID required.");
        return;
      }
      try {
        int msgId = Integer.parseInt(parts[1]);
        String content = mailboxService.getMessageContent(sessionContext.getUsername(), msgId);
        if (content != null) {
          Pop3SessionContext.sendOk(channelContext, "Message " + msgId + " follows");
          Pop3SessionContext.sendData(channelContext, content);
        } else {
          Pop3SessionContext.sendErr(channelContext, "No such message.");
        }
      } catch (NumberFormatException e) {
        Pop3SessionContext.sendErr(channelContext, "Invalid message ID.");
      }
      break;
    case "DELE":
      // TODO: 实现标记删除逻辑
      Pop3SessionContext.sendOk(channelContext, "Message marked for deletion.");
      break;
    case "NOOP":
      Pop3SessionContext.sendOk(channelContext, "");
      break;
    case "RSET":
      // TODO: 实现取消删除标记的逻辑
      Pop3SessionContext.sendOk(channelContext, "Deletion marks removed.");
      break;
    case "QUIT":
      handleQuit(channelContext, sessionContext);
      break;
    default:
      Pop3SessionContext.sendErr(channelContext, "Unknown command.");
    }
  }

  private void handleQuit(ChannelContext channelContext, Pop3SessionContext sessionContext) {
    sessionContext.setState(Pop3SessionContext.State.UPDATE);
    // TODO: 在这里执行真正的删除操作
    Pop3SessionContext.sendOk(channelContext, "tio-mail-wing POP3 server signing off.");
    Tio.close(channelContext, "Client requested QUIT.");
  }
}