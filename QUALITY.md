# 当前代码规范状态

2026-10-24，Core Kotlin 生产源码使用 Detekt CLI 1.23.8 默认规则复扫，保留 749 条提示。没有 suppress、baseline 或提高阈值；这些提示没有被清零，也不等同于 749 个已证实的功能漏洞。完整的文件、行号与规则见 [扫描结果](scripts/quality/detekt-findings.xml)。这是一份扫描报告，不是让扫描器忽略问题的 baseline。

| 类别 | 数量 | 处理边界 |
| --- | ---: | --- |
| 数值常量 | 478 | 包含界面和协议数值，需结合消费者判断，不能机械换名证明正确 |
| 长度、复杂度、参数、嵌套、函数数等 | 195 | 既有结构仍有整理空间；不为降数字改变已定行为或强拆方法 |
| 异常处理相关 | 75 | 涉及取消、可选能力和失败边界，保留逐项记录；尚未做完每一条的运行时语义审查 |
| 行长 | 1 | 保留现有文案，不以删内容掩盖提示 |

以上保留不代表无法改进。当前没有技术债清零、完整安全认证或全部规范通过的结论。已确认的启动、异步和构建缺陷按真实复现修复；其他提示的进一步重构需要对应行为证据。

复扫时使用官方 Detekt CLI 1.23.8，变量指向审阅者自己的工具 JAR：

```sh
java -jar "$DETEKT_CLI_JAR" --input android/app/src/main/java \
  --base-path . --report xml:detekt-current.xml
```

发现问题时 CLI 会非零退出，不能把它改写成全绿。对比应使用相同版本和默认规则，不改变输入范围。工具 JAR 不随 App 或源码包分发。

当前已执行 Core 原生 167 项、交互 85 项、发布工具 36 项；真实设备、Linux最终构建、lint与签名分别按同次发布记录报告。有效账号测试由用户明确跳过。依赖安全与许可证是另一组证据，见 [SECURITY.md](SECURITY.md) 和 [BUILD.md](BUILD.md)。

## 最终 Release lint

最终 Linux 干净重建实测 **0 错误、34 警告**，逐项记录见 [lint-findings.xml](scripts/quality/lint-findings.xml)。报告中的构建机绝对路径已改为相对源码路径或环境占位，问题、位置和行号未删改。此前 35/36 的数字属于旧轮次；不把更新建议的数量波动当作修复。

| 规则 | 数量 | 当前处理 |
| --- | ---: | --- |
| UseKtx | 21 | 风格迁移建议；未做机械替换，不代表不能改，也不算功能漏洞 |
| NewerVersionAvailable | 5 | 固定依赖的更新建议；需兼容/许可回归，不能仅为清提示盲升 |
| AndroidGradlePluginVersion | 1 | 构建插件更新建议；当前锁定组合已完成构建，不自动升级整个工具链 |
| OldTargetApi | 1 | compile SDK 37、target 35；改变 target 会改变系统行为，需要单独兼容验证 |
| ChromeOsAbiSupport | 1 | 当前产品明确仅 ARM64 Android，不增加 ChromeOS/x86 支持 |
| SetJavaScriptEnabled | 2 | 对话与可信插件需要执行脚本，不能禁用功能来消警告；同 UID 代码需受信 |
| InsecureBaseConfiguration | 1 | 通用宿主保留 HTTP 能力用于回环及用户指定的端点；模型凭据走固定 HTTPS，不把明文 HTTP 描述为安全传输 |
| UsableSpace | 1 | 既有运行时安装空间检查的 API 建议，未把估算空间当成安装成功证据 |
| ViewConstructor | 1 | 定制视图由代码创建，不从 XML inflate，未添加无人调用的构造器 |

这些是明确保留或尚未重构的项目，不是“已全部修复”。
