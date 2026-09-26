# XSS 与 CSRF 防护方案

本文说明当前浏览器认证链路中登录 Cookie、CSRF Token 和浏览器策略的职责。实现主要位于 `backend/src/main/java/com/networkdisk/auth/AuthCookie.java`、`backend/src/main/java/com/networkdisk/config/SecurityConfig.java`、`backend/src/main/java/com/networkdisk/auth/AuthController.java` 和 `frontend/src/api/auth.js`。

## 防护边界

- **XSS（跨站脚本）**：登录 JWT 存入 `NETWORKDISK_AUTH` HttpOnly Cookie，前端 JavaScript 无法直接读取令牌。该措施降低令牌被脚本窃取后在站外重放的风险，但不能阻止注入脚本借用当前浏览器的登录状态发请求。
- **CSRF（跨站请求伪造）**：浏览器会自动附加认证 Cookie。对于 POST 等不安全请求，Spring Security 要求请求携带匹配的 CSRF Token，减少攻击者借用户浏览器伪造操作的风险。
- **SameSite**：登录 Cookie 设置为 `SameSite=Lax`，作为额外的浏览器侧防线。跨站安全方法导航（例如 GET 链接）仍可能带 Cookie，因此 GET 接口必须保持只读。

CSRF Token 不是登录凭证。`XSRF-TOKEN` Cookie 故意可由前端读取；认证 Cookie 则保持 HttpOnly。站点一旦存在 XSS，恶意脚本可能读取 CSRF Token 并代用户发请求，所以 CSRF 防护不能替代 XSS 预防。

## Cookie 属性

| Cookie | 用途 | 属性 |
| --- | --- | --- |
| `NETWORKDISK_AUTH` | 携带 JWT，作为登录凭证 | `HttpOnly`、`SameSite=Lax`、`Path=/`；有效期与 JWT TTL 一致；HTTPS 部署时启用 `Secure` |
| `XSRF-TOKEN` | 供前端读取并回传，配合服务端校验请求 | 由 Spring Security `CookieCsrfTokenRepository` 管理；`SameSite=Lax`、`Path=/`；脚本可读 |

登录响应只返回用户 ID 和用户名，不在 JSON 或 `localStorage` 中暴露 JWT。后端认证过滤器只从 `NETWORKDISK_AUTH` 读取令牌。登出时 Redis 删除当前 JWT 摘要对应的 allowlist 项并清除认证 Cookie；改密时密码哈希版本变化，使旧 JWT 全部失效，并清除当前认证 Cookie。

## 请求流程

1. 前端首次发写请求前调用 `GET /api/v1/auth/csrf`。
2. Spring Security 创建 CSRF Token，并通过 `XSRF-TOKEN` Cookie 保存；Controller 同时将 Token 返回给前端。
3. 前端把 Token 暂存在模块内存中，所有 POST 请求在 `X-XSRF-TOKEN` 请求头中回传它。注册、邮件验证码、登录、登出和改密请求均使用同一封装。
4. Spring Security 对不安全 HTTP 方法执行 CSRF 校验。Token 缺失或不匹配时，请求会在进入 Controller 前被拒绝。
5. 已登录请求由浏览器自动附带 HttpOnly 认证 Cookie，JWT 过滤器再校验签名、有效期、Redis 撤销状态和当前凭据版本。

## 退出与再次登录

退出使用 `POST /api/v1/auth/logout`，请求必须携带当前 `NETWORKDISK_AUTH` Cookie 和 `X-XSRF-TOKEN`。Controller 调用认证服务撤销当前令牌，再设置过期的认证 Cookie；该操作只影响当前会话。Spring Security 同时清除 `XSRF-TOKEN` Cookie。

CSRF token 在前端模块内存中缓存。退出后该缓存值可能已失效，因此前端若收到没有业务错误码的 403（CSRF Filter 拒绝，请求未进入 Controller），会调用 `GET /api/v1/auth/csrf` 获取新 token，并仅对本次登录请求重试一次。带业务错误码的 403（例如验证码缺失）按业务错误处理，不触发 CSRF 重试。成功退出或服务端返回 401 后，前端再清除本地用户展示信息；其他退出错误会保留当前状态并提示用户。

登录成功后，前端跳转 `/drive`，路由守卫调用受保护的 `GET /api/v1/auth/me` 确认 Cookie 对应的会话并获取用户名。`/me` 是只读请求，不需要 CSRF header。

对应回归测试：`backend/src/test/java/com/networkdisk/config/SecurityFilterChainRegressionTest.java` 覆盖登录 Cookie、退出后旧 Cookie 被拒、旧 CSRF token 得到 403、换取新 CSRF token 后重新登录成功；`backend/src/test/java/com/networkdisk/auth/AuthServiceTest.java` 和 `ActiveTokenRepositoryTest.java` 覆盖仅撤销当前 token 及删除相应 Redis 摘要键。

## 配置与开发注意事项

- 本地 HTTP 开发默认 `app.auth.cookie-secure=false`。生产环境通过环境变量 `AUTH_COOKIE_SECURE=true` 启用 `Secure`，并必须使用 HTTPS。
- 不要关闭 Spring Security CSRF，也不要在前端请求中移除 `X-XSRF-TOKEN`。
- GET、HEAD 等安全方法不得修改数据或执行账户操作。
- Vue 普通文本插值会进行转义；不要将不可信数据传入 `v-html`。当前项目没有额外配置 CSP 响应头或 HTML 清理库，后续如加入富文本展示，应采用明确的清理策略并评估 CSP。
- HttpOnly 不会阻止 XSS 执行或阻止脚本代表用户操作。仍需避免不可信 HTML 注入，并对用户内容进行上下文适配的输出处理。
