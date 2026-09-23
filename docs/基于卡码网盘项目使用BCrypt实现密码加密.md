# Spring Boot 项目使用 BCrypt 实现密码加密与登录校验

> 本文基于“卡码网盘”项目的真实代码，记录 BCrypt 的依赖引入、Bean 配置、变量注入、注册密码加密以及登录密码校验过程。

## 一、为什么密码不能直接保存

用户注册时提交的是原始密码。如果直接将原始密码保存到 MySQL，一旦数据库泄露，所有用户的密码都会直接暴露。

本项目采用 BCrypt 对密码进行单向哈希：

```text
Vue 前端提交原始密码（生产环境必须使用 HTTPS）
                    ↓
Spring Boot 后端接收密码
                    ↓
BCryptPasswordEncoder.encode() 生成哈希
                    ↓
MySQL 只保存 password_hash
```

登录时不会解密 BCrypt 哈希，而是使用 `matches()` 判断用户输入的密码是否与数据库哈希匹配。

BCrypt 自带随机盐，因此同一个密码每次调用 `encode()` 得到的结果通常不同。这是正常现象，不能用字符串相等来判断密码。

## 二、本项目使用的技术环境

- Java 21
- Spring Boot 3.5.5
- Maven
- Spring Security Crypto
- Spring Data JPA
- MySQL

项目中的相关文件：

```text
backend/pom.xml
backend/src/main/java/com/networkdisk/config/PasswordConfig.java
backend/src/main/java/com/networkdisk/auth/AuthService.java
backend/src/main/java/com/networkdisk/auth/User.java
```

## 三、在 Maven 中导入 BCrypt 依赖

打开后端项目的 `pom.xml`，在 `<dependencies>` 中加入：

```xml
<dependency>
    <groupId>org.springframework.security</groupId>
    <artifactId>spring-security-crypto</artifactId>
</dependency>
```

本项目使用 Spring Boot 父工程管理依赖版本：

```xml
<parent>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-parent</artifactId>
    <version>3.5.5</version>
    <relativePath/>
</parent>
```

因此 `spring-security-crypto` 一般不需要手动填写 `<version>`，Spring Boot 会提供相互兼容的版本。

添加依赖后，可以在项目根目录执行：

```bash
mvn test
```

如果 IDE 仍提示找不到类，需要重新加载 Maven 项目。

需要注意：这里只引入了密码加密模块，并没有引入完整的 Spring Security Web 登录体系。

## 四、在 Java 文件中导入 BCrypt 类

依赖下载完成后，在需要声明 BCrypt Bean 或使用 BCrypt 的 Java 类中加入：

```java
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
```

“Maven 导入依赖”和“Java 导入类”是两个不同步骤：

- `pom.xml` 的 `<dependency>` 让项目获得 BCrypt 类库。
- Java 文件中的 `import` 让当前源文件可以直接使用 `BCryptPasswordEncoder` 类名。

## 五、把 BCrypt 配置成 Spring Bean

本项目没有在每次注册时手动 `new BCryptPasswordEncoder()`，而是在配置类中统一创建对象。

文件：`PasswordConfig.java`

```java
package com.networkdisk.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

@Configuration
public class PasswordConfig {

    @Bean
    public BCryptPasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
```

这里的两个注解含义如下：

- `@Configuration`：告诉 Spring 这是一个配置类。
- `@Bean`：把方法返回的 `BCryptPasswordEncoder` 对象交给 Spring 容器管理。

应用启动后，Spring 容器中就会存在一个 `BCryptPasswordEncoder` Bean，其他业务类可以直接注入。

### 是否需要设置强度参数

默认写法是：

```java
new BCryptPasswordEncoder()
```

也可以显式指定强度，例如：

```java
new BCryptPasswordEncoder(12)
```

强度越高，计算成本越高，抵抗暴力破解的能力也越强，但注册和登录会更慢。没有经过压测时，使用默认值通常更稳妥。

## 六、在 Service 中引入 BCrypt 变量

本项目采用构造器注入，这是 Spring 官方实践中常见且便于测试的方式。

首先，在 `AuthService` 中声明成员变量：

```java
private final BCryptPasswordEncoder passwordEncoder;
```

然后把它写进构造方法参数，并赋值给成员变量：

```java
public AuthService(UserRepository userRepository,
                   BCryptPasswordEncoder passwordEncoder,
                   TokenService tokenService) {
    this.userRepository = userRepository;
    this.passwordEncoder = passwordEncoder;
    this.tokenService = tokenService;
}
```

完整的关键结构如下：

```java
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;

@Service
public class AuthService {

    private final BCryptPasswordEncoder passwordEncoder;

    public AuthService(BCryptPasswordEncoder passwordEncoder) {
        this.passwordEncoder = passwordEncoder;
    }
}
```

因为 `PasswordConfig` 已经通过 `@Bean` 注册了该类型的对象，所以 Spring 创建 `AuthService` 时，会自动找到并传入这个 Bean。

