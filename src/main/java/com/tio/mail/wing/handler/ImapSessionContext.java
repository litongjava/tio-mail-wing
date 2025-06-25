// src/main/java/com/tio/mail/wing/handler/ImapSessionContext.java
package com.tio.mail.wing.handler;

import com.litongjava.tio.core.ChannelContext;
import com.litongjava.tio.core.Tio;
import com.tio.mail.wing.packet.ImapPacket;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class ImapSessionContext {

  public enum State {
    /** 未认证 */
    NON_AUTHENTICATED,
    /** 等待 Base64 编码的用户名 */
    AUTH_WAIT_USERNAME,
    /** 等待 Base64 编码的密码 */
    AUTH_WAIT_PASSWORD,
    /** 已认证 */
    AUTHENTICATED,
    /** 已选择邮箱 */
    SELECTED
  }

  private State state = State.NON_AUTHENTICATED;
  private String username;
  private String selectedMailbox;

  /**
   * 用于暂存 AUTHENTICATE 命令的 tag，以便在多步交互后能正确响应
   */
  private String currentCommandTag;

  // ... (所有 sendXxx 辅助方法保持不变)
  public static void send(ChannelContext ctx, String message) {
    Tio.bSend(ctx, new ImapPacket(message + "\r\n"));
  }

  public static void sendTaggedOk(ChannelContext ctx, String tag, String command) {
    send(ctx, tag + " OK " + command);
  }

  public static void sendTaggedNo(ChannelContext ctx, String tag, String command, String message) {
    send(ctx, tag + " NO " + command + " failed: " + message);
  }

  public static void sendTaggedBad(ChannelContext ctx, String tag, String message) {
    send(ctx, tag + " BAD " + message);
  }

  public static void sendUntagged(ChannelContext ctx, String message) {
    send(ctx, "* " + message);
  }
}