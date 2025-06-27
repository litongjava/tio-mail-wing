package com.tio.mail.wing.config;

import com.litongjava.context.BootConfiguration;
import com.litongjava.tio.boot.admin.config.TioAdminDbConfiguration;

public class MwBootConfig implements BootConfiguration {
  public void config() {
    // 配置数据库相关
    new TioAdminDbConfiguration().config();
    // mail server
    new MwProtectConfig().config();
  }
}
