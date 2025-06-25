// src/main/java/com/tio/mail/wing/listener/SmtpServerAioListener.java
package com.tio.mail.wing.listener;

import com.litongjava.aio.Packet;
import com.litongjava.tio.core.ChannelContext;
import com.litongjava.tio.server.intf.ServerAioListener;
import com.tio.mail.wing.handler.SmtpSessionContext;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class SmtpServerAioListener implements ServerAioListener {

  @Override
  public void onAfterConnected(ChannelContext channelContext, boolean isConnected, boolean isReconnect) throws Exception {
    if (isConnected) {
      log.info("SMTP client connected: {}", channelContext.getClientNode());
      // 1. 创建会话上下文
      SmtpSessionContext sessionContext = new SmtpSessionContext();
      channelContext.set("sessionContext", sessionContext);

      // 2. 发送欢迎消息 (220)
      SmtpSessionContext.sendResponse(channelContext, 220, "tio-mail-wing ESMTP Service ready");
      log.info("SMTP >>> 220 Welcome message sent to {}", channelContext.getClientNode());
    }
  }

  @Override
  public void onBeforeClose(ChannelContext channelContext, Throwable throwable, String remark, boolean isRemove) throws Exception {
    log.info("SMTP client disconnected: {}", channelContext.getClientNode());
  }

  @Override
  public void onAfterDecoded(ChannelContext channelContext, Packet packet, int packetSize) throws Exception {
    // TODO Auto-generated method stub

  }

  @Override
  public void onAfterReceivedBytes(ChannelContext channelContext, int receivedBytes) throws Exception {
    // TODO Auto-generated method stub

  }

  @Override
  public void onAfterSent(ChannelContext channelContext, Packet packet, boolean isSentSuccess) throws Exception {
    // TODO Auto-generated method stub

  }

  @Override
  public void onAfterHandled(ChannelContext channelContext, Packet packet, long cost) throws Exception {
    // TODO Auto-generated method stub

  }

  @Override
  public boolean onHeartbeatTimeout(ChannelContext channelContext, Long interval, int heartbeatTimeoutCount) {
    // TODO Auto-generated method stub
    return false;
  }
}