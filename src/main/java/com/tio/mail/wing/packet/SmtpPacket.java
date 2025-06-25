package com.tio.mail.wing.packet;

import com.litongjava.aio.Packet;

@SuppressWarnings("serial")
public class SmtpPacket extends Packet {
  private String line;

  public SmtpPacket(String line) {
    this.line = line;
  }

  public String getLine() {
    return line;
  }
}