package com.tio.mail.wing.packet;

import com.litongjava.aio.Packet;

@SuppressWarnings("serial")
public class ImapPacket extends Packet {
  private String line;

  public ImapPacket(String line) {
    this.line = line;
  }

  public String getLine() {
    return line;
  }
}