# Docker 源码部署

服务器从 Git 拉取项目源码后，在项目根目录运行 Docker Compose。Compose 会在服务器上构建 Spring Boot 后端和 Vue 前端镜像，并启动 MySQL、Redis、Elasticsearch、后端与 Nginx 前端。Elasticsearch 使用持久化数据卷，且只接入内部网络，由后端容器访问，不发布宿主机端口。

## 首次部署

1. 在服务器安装 Docker Compose 插件并克隆项目。
2. 将 `.env.example` 复制为 `.env`，配置数据库、Redis 和应用密钥。不要把 `.env` 提交到 Git。
3. `MYSQL_PASSWORD`、`MYSQL_ROOT_PASSWORD` 和 `REDIS_PASSWORD` 使用不同的强密码。`AUTH_TOKEN_SECRET` 与 `APP_REDIS_KEY_SECRET` 使用不同的随机 Base64 密钥。
4. 如果通过 HTTPS 提供服务，将 `AUTH_COOKIE_SECURE` 设为 `true`，并在 Compose 服务前配置 HTTPS 反向代理。仅使用 HTTP 时保持 `false`。
5. 构建并启动：

```sh
docker compose up -d --build
docker compose ps
docker compose logs -f backend
```

浏览器访问 `http://服务器地址/`。如需更换 HTTP 端口，修改 `.env` 中的 `HTTP_PORT`。

## Elasticsearch 搜索服务

Compose 使用单节点 Elasticsearch 8.19.23，数据保存在 `elasticsearch-data` 卷中。后端通过 `http://elasticsearch:9200` 连接，Spring 配置也支持通过 `SPRING_ELASTICSEARCH_URIS` 修改地址。当前 Compose 配置关闭 ES 自身认证，仅把它放在独立的内部网络中，并且只将后端接入该网络；不要为 ES 增加公网端口映射。生产环境应进一步启用认证和 TLS。

启动或更新：

```sh
docker compose up -d --build
docker compose ps
docker compose logs -f elasticsearch
```

目前此变更只加入 ES 容器与 Spring Data Elasticsearch 客户端依赖，文件搜索接口、索引映射和数据库同步尚未实现。
## 邮箱验证码

Compose 默认启用邮件发送。服务器 `.env` 必须配置有效的 SMTP 账号和授权码：

```env
MAIL_ENABLED=true
MAIL_HOST=smtp.qq.com
MAIL_PORT=587
MAIL_USERNAME=发件邮箱
MAIL_PASSWORD=邮箱SMTP授权码
MAIL_FROM=发件邮箱
```

SMTP 凭据只保存在服务器 `.env`，不要写入代码或提交到仓库。更新配置后重建后端容器：

```sh
docker compose up -d --build backend
docker compose logs -f --since 5m backend
```

验证码发送成功日志表示 SMTP 客户端已接受发送请求，不保证最终进入收件箱。若收不到邮件，检查后端日志中的 SMTP 异常，并查看垃圾邮件或邮箱服务商的投递记录。

## 更新部署

```sh
git pull
docker compose up -d --build
```

MySQL、Redis 和上传文件分别保存在 Docker 命名卷中，重建容器不会清空这些数据。维护前应备份数据库和上传文件。使用 `docker compose down` 停止服务会保留数据卷；不要使用 `docker compose down -v`，除非确定要删除持久化数据。

数据库和 Redis 未发布到宿主机端口，只能由 Compose 内部服务访问。首次部署时请在云服务器防火墙开放配置的 HTTP/HTTPS 端口。

## Canal 数据同步

Compose 加入 `canal-server`（Canal 1.1.8）作为 MySQL binlog 订阅数据源，MySQL 已开启 binlog（ROW + FULL 行镜像）。Canal 本身只产出 binlog 流，写入 Elasticsearch 需要下游 adapter 或自定义客户端消费 TCP 端口 11111。

`.env` 需配置 Canal 的复制账号：

```env
CANAL_DB_USERNAME=canal
CANAL_DB_PASSWORD=一个强密码（避免含 | # & 反斜杠，会干扰启动脚本改写配置）
```

`canal` 用户由 `mysql/init/01-canal-user.sh` 在数据卷**首次初始化**时自动创建。若服务器 MySQL 数据卷已存在，初始化脚本不会执行，需手动补一次授权（密码替换为 `.env` 里的 `CANAL_DB_PASSWORD`）：

```sql
CREATE USER IF NOT EXISTS 'canal'@'%' IDENTIFIED BY '<密码>';
GRANT SELECT, REPLICATION SLAVE, REPLICATION CLIENT ON *.* TO 'canal'@'%';
FLUSH PRIVILEGES;
```

更新后重建并查看 Canal 日志确认连接成功：

```sh
docker compose up -d --build
docker compose logs -f canal-server
```

看到 `successful to connect` 或实例启动日志即表示 Canal 已订阅到 MySQL。查看订阅进度可执行 `docker compose exec canal-server tail -f /home/admin/canal-server/logs/example/example.log`。

