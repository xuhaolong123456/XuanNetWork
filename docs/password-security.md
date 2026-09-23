# 密码加密与 BCrypt 说明

## 1. 为什么不能保存明文密码

用户注册时不能直接把密码保存到数据库：

```text
错误做法：123456
```

如果数据库泄露，攻击者可以直接获得所有用户密码，而且用户可能在其他网站重复使用相同密码。

因此，数据库只保存密码的安全哈希结果，不保存原始密码。

## 2. BCrypt 的基本流程

项目使用 Spring 提供的 `BCryptPasswordEncoder`：

```java
private final BCryptPasswordEncoder passwordEncoder;
```

### 2.1 注册时

注册时，用户提交原始密码，例如：

```text
123456
```

BCrypt 会自动生成随机盐值，并使用密码、盐值和计算强度进行专门的密码哈希计算：

```text
原始密码 + 随机盐值
        ↓
BCrypt 哈希计算
        ↓
保存结果到 password_hash
```

代码：

```java
String passwordHash = passwordEncoder.encode(request.password());
```

数据库保存的是类似下面的 BCrypt 字符串，而不是明文密码：

```text
$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy
```

BCrypt 字符串中包含算法版本、计算强度、盐值和哈希结果。

### 2.2 登录时

登录时不会解密数据库中的密码，也不会把数据库密码还原成明文。

BCrypt 会从数据库中的密文读取盐值和计算强度，然后对用户本次输入的密码重新计算，最后比较两个结果：

```text
用户输入的密码
        ↓
读取数据库密文中的盐值和计算强度
        ↓
BCrypt 重新计算
        ↓
和数据库中的哈希结果比较
```

代码：

```java
boolean matched = passwordEncoder.matches(
        rawPassword,
        user.getPasswordHash()
);
```

## 3. 什么是加盐

盐值是为每个密码随机生成的一段数据。

如果不加盐，相同密码通常会得到相同哈希值：

```text
123456 -> 固定哈希值
123456 -> 固定哈希值
```

加盐后，即使两个用户的密码都是 `123456`，由于盐值不同，最终结果也不同：

```text
盐值 A + 123456 -> 哈希值 A
盐值 B + 123456 -> 哈希值 B
```

这样可以有效降低彩虹表和批量密码比对攻击的风险。

BCrypt 会自动生成和保存盐值，不需要业务代码手动管理：

```java
passwordEncoder.encode(rawPassword);
passwordEncoder.matches(rawPassword, encodedPassword);
```

盐值不需要单独保密，它的作用是让相同密码产生不同的哈希结果。

## 4. 为什么选择 BCrypt

### 4.1 专门用于密码存储

BCrypt 不是普通的数据摘要算法，而是专门为密码存储设计的密码哈希算法。

### 4.2 自动加盐

BCrypt 自动生成随机盐值，并将盐值放入最终密文中，减少手动实现出错的可能。

### 4.3 计算速度故意较慢

密码哈希不能追求越快越好。BCrypt 的计算速度比 MD5、SHA-1、普通 SHA-256 慢，可以增加攻击者暴力尝试密码的成本。

### 4.4 支持计算强度

BCrypt 支持调整工作因子：

```java
new BCryptPasswordEncoder(10);
```

强度越高，计算成本越大。当前项目使用默认配置，适合开发阶段和普通业务场景。

### 4.5 Spring 集成成熟

Spring 直接提供 `BCryptPasswordEncoder`，使用方式简单，社区实践广泛，适合当前 Spring Boot 项目。

## 5. 为什么不用 MD5 或 SHA-256

不建议使用以下方式保存用户密码：

```text
MD5(password)
SHA1(password)
SHA256(password)
```

原因是这些算法设计目标是快速计算，攻击者可以使用 GPU 或专用设备快速尝试大量密码，而且普通用法通常没有独立盐值。

它们可以用于文件完整性校验，但不适合直接用于用户密码存储。

## 6. 可选替代方案

如果项目后续有更高的安全要求，也可以考虑：

- `Argon2PasswordEncoder`：现代密码哈希算法，抗硬件破解能力较强
- `SCryptPasswordEncoder`：增加内存消耗，提高暴力破解成本
- `Pbkdf2PasswordEncoder`：标准化程度较高

当前项目选择 BCrypt，是因为它成熟、稳定、容易维护，并且已经有 Spring 官方实现。

## 7. 项目中的安全约束

- 不记录明文密码日志。
- 不在接口响应中返回密码或 `passwordHash`。
- 不在前端保存明文密码。
- 登录时只使用 `matches()` 校验密码。
- 登录失败统一返回“邮箱或密码错误”，避免泄露账号是否存在。
- 生产环境使用 HTTPS，避免密码在传输过程中被窃取。

