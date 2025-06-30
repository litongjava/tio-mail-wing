package com.tio.mail.wing.service;

import java.time.OffsetDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import com.litongjava.db.activerecord.Db;
import com.litongjava.db.activerecord.Row;
import com.litongjava.template.SqlTemplates;
import com.tio.mail.wing.model.Email;

public class MailFlagService {
  
  public List<Email> getActiveMailFlags(Long mailboxId) {
    String sql = SqlTemplates.get("mailbox.getActiveMailFlags");
    List<Row> mailRows = Db.find(sql, mailboxId);
    return mailRows.stream().map(this::rowToEmailWithAggregatedFlags).collect(Collectors.toList());
  }
  
  /**
  * 将数据库行（包含聚合后的标志数组）转换为 Email DTO 对象。
  */
  public Email rowToEmailWithAggregatedFlags(Row row) {
    Email email = new Email();
    email.setId(row.getLong("id"));
    email.setUid(row.getLong("uid"));
    email.setRawContent(row.getStr("raw_content"));
    Integer sizeInBytes = row.getInt("size_in_bytes");
    if (sizeInBytes != null) {
      email.setSize(sizeInBytes);
    }

    // 新增：设置序列号
    if (row.get("sequence_number") != null) {
      email.setSequenceNumber(row.getInt("sequence_number"));
    }

    OffsetDateTime internalDate = row.getOffsetDateTime("internal_date");
    if (internalDate != null) {
      email.setInternalDate(internalDate);
    }
    String[] flagsArray = row.getStringArray("flags");

    if (flagsArray != null) {
      Set<String> flags = new HashSet<>(flagsArray.length);
      for (String string : flagsArray) {
        flags.add(string);
      }
      email.setFlags(flags);
    }

    return email;
  }


}
