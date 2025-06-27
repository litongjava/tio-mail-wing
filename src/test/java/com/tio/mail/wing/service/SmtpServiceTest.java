package com.tio.mail.wing.service;

import org.junit.Test;

import com.litongjava.tio.utils.base64.Base64Utils;

public class SmtpServiceTest {

  @Test
  public void test() {
    String encodeToString = Base64Utils.encodeToString("00000000");
    System.out.println(encodeToString);
  }

}
