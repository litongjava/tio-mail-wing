package com.tio.mail.wing.config;

public class MwProtectConfig {

  public void config() {
    new SmtpServerConfig().startSmtpServer();
    new ImapServerConfig().startImapServer();
  }
}
