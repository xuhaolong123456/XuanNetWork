# 登录接口前后端链路

## 1. 文档目的

本文档记录卡码网盘登录功能的前后端调用链路、接口约定和实现步骤，作为后续开发和联调依据。

当前工程已经具备：

- Vue 登录页面
- Spring Boot 认证模块基础结构
- MySQL 用户表和 BCrypt 密码加密能力
- 统一接口响应结构 `Result`

当前工程已具备登录状态校验、Redis 令牌撤销和登出接口。认证 JWT 通过 HttpOnly Cookie 传递，浏览器 POST 请求使用 CSRF token。

## 2. 登录业务流程

```text
用户打开 /login
        ↓
前端获取 CSRF token 和绑定用户名/设备的图片验证码
        ↓
POST /api/v1/auth/login（X-Device-Id、X-XSRF-TOKEN、验证码）
        ↓
AuthController 调用 AuthService
        ↓
AuthService 执行 IP/设备限流、消费验证码、查询用户名
        ↓
BCrypt 校验密码，生成 JWT
        ↓
Redis 写入 auth:token:<SHA-256(token)> → userId（有效期与 JWT 一致）
        ↓
后端设置 NETWORKDISK_AUTH HttpOnly Cookie，JSON 只返回 userId/username
        ↓
前端跳转 /drive；路由守卫请求 GET /api/v1/auth/me 确认会话
        ↓
浏览器后续自动携带 Cookie；POST 请求另带 X-XSRF-TOKEN
```

## 3. 前端链路

### 3.1 页面入口

页面文件：

```text
frontend/src/views/LoginView.vue
```

路由：

```text
/login
```

当前页面包含：

- 用户名输入框
- 密码输入框
- 登录按钮
- 注册页面跳转入口

### 3.2 前端校验

点击登录时，前端应先校验：

| 字段 | 校验规则 | 提示信息 |
|---|---|---|
| 用户名 | 不能为空 | 请输入用户名 |
| 用户名 | 长度为 3-32 位 | 用户名长度为3-32位 |
| 密码 | 不能为空 | 请输入密码 |
| 密码 | 长度为 6-64 位 | 密码长度为6-64位 |

校验失败时不发送请求。

### 3.3 请求封装

在以下文件统一处理 CSRF、设备 ID、Cookie 和错误响应：

```text
frontend/src/api/auth.js
```

登录请求携带同一设备的 ID 和 CSRF token；认证 JWT 不放进请求体或浏览器存储：

```javascript
export async function login(payload) {
  const response = await fetch('/api/v1/auth/login', {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      'X-Device-Id': deviceId(),
      'X-XSRF-TOKEN': await getCsrfToken()
    },
    credentials: 'same-origin',
    body: JSON.stringify(payload)
  })

  const result = await response.json()
  if (!response.ok || !result.success) {
    throw new Error(result.message || '登录失败，请稍后重试')
  }
  return result.data
}
```

### 3.4 登录成功处理

后端返回成功后，前端需要：

1. 浏览器自动保存 `NETWORKDISK_AUTH` HttpOnly Cookie，JavaScript 不能读取 JWT。
2. 将 `userId`、`username` 等非敏感展示信息保存为当前用户信息；它们不用于认证。
3. 跳转到 `/drive`。路由守卫调用 `GET /api/v1/auth/me`，从认证过滤器确认 Cookie 对应的用户后再进入网盘首页。
4. 后续请求由浏览器自动携带 Cookie；所有 POST 请求都需要在 `X-XSRF-TOKEN` 请求头中回传 CSRF token。

CSRF token 初始化示例：

```javascript
const response = await fetch('/api/v1/auth/csrf')
const result = await response.json()
const csrfToken = result.data
```

生产环境使用 HTTPS，并设置 `AUTH_COOKIE_SECURE=true`。登录 Cookie 使用 `HttpOnly`、`SameSite=Lax` 和根路径。

### 3.5 退出后重新登录

