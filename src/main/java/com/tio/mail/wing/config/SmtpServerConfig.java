package com.tio.mail.wing.config;

import java.io.IOException;

import com.litongjava.hook.HookCan;
import com.litongjava.tio.server.ServerTioConfig;
import com.litongjava.tio.server.TioServer;
import com.litongjava.tio.utils.environment.EnvUtils;
import com.tio.mail.wing.handler.SmtpServerAioHandler;
import com.tio.mail.wing.listener.SmtpServerAioListener;

import lombok.extern.slf4j.Slf4j;

@Slf4j
public class SmtpServerConfig {

  public void startSmtpServer() {
    SmtpServerAioHandler serverHandler = new SmtpServerAioHandler();
    SmtpServerAioListener serverListener = new SmtpServerAioListener();

    ServerTioConfig serverTioConfig = new ServerTioConfig("smtp-server");
    serverTioConfig.checkAttacks = false;
    serverTioConfig.ignoreDecodeFail = true;
    serverTioConfig.setServerAioHandler(serverHandler);
    serverTioConfig.setServerAioListener(serverListener);
    serverTioConfig.setHeartbeatTimeout(-1);
    serverTioConfig.setWorkerThreads(4);

    TioServer tioServer = new TioServer(serverTioConfig);

    try {
      int port = EnvUtils.getInt("mail.server.smtp.port", 25);
      tioServer.start(null, port);
      HookCan.me().addDestroyMethod(tioServer::stop);
      log.info("Started SMTP server on port: {}", port);
    } catch (IOException e) {
      log.error("Failed to start SMTP server", e);
    }
    
    
  }
}