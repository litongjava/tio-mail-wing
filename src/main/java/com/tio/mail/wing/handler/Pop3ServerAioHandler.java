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
import com.tio.mail.wing.packet.Pop3Packet;
import com.tio.mail.wing.service.Pop3Service;

import lombok.extern.slf4j.Slf4j;

@Slf4j
public class Pop3ServerAioHandler implements ServerAioHandler {
  private Pop3Service pop3Service = Aop.get(Pop3Service.class);

  /**
   * 解码：从 ByteBuffer 中解析出以 \r\n 结尾的一行命令
   */
  @Override
  public Packet decode(ByteBuffer buffer, int limit, int position, int readableLength, ChannelContext channelContext) throws TioDecodeException {
    String charset = channelContext.getTioConfig().getCharset();
    // Tio内置了行解码器，非常方便
    String line = null;
    try {
      line = ByteBufferUtils.readLine(buffer, charset);
    } catch (LengthOverflowException e) {
      e.printStackTrace();
    }

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
    String charset = channelContext.getTioConfig().getCharset();
    Pop3Packet pop3Packet = (Pop3Packet) packet;
    String line = pop3Packet.getLine();
    try {
      byte[] bytes = line.getBytes(charset);
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

    String reply = null;
    // 根据会话状态处理命令
    switch (sessionContext.getState()) {
    case AUTHORIZATION:
      reply = pop3Service.handleAuthorizationState(command, parts, sessionContext);
      break;
    case TRANSACTION:
      reply = pop3Service.handleTransactionState(command, parts, sessionContext);
      break;
    case UPDATE:
      // 在 UPDATE 状态，通常只响应 QUIT
      if ("QUIT".equals(command)) {
        reply = pop3Service.handleQuit(sessionContext);
        if (reply != null) {
          Tio.send(channelContext, new Pop3Packet(reply));
        }
        Tio.close(channelContext, "quit");
      } else {
        reply = "-ERR Command not allowed in UPDATE state.\r\n";

      }
      break;
    }
    if (reply != null) {
      Tio.send(channelContext, new Pop3Packet(reply));
    }
  }
}