当前类只有一个构造方法，因此不需要再写 `@Autowired`。

不建议写成可变的字段注入：

```java
@Autowired
private BCryptPasswordEncoder passwordEncoder;
```

构造器注入可以让依赖关系更清晰，字段还可以声明为 `final`，单元测试时也更容易传入 Mock 对象。

## 七、注册时使用 encode() 加密密码

卡码网盘项目的注册逻辑位于 `AuthService.register()`。

核心代码：

```java
User user = userRepository.save(new User(
        email,
        nickName,
        passwordEncoder.encode(request.password())
));
```

其中：

```java
request.password()
```

是前端提交的原始密码；

```java
passwordEncoder.encode(request.password())
```

会生成 BCrypt 哈希。传给 `User` 实体和数据库的只有哈希值，不是原始密码。

为了让流程更容易理解，也可以拆成变量：

```java
String rawPassword = request.password();
String passwordHash = passwordEncoder.encode(rawPassword);

User user = new User(email, nickName, passwordHash);
userRepository.save(user);
```

注意不要输出以下日志：

```java
System.out.println(rawPassword);
```

原始密码不应写入数据库、日志、接口响应或异常信息。

## 八、数据库只保存 BCrypt 哈希

本项目的 `User` 实体使用 `password_hash` 字段保存密码哈希：

```java
@Column(name = "password_hash", nullable = false, length = 100)
private String passwordHash;
```

推荐的 MySQL 字段定义为：

```sql
password_hash VARCHAR(100) NOT NULL
```

BCrypt 哈希通常以 `$2a$`、`$2b$` 等开头。字段长度不要按用户原始密码长度设置，预留 `100` 个字符比较合适。

实体构造方法接收的参数也应明确命名为 `passwordHash`：

```java
public User(String email, String nickName, String passwordHash) {
    this.email = email;
    this.nickName = nickName;
    this.passwordHash = passwordHash;
    this.createdAt = LocalDateTime.now();
}
```

不要给接口响应对象添加 `passwordHash` 字段。即使哈希不是明文，也不应该返回给前端。

## 九、登录时使用 matches() 校验密码

本项目要求使用用户名登录，不使用邮箱登录。后端先按用户名查询用户，再校验密码：

```java
@Transactional(readOnly = true)
public LoginResponse login(LoginRequest request) {
    String username = request.username().trim();

    User user = userRepository.findByNickName(username)
            .orElseThrow(AuthService::loginFailed);

    if (!passwordEncoder.matches(
            request.password(),
            user.getPasswordHash())) {
        throw loginFailed();
    }

    return new LoginResponse(
            tokenService.create(user.getId()),
            user.getId(),
            user.getNickName()
    );
}
```

`matches()` 的参数顺序非常重要：

```java
passwordEncoder.matches(用户输入的原始密码, 数据库中的BCrypt哈希)
```

即：

```java
boolean matched = passwordEncoder.matches(rawPassword, passwordHash);
```

不能在登录时重新调用 `encode()` 后比较字符串：

```java
// 错误示例
passwordEncoder.encode(rawPassword).equals(passwordHash)
```

因为 BCrypt 每次加密都会使用随机盐，即使原始密码相同，生成的哈希也通常不同。

## 十、随机加盐后，登录时为什么还能校验成功

这是使用 BCrypt 时最容易产生疑问的地方：注册和登录时如果使用了不同的随机盐，生成的哈希确实会不同，那么系统是怎样判断密码正确的？

关键点是：**登录时不会重新调用 `encode()` 生成一个新哈希，再与数据库字符串直接比较，而是调用 `matches()`。**

注册时执行：

```java
String passwordHash = passwordEncoder.encode("123456");
```

数据库中保存的完整 BCrypt 字符串类似：

```text
$2a$10$abcdefghijklmnopqrstuu1VnV3fTn0exampleHashValue
```

这个字符串不只是单纯的哈希结果，其中还包含：

```text
算法版本 + 计算强度 + 注册时生成的盐值 + 密码哈希结果
```

例如：

```text
$2a$  → BCrypt 算法版本
10$   → 计算强度 cost
后续内容 → 盐值和哈希结果
```

因此，盐值不需要另外创建数据库字段。只要完整保存 `encode()` 返回的 BCrypt 字符串，注册时使用的盐值就不会丢失。

登录时，本项目执行：

```java
passwordEncoder.matches(
        request.password(),
        user.getPasswordHash()
)
```

`matches()` 内部会完成以下过程：

```text
读取数据库中的完整 BCrypt 字符串
                    ↓
解析出注册时使用的算法版本、计算强度和盐值
                    ↓
使用解析出的原盐值计算本次输入的密码
                    ↓
将计算结果与数据库中的哈希结果进行安全比较
                    ↓
返回 true 或 false
```

所以，登录校验使用的是数据库哈希中记录的原盐值，并不会为本次比较生成一个新盐。

下面这段代码可以直接验证：

