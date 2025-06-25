// src/main/java/com/tio/mail/wing/config/Pop3ServerConfig.java
package com.tio.mail.wing.config;

import java.io.IOException;

import com.litongjava.annotation.AConfiguration;
import com.litongjava.annotation.Initialization;
import com.litongjava.jfinal.aop.Aop;
import com.litongjava.tio.server.ServerTioConfig;
import com.litongjava.tio.server.TioServer;
import com.litongjava.tio.server.intf.ServerAioHandler;
import com.litongjava.tio.utils.environment.EnvUtils;
import com.tio.mail.wing.handler.Pop3ServerAioHandler;
import com.tio.mail.wing.listener.Pop3ServerAioListener;

import lombok.extern.slf4j.Slf4j;

@AConfiguration
@Slf4j
public class Pop3ServerConfig {

  @Initialization
  public void startPop3Server() {

    ServerAioHandler serverHandler = Aop.get(Pop3ServerAioHandler.class);
    Pop3ServerAioListener pop3ServerAioListener = Aop.get(Pop3ServerAioListener.class);

    // 配置对象
    ServerTioConfig serverTioConfig = new ServerTioConfig("pop3-server");
    serverTioConfig.setServerAioHandler(serverHandler);
    serverTioConfig.setServerAioListener(pop3ServerAioListener);
    serverTioConfig.checkAttacks = false;
    serverTioConfig.ignoreDecodeFail = true;

    // 设置心跳,-1 取消心跳
    serverTioConfig.setHeartbeatTimeout(-1);

    // TioServer对象
    TioServer tioServer = new TioServer(serverTioConfig);

    // 启动服务
    try {
      int port = EnvUtils.getInt("mail.server.pop3.port", 110);
      tioServer.start(null, port);
      log.info("Started POP3 server on port: {}", port);
    } catch (IOException e) {
      log.error("Failed to start POP3 server", e);
    }
  }
}