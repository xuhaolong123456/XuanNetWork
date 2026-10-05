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
- `GET /api/v1/auth/captcha`：获取登录图片验证码；需携带前端生成且持久化的 `X-Device-Id`。
- `POST /api/v1/auth/login`：使用 `username`、`password` 和一次性 `captchaCode` 登录，不支持邮箱登录；需携带 `X-Device-Id`。登录请求同时按设备和 IP 限流。
- `GET /api/v1/auth/csrf`：初始化 CSRF 校验，前端 POST 请求会自动附带 `X-XSRF-TOKEN`。
- `POST /api/v1/auth/change-password`：浏览器自动携带 HttpOnly 登录 Cookie，并提交当前密码、新密码；成功后旧令牌失效并清除 Cookie。
- `POST /api/v1/auth/logout`：浏览器自动携带 HttpOnly 登录 Cookie；成功后当前令牌立即失效并清除 Cookie。

当前全部接口的 Apifox 导入文件：[docs/networkdisk-apifox-openapi.json](docs/networkdisk-apifox-openapi.json)。在 Apifox 中选择“项目设置 → 导入数据 → OpenAPI/Swagger”，上传此文件即可。

文件列表接口为 `GET /api/v1/files`，支持 `parentId`、`page` 和 `size` 参数；省略 `parentId` 时返回根目录。查询只返回当前登录用户的文件，前端点击文件夹或面包屑即可切换目录。

开发环境需要初始化示例管理员账号和专属文件时，启动后端前设置 `APP_DEMO_SEED_ADMIN=true` 与 `APP_DEMO_ADMIN_PASSWORD`。初始化只在 `dev` 配置下运行，密码以 BCrypt 哈希保存；重复启动不会重复创建同名示例文件。初始化完成后可移除这两个环境变量。示例文件为根目录中的 `管理员示例文件.txt`，当前文件列表仅展示其元数据。

登录成功后，后端通过 `HttpOnly; SameSite=Lax` Cookie 设置 JWT，令牌不会出现在 JSON 响应中，也不能由前端脚本读取。浏览器会自动在后续请求中携带 Cookie。Spring Security 同时要求不安全请求携带 CSRF token，并验证 JWT 的 HS256 签名、过期时间、Redis 有效状态和当前密码版本；验证通过后，当前用户 ID 位于 `SecurityContext` 的 `Authentication.principal`（`Long`）。无有效令牌的请求返回 `401` 和 `UNAUTHORIZED`。生产 HTTPS 部署需设置 `AUTH_COOKIE_SECURE=true`。

XSS 与 CSRF 防护边界和请求流程见[安全防护说明](docs/xss-csrf-protection.md)。

## MySQL 配置

先创建数据库：

```sql
CREATE DATABASE network_disk DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
```

默认连接为 `localhost:3306`，用户名 `root`，密码 `123456`。也可以通过 `MYSQL_URL`、`MYSQL_USERNAME`、`MYSQL_PASSWORD` 环境变量覆盖配置。
"test deploy" 
