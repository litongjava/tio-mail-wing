package com.tio.mail.wing.config;

import com.litongjava.context.BootConfiguration;
import com.litongjava.tio.boot.admin.config.TioAdminDbConfiguration;
import com.litongjava.tio.boot.server.TioBootServer;
import com.litongjava.tio.http.server.router.HttpRequestRouter;
import com.tio.mail.wing.handler.ErrorAlarmHandler;

public class MwBootConfig implements BootConfiguration {
  public void config() {
    // 配置数据库相关
    new TioAdminDbConfiguration().config();
    // mail server
    new MwProtectConfig().config();

    HttpRequestRouter r = TioBootServer.me().getRequestRouter();
    if (r != null) {
      ErrorAlarmHandler errorAlarmHandler = new ErrorAlarmHandler();
      r.add("/alarm", errorAlarmHandler::send);
    }
  }
}
