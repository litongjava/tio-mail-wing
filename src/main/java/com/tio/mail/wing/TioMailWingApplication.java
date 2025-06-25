package com.tio.mail.wing;

import com.litongjava.annotation.AComponentScan;
import com.litongjava.tio.boot.TioApplication;

@AComponentScan
public class TioMailWingApplication {

  public static void main(String[] args) {
    TioApplication.run(TioMailWingApplication.class, args);
  }
}
