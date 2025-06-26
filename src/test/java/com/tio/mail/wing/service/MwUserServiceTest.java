package com.tio.mail.wing.service;

import org.junit.Test;

import com.litongjava.db.activerecord.Row;
import com.litongjava.jfinal.aop.Aop;
import com.litongjava.tio.boot.testing.TioBootTest;
import com.tio.mail.wing.config.MwAdminAppConfig;

public class MwUserServiceTest {

  @Test
  public void testUserExists() {
    TioBootTest.runWith(MwAdminAppConfig.class);
    boolean userExists = Aop.get(MwUserService.class).userExists("user3@tio.com");
    System.out.println(userExists);
  }

  @Test
  public void testAuthenticate() {
    TioBootTest.runWith(MwAdminAppConfig.class);
    boolean userExists = Aop.get(MwUserService.class).authenticate("user1@tio.com", "00000000");
    System.out.println(userExists);
  }
  
  @Test
  public void testGetUserByUsername() {
    TioBootTest.runWith(MwAdminAppConfig.class);
    Row userByUsername = Aop.get(MwUserService.class).getUserByUsername("user1@tio.com");
    System.out.println(userByUsername);
    
  }
}
