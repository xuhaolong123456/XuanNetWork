# Docker 源码部署

服务器从 Git 拉取项目源码后，在项目根目录执行 Docker Compose。Compose 会在服务器上构建 Spring Boot 后端和 Vue 前端镜像，并启动 MySQL、Redis、后端与 Nginx 前端。

## 首次部署

1. 在服务器安装 Docker Compose 插件，并克隆项目。
2. 在项目根目录复制 `.env.example` 为 `.env`，设置数据库、Redis 和应用密钥。不要把 `.env` 提交到 Git。
3. `MYSQL_PASSWORD`、`MYSQL_ROOT_PASSWORD` 和 `REDIS_PASSWORD` 应使用不同的强密码。`AUTH_TOKEN_SECRET` 与 `APP_REDIS_KEY_SECRET` 使用至少 32 字节随机数的标准 Base64 编码，且两者应不同。
4. 若通过 HTTPS 提供服务，将 `AUTH_COOKIE_SECURE` 设为 `true`，并在本 Compose 服务前配置 HTTPS 反向代理。仅使用 HTTP 时保持 `false`。
5. 执行：

```sh
docker compose up -d --build
docker compose ps
docker compose logs -f backend
```

浏览器访问 `http://服务器地址/`。如需更换 HTTP 端口，修改 `.env` 中的 `HTTP_PORT`。

## 邮件验证码

邮件发送默认关闭。需要开放注册时，在服务器 `.env` 中设置 `MAIL_ENABLED=true`、`MAIL_USERNAME`、`MAIL_PASSWORD`、`MAIL_FROM`，并按需调整 `MAIL_HOST`、`MAIL_PORT`。邮件服务凭据只放在服务器 `.env`，不要提交到仓库。

## 更新部署

```sh
git pull
docker compose up -d --build
```

MySQL、Redis 和上传文件分别保存在 Docker 命名卷中，重建容器不会清空这些数据。维护前应备份数据库和上传文件。停止服务使用 `docker compose down`；该命令保留数据卷。不要使用 `docker compose down -v`，除非确实要删除持久化数据。

数据库和 Redis 未发布到宿主机端口，只能由 Compose 内部服务访问。首次部署时请在云服务器防火墙开放配置的 HTTP/HTTPS 端口。
