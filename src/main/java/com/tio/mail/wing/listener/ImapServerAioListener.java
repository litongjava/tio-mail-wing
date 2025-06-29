package com.tio.mail.wing.listener;

import com.litongjava.aio.Packet;
import com.litongjava.tio.core.ChannelContext;
import com.litongjava.tio.core.Tio;
import com.litongjava.tio.server.intf.ServerAioListener;
import com.tio.mail.wing.handler.ImapSessionContext;
import com.tio.mail.wing.packet.ImapPacket;

import lombok.extern.slf4j.Slf4j;

@Slf4j
public class ImapServerAioListener implements ServerAioListener {

  @Override
  public void onAfterConnected(ChannelContext channelContext, boolean isConnected, boolean isReconnect) throws Exception {
    if (isConnected) {
      log.info("IMAP client connected: {}", channelContext.getClientNode());
      channelContext.set("sessionContext", new ImapSessionContext());
      // 发送欢迎消息
      Tio.send(channelContext, new ImapPacket("* OK tio-mail-wing IMAP4rev1 server ready \r\n"));
    }
  }

  @Override
  public void onBeforeClose(ChannelContext channelContext, Throwable throwable, String remark, boolean isRemove) throws Exception {
    log.info("IMAP client disconnected: {}", channelContext.getClientNode());
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