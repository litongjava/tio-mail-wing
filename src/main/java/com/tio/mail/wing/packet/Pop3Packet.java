package com.tio.mail.wing.packet;

import com.litongjava.aio.Packet;

/**
 * POP3 消息包，直接存储解码后的命令或响应字符串
 */
@SuppressWarnings("serial")
public class Pop3Packet extends Packet {
  private String line;

  public Pop3Packet(String line) {
    this.line = line;
  }

  public String getLine() {
    return line;
  }

  public void setLine(String line) {
    this.line = line;
  }
}