成功退出时，服务端会清除 `NETWORKDISK_AUTH` 和 `XSRF-TOKEN` Cookie。前端模块内存中的 CSRF token 可能仍是退出前的旧值，因此退出后第一次登录请求可能被 Spring Security 以 403 拒绝（请求尚未进入登录 Controller）。

前端登录请求在收到没有业务错误码的 403 时，重新请求 `GET /api/v1/auth/csrf`，再用新 token 重试一次。`CAPTCHA_REQUIRED` 等带业务错误码的响应按业务错误处理，不执行这个 CSRF 重试。CSRF 失败的重试仅用于登录请求；其他写请求仍需根据其业务语义单独处理。

若退出接口返回非 401 错误，前端保留当前用户界面和状态并提示退出失败；退出成功或服务端确认会话已失效（401）后，才清除本地用户信息并跳转登录页。

### 3.6 后续请求携带 Cookie

```javascript
fetch('/api/v1/auth/logout', {
  method: 'POST',
  credentials: 'same-origin',
  headers: {
    'Content-Type': 'application/json',
    'X-XSRF-TOKEN': csrfToken
  }
})
```

认证 Cookie 由浏览器自动携带，前端不得通过 JavaScript 读取 JWT。当前工程还没有文件列表等业务接口，上面的受保护请求示例仅用于说明认证传递方式。

## 4. 后端链路

### 4.1 请求对象

当前请求 DTO：

```text
backend/src/main/java/com/networkdisk/auth/LoginRequest.java
```

```java
public record LoginRequest(
        @NotBlank(message = "请输入用户名")
        @Size(min = 3, max = 32, message = "用户名长度为3-32位")
        String username,

        @NotBlank(message = "请输入密码")
        @Size(min = 6, max = 64, message = "密码长度为6-64位")
        String password,

        @NotBlank(message = "请输入验证码")
        String captchaCode
) {
}
```

### 4.2 登录接口

接口地址：

```http
POST /api/v1/auth/login
Content-Type: application/json
X-Device-Id: <device UUID>
X-XSRF-TOKEN: <CSRF token>
```

请求体：

```json
{
  "username": "user001",
  "password": "123456",
  "captchaCode": "2345"
}
```

### 4.3 Controller 层

文件：

```text
backend/src/main/java/com/networkdisk/auth/AuthController.java
```

Controller 负责：

- 接收请求
- 触发参数校验
- 调用 `AuthService`
- 设置认证 Cookie 并返回统一响应

示例：

```java
// AuthService 返回的 JWT 仅写入 HttpOnly Cookie；JSON 返回 userId 和 username。
AuthCookie.set(response, login.accessToken(), authService.tokenTtlSeconds(), authCookieSecure);
return Result.success(new LoginSessionResponse(login.userId(), login.username()));
```

### 4.4 Service 层

文件：

```text
backend/src/main/java/com/networkdisk/auth/AuthService.java
```

核心步骤：

1. 标准化用户名和设备 ID。
2. 检查 IP 与设备登录请求频率、账号失败锁定状态。
3. 从 Redis 一次性校验与用户名、设备绑定的图片验证码。
4. 通过 `UserRepository` 按用户名查询用户，并用 `BCryptPasswordEncoder.matches()` 校验密码。
5. 校验成功后生成 JWT，并将 `SHA-256(token)` 摘要作为 Redis key 写入 `auth:token:<digest>`，value 为 userId，TTL 与 JWT 一致。
6. 返回 JWT、用户 ID 和用户名给 Controller；Controller 只将 JWT 写入 HttpOnly Cookie，响应 JSON 只包含用户 ID 和用户名。

伪代码：

```java
String username = request.username().trim();
User user = userRepository.findByNickName(username)
        .orElseThrow(() -> new AuthBusinessException("LOGIN_FAILED", "用户名或密码错误"));

if (!passwordEncoder.matches(request.password(), user.getPasswordHash())) {
    throw new AuthBusinessException("LOGIN_FAILED", "用户名或密码错误");
}

String accessToken = tokenService.create(user.getId(), user.getPasswordHash());
activeTokenRepository.activate(accessToken, user.getId(), tokenService.ttlSeconds());
return new LoginResponse(accessToken, user.getId(), user.getNickName());
```

