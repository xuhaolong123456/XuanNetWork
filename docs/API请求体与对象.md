# API 请求体与对象速查

本文件按当前 Controller 和请求 DTO 整理，是填写 Apifox / Postman 请求的入口。

本地后端地址：`http://localhost:9090`。
项目已接入 Swagger UI：后端启动后访问 `http://localhost:9090/swagger-ui/index.html`；自动生成的 OpenAPI 定义地址为 `http://localhost:9090/v3/api-docs`，可导入 Apifox。

## 先设置认证和请求头

1. GET `/api/v1/auth/csrf`，保留响应设置的 Cookie，并复制响应中的 `data` 作为 CSRF 值。
2. 所有 POST、PUT、PATCH、DELETE 请求都添加 `X-XSRF-TOKEN: 上一步的值`，同时携带 `XSRF-TOKEN` Cookie。
3. 登录成功后，工具应保存 `NETWORKDISK_AUTH` Cookie，后续文件接口携带该 Cookie。当前项目不使用 Bearer Header 认证。
4. JSON 请求在 Body 中选择 JSON，设置 `Content-Type: application/json`。
5. 上传使用 form-data；由工具自动生成 Content-Type 和 multipart boundary。

`userId` 不需要放进请求体：后端从登录身份取得。示例中的资源 ID 都要替换成自己列表接口返回的真实 ID。

## 文件接口总表

| 操作 | 方法 | 路径 | 参数位置 | 请求对象 | 成功状态 |
| --- | --- | --- | --- | --- | --- |
| 普通列表 | GET | `/api/v1/files` | Query：parentId、page、size | 无请求体 | 200 |
| 创建文件夹 | POST | `/api/v1/files/directories` | JSON | CreateDirectoryRequest | 201 |
| 重命名文件夹 | PATCH | `/api/v1/files/directories/{directoryId}` | Path ID + JSON | RenameDirectoryRequest | 200 |
| 单删文件或文件夹 | PUT | `/api/v1/files/file/{id}` | Path ID | 无请求体 | 204 |
| 批量删除 | PUT | `/api/v1/files/files` | JSON | FileIdsRequest | 204 |
| 回收站列表 | GET | `/api/v1/files/recycle-bin` | Query：page、size | 无请求体 | 200 |
| 单条恢复 | PUT | `/api/v1/files/recover/{id}` | Path ID | 无请求体 | 204 |
| 批量恢复 | PUT | `/api/v1/files/recover/batch` | JSON | FileIdsRequest | 204 |
| 上传文件 | POST | `/api/v1/files/upload` | Query：parentId + form-data：file | MultipartFile | 201 |
| 兼容旧目录删除 | DELETE | `/api/v1/files/directories/{directoryId}` | Path ID | 无请求体 | 204 |

### 1. 查询文件列表

根目录：`GET /api/v1/files?page=0&size=50`。
子目录：`GET /api/v1/files?parentId=12&page=0&size=50`。

没有 Body。`page` 从 0 开始，`size` 为 1～100。根目录省略 `parentId`，不要传字符串 `"null"`。

### 2. 创建文件夹

`POST /api/v1/files/directories`

根目录创建，Body：

```json
{
  "parentId": null,
  "folderName": "学习资料"
}
```

在 ID 为 12 的文件夹中创建，Body：

```json
{
  "parentId": 12,
  "folderName": "Java笔记"
}
```

| 字段 | 类型 | 规则 |
| --- | --- | --- |
| parentId | 整数或 null | null / 省略代表根目录；提供时须为正数且属于当前用户的有效文件夹 |
| folderName | 字符串 | 必填，去除两端空白后非空，最长 255，不能为 `.` 或 `..`，不能含控制字符及 `< > : " / \ \| ? *` |

JSON 字段严格使用 `parentId`、`folderName`。同名会自动编号；不要传数据库字段 `parent_id`、`node_type`、`is_delete`。

### 3. 重命名文件夹

`PATCH /api/v1/files/directories/12`

```json
{
  "folderName": "新的文件夹名称"
}
```

只传新名称，资源 ID 在路径里。名称规则同创建；空名称返回 400，提示「文件名为空」；与同目录其他文件或文件夹冲突返回 409，不自动编号。传当前名称为成功的无修改操作。

当前重命名接口只支持文件夹，尚没有通用文件重命名接口。

### 4. 单删与单条恢复

删除：`PUT /api/v1/files/file/12`。
恢复：`PUT /api/v1/files/recover/12`。

Body 选择 None。后端按路径 ID 操作，名称和类型从数据库读取。成功返回 204，响应体为空，不能解析为 JSON。

### 5. 批量删除与批量恢复

删除：`PUT /api/v1/files/files`。
恢复：`PUT /api/v1/files/recover/batch`。

两者使用相同 Body：

```json
{
  "ids": [12, 13, 14]
}
```

`ids` 必填，1～1000 项；每项必须为正整数，不能为 null。可同时包含文件和文件夹，重复 ID 去重。所有资源必须属于当前用户；任意一项越权或不存在，整批失败。成功返回 204，无响应体。

### 6. 回收站列表

`GET /api/v1/files/recycle-bin?page=0&size=50`

没有 Body。只返回当前用户的已删除记录，分页参数规则同普通列表。

### 7. 上传

