package com.tio.mail.wing.handler;

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
  private Long userId;
  private String username;
  private String selectedMailbox;
  private Long selectedMailboxId;

  /**
   * 用于暂存 AUTHENTICATE 命令的 tag，以便在多步交互后能正确响应
   */
  private String currentCommandTag;
}