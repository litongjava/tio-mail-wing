package com.tio.mail.wing;

import com.litongjava.tio.boot.TioApplication;
import com.tio.mail.wing.config.MwBootConfig;

public class TioMailWingApplication {
  public static void main(String[] args) {
    MwBootConfig mwBootConfig = new MwBootConfig();
    TioApplication.run(TioMailWingApplication.class, mwBootConfig, args);
  }
}
