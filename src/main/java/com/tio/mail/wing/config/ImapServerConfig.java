package com.tio.mail.wing.config;

import java.io.IOException;

import com.litongjava.tio.server.ServerTioConfig;
import com.litongjava.tio.server.TioServer;
import com.litongjava.tio.utils.environment.EnvUtils;
import com.tio.mail.wing.handler.ImapServerAioHandler;
import com.tio.mail.wing.listener.ImapServerAioListener;

import lombok.extern.slf4j.Slf4j;

@Slf4j
public class ImapServerConfig {

  public void startImapServer() {
    ImapServerAioHandler serverHandler = new ImapServerAioHandler();
    ImapServerAioListener serverListener = new ImapServerAioListener();

    ServerTioConfig serverTioConfig = new ServerTioConfig("imap-server");
    serverTioConfig.setServerAioHandler(serverHandler);
    serverTioConfig.setServerAioListener(serverListener);
    serverTioConfig.setHeartbeatTimeout(-1);
    serverTioConfig.checkAttacks = false;
    serverTioConfig.ignoreDecodeFail = true;

    TioServer tioServer = new TioServer(serverTioConfig);

    try {
      int port = EnvUtils.getInt("mail.server.imap.port", 143);
      tioServer.start(null, port);
      log.info("Started IMAP server on port: {}", port);
    } catch (IOException e) {
      log.error("Failed to start IMAP server", e);
    }
  }
}