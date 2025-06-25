// src/main/java/com/tio/mail/wing/config/SmtpServerConfig.java
package com.tio.mail.wing.config;

import java.io.IOException;

import com.litongjava.annotation.AConfiguration;
import com.litongjava.annotation.Initialization;
import com.litongjava.tio.server.ServerTioConfig;
import com.litongjava.tio.server.TioServer;
import com.litongjava.tio.utils.environment.EnvUtils;
import com.tio.mail.wing.handler.SmtpServerAioHandler;
import com.tio.mail.wing.listener.SmtpServerAioListener;

import lombok.extern.slf4j.Slf4j;

@AConfiguration
@Slf4j
public class SmtpServerConfig {

  @Initialization
  public void startSmtpServer() {
    SmtpServerAioHandler serverHandler = new SmtpServerAioHandler();
    SmtpServerAioListener serverListener = new SmtpServerAioListener();

    ServerTioConfig serverTioConfig = new ServerTioConfig("smtp-server");
    serverTioConfig.checkAttacks = false;
    serverTioConfig.ignoreDecodeFail = true;
    serverTioConfig.setServerAioHandler(serverHandler);
    serverTioConfig.setServerAioListener(serverListener);
    serverTioConfig.setHeartbeatTimeout(-1); // SMTP 不需要应用层心跳

    TioServer tioServer = new TioServer(serverTioConfig);

    try {
      int port = EnvUtils.getInt("mail.server.smtp.port", 25);
      tioServer.start(null, port);
      log.info("Started SMTP server on port: {}", port);
    } catch (IOException e) {
      log.error("Failed to start SMTP server", e);
    }
  }
}