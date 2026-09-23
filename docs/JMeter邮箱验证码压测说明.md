# JMeter 邮箱验证码接口压测

## 安全要求

压测前必须关闭真实邮件发送：

```powershell
$env:MAIL_ENABLED="false"
cd C:\Users\11939\Desktop\NetworkDemo\backend
mvn spring-boot:run
```

这样仍会执行验证码生成、限流和内存保存，但不会连接 QQ SMTP，也不会发送邮件。

## 安装检查

安装 JMeter 后，确认命令可用：

```powershell
jmeter --version
```

如果命令不可用，请把 JMeter 的 `bin` 目录加入系统 `PATH`。

## 生成 HTML 报告

在项目根目录执行：

```powershell
$resultDir = "C:\Users\11939\Desktop\NetworkDemo\docs\jmeter-results"
$reportDir = "C:\Users\11939\Desktop\NetworkDemo\docs\jmeter-report"

jmeter -n `
  -t ".\docs\邮箱验证码接口压测.jmx" `
  -l "$resultDir\结果.jtl" `
  -e `
  -o "$reportDir"
```

参数说明：

- `-n`：非 GUI 模式，适合压测
- `-t`：指定测试计划
- `-l`：保存原始结果
- `-e`：根据结果生成 Dashboard
- `-o`：指定 HTML 报告目录

生成后打开：

```text
docs\jmeter-report\index.html
```

报告会包含吞吐量（QPS）、平均响应时间、P90/P95/P99、错误率和响应时间分布等指标。

## 当前测试参数

- 并发线程数：500
- 启动时间：10 秒
- 每个线程循环：50 次
- 预计请求数：1000 次
- 目标接口：`POST /api/v1/auth/email-code`

## 注意事项

当前验证码保存在内存中，压测结果只能反映单实例业务接口的性能。压测完成后删除或清理 `jmeter-results` 和 `jmeter-report` 目录中的结果文件即可。
