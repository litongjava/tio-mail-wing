package com.tio.mail.wing.listener;

import com.litongjava.aio.Packet;
import com.litongjava.tio.core.ChannelContext;
import com.litongjava.tio.core.Tio;
import com.litongjava.tio.server.intf.ServerAioListener;
import com.tio.mail.wing.handler.Pop3SessionContext;
import com.tio.mail.wing.packet.Pop3Packet;

import lombok.extern.slf4j.Slf4j;

@Slf4j
public class Pop3ServerAioListener implements ServerAioListener {

  @Override
  public void onAfterConnected(ChannelContext channelContext, boolean isConnected, boolean isReconnect) throws Exception {
    if (isConnected) {
      log.info("POP3 client connected: {}", channelContext.getClientNode());
      // 1. 创建会话上下文
      Pop3SessionContext sessionContext = new Pop3SessionContext();
      channelContext.set("sessionContext", sessionContext);

      // 2. 立即发送欢迎消息
      String response = "+OK tio-mail-wing POP3 server ready.\r\n";
      Tio.send(channelContext, new Pop3Packet(response));
      log.info("POP3 >>> +OK welcome message sent to {}", channelContext.getClientNode());
    }
  }

  @Override
  public void onAfterDecoded(ChannelContext channelContext, Packet packet, int packetSize) throws Exception {
    // Do nothing
  }

  @Override
  public void onAfterReceivedBytes(ChannelContext channelContext, int receivedBytes) throws Exception {
    // Do nothing
  }

  @Override
  public void onAfterSent(ChannelContext channelContext, Packet packet, boolean isSentSuccess) throws Exception {
    // Do nothing
  }

  @Override
  public void onAfterHandled(ChannelContext channelContext, Packet packet, long cost) throws Exception {
    // Do nothing
  }

  @Override
  public void onBeforeClose(ChannelContext channelContext, Throwable throwable, String remark, boolean isRemove) throws Exception {
    log.info("POP3 client disconnected: {}", channelContext.getClientNode());
  }

  @Override
  public boolean onHeartbeatTimeout(ChannelContext channelContext, Long interval, int heartbeatTimeoutCount) {
    return false;
  }
}