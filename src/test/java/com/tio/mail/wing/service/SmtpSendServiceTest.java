package com.tio.mail.wing.service;

import java.util.List;

import org.junit.Test;

import com.litongjava.jfinal.aop.Aop;

public class SmtpSendServiceTest {

  @Test
  public void lookupMX() {
//    try {
//      List<String> mxs = Aop.get(SmtpSendService.class).lookupMX("gmail.com");
//      System.out.println(mxs);
//    } catch (Exception e) {
//      e.printStackTrace();
//    }
    
    try {
      List<String> mxs = Aop.get(SmtpSendService.class).lookupMX("litong.xyz");
      System.out.println(mxs);
    } catch (Exception e) {
      e.printStackTrace();
    }
  }

}
