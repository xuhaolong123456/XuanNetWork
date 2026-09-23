# 登录接口前后端链路

## 1. 文档目的

本文档记录卡码网盘登录功能的前后端调用链路、接口约定和实现步骤，作为后续开发和联调依据。

当前工程已经具备：

- Vue 登录页面
- Spring Boot 认证模块基础结构
- MySQL 用户表和 BCrypt 密码加密能力
- 统一接口响应结构 `Result`

当前工程尚未具备：

- 登录状态校验
- 登出接口

## 2. 登录业务流程

```text
用户打开 /login
        ↓
填写用户名和密码
        ↓
前端校验必填项和格式
        ↓
POST /api/v1/auth/login
        ↓
AuthController 接收请求
        ↓
AuthService 按用户名查询用户
        ↓
BCrypt 校验密码
        ↓
生成登录凭证 Token
        ↓
返回用户信息和 Token
        ↓
前端保存 Token
        ↓
跳转网盘首页
        ↓
后续请求携带 Authorization: Bearer <token>
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

建议在以下文件中统一封装：

```text
frontend/src/api/auth.js
```

请求示例：

```javascript
export async function login(payload) {
  const response = await fetch('/api/v1/auth/login', {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json'
    },
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

1. 保存 Token。
2. 保存当前用户基本信息。
3. 跳转到网盘首页，例如 `/drive`。
4. 后续请求自动携带 Token。

推荐使用 `localStorage` 保存开发阶段的 Token：

```javascript
localStorage.setItem('access_token', data.accessToken)
```

生产环境需要结合安全要求评估 Token 存储方式，优先考虑 HttpOnly Cookie，避免 Token 被前端脚本直接读取。

### 3.5 后续请求携带 Token

```javascript
const token = localStorage.getItem('access_token')

fetch('/api/v1/files', {
  headers: {
    Authorization: `Bearer ${token}`
  }
})
```

## 4. 后端链路

### 4.1 请求对象

建议新增：

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
        String password
) {
}
```

### 4.2 登录接口

接口地址：

```http
POST /api/v1/auth/login
Content-Type: application/json
```

请求体：

```json
{
  "username": "user001",
  "password": "123456"
}
```

### 4.3 Controller 层

文件：

```text
backend/src/main/java/com/networkdisk/auth/AuthController.java
```

Controller 只负责：

- 接收请求
- 触发参数校验
- 调用 `AuthService`
- 返回统一响应

示例：

```java
@PostMapping("/login")
public Result<LoginResponse> login(@Valid @RequestBody LoginRequest request) {
    return Result.success(authService.login(request));
}
```

### 4.4 Service 层

文件：

```text
backend/src/main/java/com/networkdisk/auth/AuthService.java
```

核心步骤：

1. 用户名标准化：去除首尾空格。
2. 通过 `UserRepository` 按用户名查询用户。
3. 用户不存在时返回统一登录失败信息。
4. 使用 `BCryptPasswordEncoder.matches()` 校验密码。
5. 密码错误时返回统一登录失败信息。
6. 校验成功后生成 Token。
7. 返回 Token、用户 ID 和用户名。

伪代码：

```java
String username = request.username().trim();
User user = userRepository.findByNickName(username)
        .orElseThrow(() -> new AuthBusinessException("LOGIN_FAILED", "用户名或密码错误"));

if (!passwordEncoder.matches(request.password(), user.getPasswordHash())) {
    throw new AuthBusinessException("LOGIN_FAILED", "用户名或密码错误");
}

String accessToken = tokenService.create(user);
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
    "accessToken": "eyJhbGciOiJIUzI1NiJ9...",
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

登录成功后，文件列表、上传、分享和回收站等接口都需要经过认证过滤器：

```text
前端携带 Authorization 请求头
        ↓
JWT 过滤器解析 Token
        ↓
校验签名和过期时间
        ↓
获取 userId
        ↓
写入当前请求上下文
        ↓
Controller 执行业务
```

Token 无效或过期时返回：

```text
401 Unauthorized
```

## 7. 登出链路

如果采用无状态 JWT：

1. 前端删除本地 Token。
2. 清除当前用户信息。
3. 跳转到 `/login`。

如果采用 Redis Session 或 Token 黑名单，还需要后端提供：

```http
POST /api/v1/auth/logout
Authorization: Bearer <token>
```

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

已完成用户名密码登录、令牌签发、Vue 登录页接入和登录接口测试。

后续仍需在受保护业务接口上增加令牌校验过滤器、前端请求统一携带令牌，以及登录后的业务首页路由守卫。
