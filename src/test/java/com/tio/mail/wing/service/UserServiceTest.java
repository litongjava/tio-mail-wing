package com.tio.mail.wing.service;

import org.junit.Test;

import com.litongjava.tio.utils.digest.Sha256Utils;

public class UserServiceTest {

  @Test
  public void test() {
    String hashedPassword = Sha256Utils.hashPassword("00000000");
    System.out.println(hashedPassword);
  }

}