根目录：`POST /api/v1/files/upload`。
子目录：`POST /api/v1/files/upload?parentId=12`。

Body 选择 form-data：

| Key | 类型 | Value |
| --- | --- | --- |
| file | File | 选择实际文件 |

`parentId` 放在 Query 参数里。不要把本地文件路径写成 JSON 字符串。上传是现有文件存储功能；删除和恢复不调用磁盘存储操作。

## 认证接口的请求体

### 发送邮箱验证码

`POST /api/v1/auth/email-code`

```json
{
  "email": "student@example.com"
}
```

### 注册

`POST /api/v1/auth/register`

```json
{
  "email": "student@example.com",
  "nickName": "student01",
  "password": "example-password",
  "emailCode": "123456"
}
```

`emailCode` 替换为实际收到的验证码。邮箱最长 128，用户名 `nickName` 为 3～32 字符，密码为 6～64 字符。`confirmPassword` 不提交，`checkCode` 当前未参与注册校验。

### 获取登录图形验证码

`GET /api/v1/auth/captcha?username=student01`

没有 Body。添加 `X-Device-Id` 请求头，值为客户端生成的 UUID，例如 `123e4567-e89b-42d3-a456-426614174000`。返回的 `data.image` 用于显示验证码图片。

### 登录

`POST /api/v1/auth/login`

```json
{
  "username": "student01",
  "password": "example-password",
  "captchaCode": "1234"
}
```

`captchaCode` 替换为新获取图片中的 4 位数字。`X-Device-Id` 必须与获取验证码时相同；同时携带 CSRF Header 和 Cookie。登录成功返回用户信息，JWT 写入 HttpOnly Cookie。

### 修改密码

`POST /api/v1/auth/change-password`

```json
{
  "currentPassword": "example-password",
  "newPassword": "new-example-password"
}
```

新密码为 6～64 字符。成功后需要重新登录。

### 无请求体的认证接口

| 方法与路径 | 说明 |
| --- | --- |
| GET `/api/v1/auth/csrf` | 获取 CSRF 值 |
| GET `/api/v1/auth/me` | 查询当前登录用户 |
| POST `/api/v1/auth/logout` | 退出登录；仍须 CSRF Header 和 Cookie |

## 响应对象怎么读

### 普通成功响应：Result

例如创建文件夹返回 201：

```json
{
  "success": true,
  "code": "OK",
  "message": "操作成功",
  "data": {
    "id": 12,
    "name": "学习资料",
    "type": "DIRECTORY",
    "sizeBytes": 0,
    "mimeType": null,
    "updatedAt": "2026-09-26T12:00:00"
  }
}
```

返回的 `data.id` 可用于进入目录、改名、删除。它不是需要手动指定的创建参数。

| 响应对象 | 字段 |
| --- | --- |
| FileItemResponse | id、name、type（FILE / DIRECTORY）、sizeBytes、mimeType、updatedAt |
| FileListResponse | currentDirectory、breadcrumbs、items（FileItemResponse 数组）、page |
| FileDirectoryResponse | id、name、parentId |
| FileBreadcrumb | id、name；根目录 ID 为 null |
| FilePageResponse | number、size、totalElements、totalPages |
| TrashItemResponse | id、name、type、sizeBytes、deletedAt、parentId、parentName |
| TrashListResponse | items（TrashItemResponse 数组）、page |
| LoginSessionResponse | userId、username |
| CaptchaResponse | image、prompt |

### 错误响应有两种现有约定

创建、改名及认证接口使用 Result，例如重命名传空字符串：

```json
{
  "success": false,
  "code": "INVALID_PARAM",
  "message": "文件名为空",
  "data": null
}
```

删除、恢复及回收站接口错误使用数字 HTTP 状态码：

```json
{
  "code": 403,
  "msg": "只能操作自己的文件或文件夹",
  "data": null
}
```

参数错误 400、未登录 401、越权 403、不存在 404；恢复目录结构异常 409；数据库操作故障 500。

## Java 对象在哪里

路径均相对于项目根目录。请求对象负责接收参数，响应对象负责输出结果；数据库实体 `UserFile` 不是 API 请求体。

| 对象 | 源码位置 |
| --- | --- |
| CreateDirectoryRequest | `backend/src/main/java/com/networkdisk/file/CreateDirectoryRequest.java` |
| RenameDirectoryRequest | `backend/src/main/java/com/networkdisk/file/RenameDirectoryRequest.java` |
| FileIdsRequest | `backend/src/main/java/com/networkdisk/file/FileIdsRequest.java` |
| 文件响应对象 | `backend/src/main/java/com/networkdisk/file/*Response.java` |
| RegisterRequest | `backend/src/main/java/com/networkdisk/auth/RegisterRequest.java` |
| LoginRequest | `backend/src/main/java/com/networkdisk/auth/LoginRequest.java` |
| ChangePasswordRequest | `backend/src/main/java/com/networkdisk/auth/ChangePasswordRequest.java` |
| EmailCodeRequest、LoginSessionResponse | 定义在 `backend/src/main/java/com/networkdisk/auth/AuthController.java` 中 |
| Result | `backend/src/main/java/com/networkdisk/common/Result.java` |
| FileOperationError | `backend/src/main/java/com/networkdisk/file/FileOperationError.java` |

修改接口时同步更新本文件；路由和实际字段以 Controller、Request DTO 为准。
