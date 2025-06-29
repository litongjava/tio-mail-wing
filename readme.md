# tio-mail-wing

[![GitHub Repo](https://img.shields.io/badge/repo-litongjava/tio--mail--wing-blue)](https://github.com/litongjava/tio-mail-wing)

`tio-mail-wing` 是一个基于 Java 的轻量级邮件服务器，实现了 IMAP 和 SMTP 协议。

## 特性

* **IMAP 支持**：IMAP4rev1，包含 UIDPLUS、MOVE、PEEK、特殊用法扩展。
* **SMTP 支持**：SMTP AUTH LOGIN/PLAIN 验证。
* **用户/邮箱管理**：通过数据库（PostgreSQL 等）管理用户和邮箱目录。
* **邮件存储**：去重存储消息内容，支持原子化 UID 分配。
* **扩展性**：可配置日志、端口、数据库连接等。

## 快速开始

### 系统要求

* Java 8 或更高版本
* Maven 3.x
* 支持的数据库（默认 PostgreSQL）

### 克隆与构建

```bash
git clone https://github.com/litongjava/tio-mail-wing.git
cd tio-mail-wing
mvn clean package
```

### 运行

```bash
java -jar target/tio-mail-wing.jar
```

## 配置

默认配置文件 `application.properties` 中，可配置以下关键参数：

| 参数                           | 描述             | 示例     |
| ---------------------------- | -------------- | ------ |
| `mail.server.imap.port`      | IMAP 服务监听端口    | `143`  |
| `mail.server.smtp.port`      | SMTP 服务监听端口    | `25`   |

示例：

```properties
# IMAP 和 SMTP 端口
mail.server.imap.port=143
mail.server.smtp.port=25
jdbc.url=jdbc:postgresql://192.168.3.9/defaultdb
jdbc.user=postgres
jdbc.pswd=00000000
jdbc.MaximumPoolSize=2
```

## 使用示例

* **IMAP 客户端连接**：

  * 主机：`localhost`
  * 端口：`${mail.server.imap.port}`
  * 用户名/密码：在数据库中配置的用户

* **SMTP 客户端连接**：

  * 主机：`localhost`
  * 端口：`${mail.server.smtp.port}`
  * 验证方式：AUTH LOGIN/PLAIN

## 数据库初始化

项目中提供了 `db/schema.sql`，和 `db/truncate.sql`包括建表语句和必要的视图。请根据实际情况在数据库中执行：

```bash

```

## 贡献

欢迎提交 Issue 或 Pull Request！

1. Fork 本仓库
2. 新建分支 `feature/xxx`
3. 提交改动并发起 Pull Request

## 许可证

本项目采用 Apache-2.0 许可证，详情请参见 [LICENSE](LICENSE)。
