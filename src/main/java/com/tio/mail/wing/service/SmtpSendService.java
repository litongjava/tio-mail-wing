package com.tio.mail.wing.service;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Hashtable;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import javax.mail.Message;
import javax.mail.MessagingException;
import javax.mail.Session;
import javax.mail.Transport;
import javax.mail.internet.InternetAddress;
import javax.mail.internet.MimeMessage;
import javax.naming.Context;
import javax.naming.directory.Attribute;
import javax.naming.directory.Attributes;
import javax.naming.directory.DirContext;
import javax.naming.directory.InitialDirContext;

import lombok.extern.slf4j.Slf4j;

@Slf4j
public class SmtpSendService {

  public List<String> lookupMX(String domain) throws Exception {
    Hashtable<String, String> env = new Hashtable<>();
    env.put(Context.INITIAL_CONTEXT_FACTORY, "com.sun.jndi.dns.DnsContextFactory");

    DirContext dirContext = new InitialDirContext(env);
    Attributes attrs = dirContext.getAttributes(domain, new String[] { "MX" });
    Attribute attr = attrs.get("MX");
    List<String> mxHosts = new ArrayList<>();
    if (attr != null) {
      for (int i = 0; i < attr.size(); i++) {
        // MX 格式： "10 mail.example.com."
        String[] parts = attr.get(i).toString().split("\\s+");
        mxHosts.add(parts[1].endsWith(".") ? parts[1].substring(0, parts[1].length() - 1) : parts[1]);
      }
    }
    // 如果没有 MX，回退到 A 记录：
    if (mxHosts.isEmpty()) {
      attrs = dirContext.getAttributes(domain, new String[] { "A" });
      attr = attrs.get("A");
      if (attr != null)
        mxHosts.add(attr.get(0).toString());
    }
    return mxHosts;
  }

  /**
   * 
   * @param fromAddress
   * @param externalRecipients
   * @param mailData
   */
  public void sendExternalMail(String from, List<String> recipients, String mailData) {
    // 按 @domain 分组
    Map<String, List<String>> byDomain = new HashMap<>();
    for (String rcpt : recipients) {
      // 提取 @ 之后的域名
      String domain = rcpt.substring(rcpt.indexOf('@') + 1);
      // 如果还没放过这个域，就先初始化一个空列表
      if (!byDomain.containsKey(domain)) {
        byDomain.put(domain, new ArrayList<>());
      }
      // 再把收件人加入对应域名的列表
      byDomain.get(domain).add(rcpt);
    }

    for (Map.Entry<String, List<String>> entry : byDomain.entrySet()) {
      String domain = entry.getKey();
      List<String> toList = entry.getValue();
      try {
        // lookup MX
        List<String> mxHosts;
        try {
          mxHosts = this.lookupMX(domain);
        } catch (Exception e) {
          log.error("DNS lookup failed for domain {}", domain);
          e.printStackTrace();
          continue;
        }

        if (mxHosts.isEmpty()) {
          log.warn("No MX records for domain {}, skip", domain);
          continue;
        }
        String mxHost = mxHosts.get(0);

        // 配置 JavaMail Session
        Properties props = new Properties();
        props.put("mail.smtp.host", mxHost);
        props.put("mail.smtp.port", "25");
        Session jmSession = Session.getInstance(props);

        // 用 raw DATA 构造 MimeMessage（需要客户端在 DATA 阶段提交完整的 headers + body）
        MimeMessage msg = new MimeMessage(jmSession, new ByteArrayInputStream(mailData.getBytes(StandardCharsets.UTF_8)));
        // 强制设置 From / To，防止客户端 DATA 阶段缺失
        msg.setFrom(from.contains("<") ? new InternetAddress(from) : new InternetAddress(from));
        for (String to : toList) {
          msg.addRecipient(Message.RecipientType.TO, new InternetAddress(to));
        }

        // 发送
        Transport.send(msg);
        log.info("Delivered mail from {} to {} via {}", from, toList, mxHost);

      } catch (MessagingException me) {
        log.error("JavaMail send failed to {}: {}", entry.getValue(), me.getMessage(), me);
      }
    }
  }
}
