package com.tio.mail.wing.handler;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class Pop3SessionContext {
  // 会话状态枚举
  public enum State {
    /**
     * 未认证
     */
    AUTHORIZATION,
    /**
     * 已认证，可以进行邮件操作
     */
    TRANSACTION,
    /**
     * 准备关闭
     */
    UPDATE
  }

  private State state = State.AUTHORIZATION;
  private String username;
}