登录失败统一返回“用户名或密码错误”，不要区分“用户名不存在”和“密码错误”，避免泄露账号是否存在。

### 4.5 Repository 层

用户仓储按用户名查询：

```java
Optional<User> findByNickName(String username);
```

查询对象为 MySQL 中的：

```text
sys_user
```

密码字段是 `password_hash`，只能用于 BCrypt 比对，不能返回给前端。

## 5. 返回结构

### 5.1 登录成功

HTTP 状态码：

```text
200 OK
```

响应示例：

```json
{
  "success": true,
  "code": "OK",
  "message": "操作成功",
  "data": {
    "userId": 1,
    "username": "user001"
  }
}
```

### 5.2 登录失败

HTTP 状态码：

```text
401 Unauthorized
```

响应示例：

```json
{
  "success": false,
  "code": "LOGIN_FAILED",
  "message": "用户名或密码错误",
  "data": null
}
```

### 5.3 参数错误

HTTP 状态码：

```text
400 Bad Request
```

响应示例：

```json
{
  "success": false,
  "code": "INVALID_PARAM",
  "message": "请输入用户名",
  "data": null
}
```

## 6. Token 校验链路

登录成功后，当前用户接口和后续受保护业务接口都经过认证过滤器：

```text
浏览器自动携带 HttpOnly 登录 Cookie
        ↓
CSRF Filter 校验不安全请求的 X-XSRF-TOKEN
        ↓
JWT 过滤器读取 Cookie 中的 Token
        ↓
校验签名和过期时间
        ↓
获取 userId
        ↓
检查 Redis 中该 Token 的 allowlist 记录
        ↓
确认 JWT 中的凭据版本仍匹配当前密码哈希
        ↓
写入当前请求上下文
        ↓
Controller 执行业务
```

`GET /api/v1/auth/me` 使用当前请求的认证 userId 查询用户名，作为刷新页面和路由守卫确认登录状态的接口。令牌在 Redis 中以 SHA-256 摘要键保存，原始 JWT 不作为 Redis key。

Token 无效或过期时返回：

```text
401 Unauthorized
```

## 7. 登出链路

后端从当前请求的 `NETWORKDISK_AUTH` Cookie 读取 JWT，删除对应的 Redis allowlist 键，并将登录 Cookie 设为过期。退出请求必须同时通过认证过滤器和 CSRF 校验：

```http
POST /api/v1/auth/logout
Cookie: NETWORKDISK_AUTH=<HttpOnly token>
X-XSRF-TOKEN: <CSRF token>
```

退出成功后，前端清除本地非敏感用户信息并跳转 `/login`。该操作只撤销当前 Cookie 对应的会话，不撤销同一账号的其他会话。退出响应还会清除 `XSRF-TOKEN`；下一次登录遇到无业务错误码的 403 时，前端重新获取 CSRF token 后重试一次。

## 8. 安全要求

- 密码只能使用 BCrypt 等安全哈希保存。
- 日志中不能打印明文密码或完整 Token。
- 登录失败使用统一错误提示。
- 登录接口需要限流，防止密码爆破。
- Token 应设置过期时间。
- 生产环境必须使用 HTTPS。
- 不在响应中返回 `passwordHash`。
- 用户登录成功后，文件资源必须按当前用户 ID 做权限隔离。

## 9. 当前状态

已完成验证码与限流、登录令牌签发及 Redis allowlist、Cookie 认证过滤器、`/me` 会话确认、退出撤销、CSRF 保护和登录后网盘空首页路由守卫。

回归覆盖位于 `backend/src/test/java/com/networkdisk/config/SecurityFilterChainRegressionTest.java`：登录 Cookie 下发、Cookie 认证、CSRF 拒绝、退出清除 Cookie 和旧令牌拒绝、刷新 CSRF 后重新登录。`AuthServiceTest` 和 `ActiveTokenRepositoryTest` 覆盖单会话撤销及 Redis 摘要键删除。