```java
BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();

String hash1 = encoder.encode("123456");
String hash2 = encoder.encode("123456");

System.out.println(hash1);
System.out.println(hash2);

// 两次 encode() 使用不同的随机盐，所以结果通常不相等。
System.out.println(hash1.equals(hash2));
// false

// matches() 会读取 hash1 中原有的盐值，因此可以正确校验。
System.out.println(encoder.matches("123456", hash1));
// true

// hash2 使用另一份随机盐，但同样可以独立完成校验。
System.out.println(encoder.matches("123456", hash2));
// true

// 输入错误密码时校验失败。
System.out.println(encoder.matches("654321", hash1));
// false
```

错误的登录校验方式是：

```java
// 错误：encode() 会生成新的随机盐，结果通常不会等于数据库哈希。
boolean matched = passwordEncoder
        .encode(request.password())
        .equals(user.getPasswordHash());
```

正确方式是：

```java
// 正确：从数据库哈希中读取注册时的盐值并完成校验。
boolean matched = passwordEncoder.matches(
        request.password(),
        user.getPasswordHash()
);
```

随机盐的价值在于：即使两个用户设置了完全相同的密码，他们保存在数据库中的 BCrypt 字符串通常也不相同。攻击者不能只通过观察相同的哈希，就判断两个用户使用了相同密码。

## 十一、为什么用户名不存在和密码错误返回同一句话

本项目统一返回：

```java
private static AuthBusinessException loginFailed() {
    return new AuthBusinessException("LOGIN_FAILED", "用户名或密码错误");
}
```

不要分别提示“用户名不存在”和“密码错误”，否则攻击者可以通过接口判断某个用户名是否已经注册。

## 十二、前端需要使用 BCrypt 吗

不需要。

BCrypt 放在 Spring Boot 后端，Vue 前端只负责收集密码并通过 HTTPS 提交：

```javascript
await login({
  username: username.value,
  password: password.value
})
```

前端不应该：

- 保存原始密码；
- 把密码写进 `localStorage`；
- 保存数据库中的 BCrypt 哈希；
- 把前端哈希当作 HTTPS 的替代品。

在普通的用户名密码登录场景中，传输安全由 HTTPS 负责，密码存储安全由后端 BCrypt 负责。

## 十三、如何编写单元测试

可以使用真实的 `BCryptPasswordEncoder` 验证注册密码是否被正确哈希：

```java
BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder();

String rawPassword = "123456";
String passwordHash = passwordEncoder.encode(rawPassword);

assertThat(passwordHash).isNotEqualTo(rawPassword);
assertThat(passwordEncoder.matches(rawPassword, passwordHash)).isTrue();
assertThat(passwordEncoder.matches("wrong-password", passwordHash)).isFalse();
```

本项目中的测试还会检查：

- 注册后保存的不是原始密码；
- 正确密码可以登录；
- 错误密码不能登录；
- 用户名不存在时不能签发令牌。

执行测试：

```bash
cd backend
mvn test
```

## 十四、常见错误总结

### 1. 找不到 BCryptPasswordEncoder

检查 `pom.xml` 是否加入：

```xml
<artifactId>spring-security-crypto</artifactId>
```

并检查 Java 文件是否加入：

```java
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
```

### 2. Spring 提示找不到 BCryptPasswordEncoder Bean

检查是否存在：

```java
@Configuration
public class PasswordConfig {
    @Bean
    public BCryptPasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
```

配置类还必须位于 Spring Boot 启动类能够扫描到的包路径下。本项目启动类位于 `com.networkdisk`，配置类位于 `com.networkdisk.config`，因此可以被自动扫描。

### 3. 同一个密码加密结果不一样

这是 BCrypt 随机盐的正常效果。请使用 `matches()`，不要直接比较两个哈希字符串。

### 4. 登录一直提示密码错误

检查参数顺序是否正确：

```java
matches(rawPassword, passwordHash)
```

并确认数据库保存的是 `encode()` 的返回值，而不是原始密码或被重复加密的字符串。

### 5. 把 BCrypt 放在前端

不建议。前端代码和数据都可以被用户查看，而且前端哈希无法代替 HTTPS。BCrypt 应由可信的后端执行。

## 十五、完整流程总结

```text
注册：
前端原始密码
    → RegisterRequest
    → passwordEncoder.encode(rawPassword)
    → User.passwordHash
    → MySQL password_hash

登录：
前端用户名和原始密码
    → 按用户名查询 User
    → passwordEncoder.matches(rawPassword, passwordHash)
    → 成功后签发登录令牌
```

本项目使用 BCrypt 的关键只有两句：

```java
String passwordHash = passwordEncoder.encode(rawPassword);
boolean matched = passwordEncoder.matches(rawPassword, passwordHash);
```

真正需要注意的是：BCrypt 必须放在后端、数据库只保存哈希、登录必须使用 `matches()`、生产环境必须启用 HTTPS，并且任何接口都不能返回密码哈希。
