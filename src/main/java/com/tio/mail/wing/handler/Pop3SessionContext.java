// src/main/java/com/tio/mail/wing/handler/Pop3SessionContext.java
package com.tio.mail.wing.handler;

import com.litongjava.tio.core.ChannelContext;
import com.litongjava.tio.core.Tio;
import com.tio.mail.wing.packet.Pop3Packet;
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
  // 可以添加更多字段，如待删除邮件列表等

  /**
   * 发送 OK 响应
   * @param context ChannelContext
   * @param message 消息内容
   */
  public static void sendOk(ChannelContext context, String message) {
    String response = "+OK " + message + "\r\n";
    Tio.send(context, new Pop3Packet(response));
  }

  /**
   * 发送 ERR 响应
   * @param context ChannelContext
   * @param message 消息内容
   */
  public static void sendErr(ChannelContext context, String message) {
    String response = "-ERR " + message + "\r\n";
    Tio.send(context, new Pop3Packet(response));
  }

  /**
   * 发送多行数据
   * @param context ChannelContext
   * @param data 多行数据
   */
  public static void sendData(ChannelContext context, String data) {
    // 多行数据以 "." 结尾
    String response = data + "\r\n.\r\n";
    Tio.send(context, new Pop3Packet(response));
  }
}