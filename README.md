# 卡码网盘（NetworkDisk）

前后端分离的网盘项目。

## 目录

- `backend`：Spring Boot 3.5 + Java 21 后端 REST API
- `frontend`：独立前端工程目录
- `需求文档.md`：当前业务需求

## 启动后端

```powershell
cd backend
mvn spring-boot:run
```

## 同时启动前后端

双击项目根目录的 `start-dev.bat` 即可。脚本会分别打开两个终端窗口，启动后端 `9090` 和前端 `5173`。

首次使用前请确认已在 `frontend` 目录执行过 `npm install`。

服务默认运行在 `http://localhost:9090`。

当前已提供注册接口：`POST /api/v1/auth/register`。注册数据会写入 MySQL，密码使用 BCrypt 哈希存储。

认证接口还包括：

- `POST /api/v1/auth/email-code`：发送注册邮箱验证码。
- `POST /api/v1/auth/login`：使用 `username` 和 `password` 登录，不支持邮箱登录。

## MySQL 配置

先创建数据库：

```sql
CREATE DATABASE network_disk DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
```

默认连接为 `localhost:3306`，用户名 `root`，密码 `123456`。也可以通过 `MYSQL_URL`、`MYSQL_USERNAME`、`MYSQL_PASSWORD` 环境变量覆盖配置。
