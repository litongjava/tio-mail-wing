package com.tio.mail.wing.config;



import com.litongjava.annotation.AConfiguration;
import com.litongjava.annotation.Initialization;
import com.litongjava.tio.boot.admin.config.TioAdminDbConfiguration;

@AConfiguration
public class MwAdminAppConfig {
  @Initialization
  public void config() {
    // 配置数据库相关
    new TioAdminDbConfiguration().config();
  }
}
